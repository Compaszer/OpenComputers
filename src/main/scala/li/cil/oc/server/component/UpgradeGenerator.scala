package li.cil.oc.server.component

import java.util

import li.cil.oc.Constants
import li.cil.oc.api.driver.DeviceInfo.DeviceAttribute
import li.cil.oc.api.driver.DeviceInfo.DeviceClass
import li.cil.oc.Settings
import li.cil.oc.api.Network
import li.cil.oc.api.driver.DeviceInfo
import li.cil.oc.api.internal
import li.cil.oc.api.machine.Arguments
import li.cil.oc.api.machine.Callback
import li.cil.oc.api.machine.Context
import li.cil.oc.api.network.EnvironmentHost
import li.cil.oc.api.network._
import li.cil.oc.api.prefab
import li.cil.oc.util.ExtendedNBT._
import net.minecraft.entity.item.EntityItem
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.tileentity.TileEntityFurnace

import scala.collection.convert.WrapAsJava._

class UpgradeGenerator(val host: EnvironmentHost with internal.Agent) extends prefab.ManagedEnvironment with DeviceInfo {
  override val node = Network.newNode(this, Visibility.Network).
    withComponent("generator", Visibility.Neighbors).
    withConnector().
    create()

  var inventory: Option[ItemStack] = None

  var remainingTicks = 0

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Power,
    DeviceAttribute.Description -> "Generator",
    DeviceAttribute.Vendor -> Constants.DeviceInfo.DefaultVendor,
    DeviceAttribute.Product -> "Portagen 2.0 (Rev. 3)",
    DeviceAttribute.Capacity -> "1"
  )

  override def getDeviceInfo: util.Map[String, String] = deviceInfo

  // ----------------------------------------------------------------------- //

  @Callback(doc = """function([count:number]):boolean -- Tries to insert fuel from the selected slot into the generator's queue.""")
  def insert(context: Context, args: Arguments): Array[AnyRef] = {
    val count = args.optInteger(0, 64)
    val stack = host.mainInventory.getStackInSlot(host.selectedSlot)
    if (stack == null) return result(Unit, "selected slot is empty")
    if (!TileEntityFurnace.isItemFuel(stack)) {
      return result(Unit, "selected slot does not contain fuel")
    }
    val container = stack.getItem().getContainerItem(stack)
    var consumedCount = 0
    inventory match {
      case Some(existingStack) =>
        if (!existingStack.isItemEqual(stack) ||
          !ItemStack.areItemStackTagsEqual(existingStack, stack)) {
          return result(Unit, "different fuel type already queued")
        }
        val space = existingStack.getMaxStackSize - existingStack.stackSize
        if (space <= 0) {
          return result(Unit, "queue is full")
        }
        consumedCount = math.min(stack.stackSize, math.min(space, count))
        existingStack.stackSize += consumedCount
        stack.stackSize -= consumedCount
      case _ =>
        consumedCount = math.min(stack.stackSize, count)
        inventory = Some(stack.splitStack(consumedCount))
    }
    if (consumedCount > 0 && container != null) {
      container.stackSize = consumedCount
    }
    if (stack.stackSize > 0) {
      host.mainInventory.setInventorySlotContents(host.selectedSlot, stack)
    }
    if (stack.stackSize == 0 || container != null) {
      host.mainInventory.setInventorySlotContents(host.selectedSlot, container)
    }
    
    result(true)
  }

  @Callback(doc = """function():number -- Get the size of the item stack in the generator's queue.""")
  def count(context: Context, args: Arguments): Array[AnyRef] = {
    inventory match {
      case Some(stack) => result(stack.stackSize, stack.getItem.getItemStackDisplayName(stack))
      case _ => result(0)
    }
  }

  @Callback()
  def clear(context: Context, args: Arguments): Array[AnyRef] = {
    inventory = None
    remainingTicks = 0
    result(true)
  }

  @Callback(doc = """function([count:number]):boolean -- Tries to remove items from the generator's queue.""")
  def remove(context: Context, args: Arguments): Array[AnyRef] = {
    val count = args.optInteger(0, Int.MaxValue)
    var selectSlotContainer: ItemStack = null
    inventory match {
      case Some(stack) =>
        val moveCount = Option(stack.getItem().getContainerItem(stack)) match {
          case Some(fuelContainer) =>
            // if the fuel requires a container, we can only refill containers
            Option(host.mainInventory.getStackInSlot(host.selectedSlot)) match {
              case Some(selectedStack) if selectedStack.getItem() == fuelContainer.getItem() =>
                selectSlotContainer = selectedStack.copy() // keep a copy in case we have to put it back
                1 // refill one container
              case _ => 0 // container required
            }
          case _ => count
        }
        if (moveCount == 0) {
          result(false, "fuel requires container in the selected slot")
        } else {
          val removedStack = stack.splitStack(math.min(moveCount, stack.stackSize))
          if (selectSlotContainer != null) {
            host.mainInventory.decrStackSize(host.selectedSlot, 1)
          }
          val success = host.player.inventory.addItemStackToInventory(removedStack)
          stack.stackSize += removedStack.stackSize
          if (success) {
            if (stack.stackSize <= 0) {
              inventory = None
            }
            result(true)
          } else {
            // if we decremented the container, we need to put it back
            host.mainInventory.setInventorySlotContents(host.selectedSlot, selectSlotContainer)
            result(false, "no inventory space available for fuel")
          }
        }
      case _ => result(false, "queue is empty")
    }
  }

  // ----------------------------------------------------------------------- //

  override val canUpdate = true

  override def update() {
    super.update()
    if (remainingTicks <= 0 && inventory.isDefined) {
      val stack = inventory.get
      remainingTicks = TileEntityFurnace.getItemBurnTime(stack)
      if (remainingTicks > 0) {
        updateClient()
        stack.stackSize -= 1
        if (stack.stackSize <= 0) {
            // do not put container in inventory (we left the container when fuel was inserted)
            inventory = None
        }
      }
    }
    if (remainingTicks > 0) {
      remainingTicks -= 1
      if (remainingTicks == 0 && inventory.isEmpty) {
        updateClient()
      }
      node.changeBuffer(Settings.get.generatorEfficiency)
    }
  }

  private def updateClient() = host match {
    case robot: internal.Robot => robot.synchronizeSlot(robot.componentSlot(node.address))
    case _ =>
  }

  // ----------------------------------------------------------------------- //

  override def onDisconnect(node: Node) {
    super.onDisconnect(node)
    if (node == this.node) {
      inventory match {
        case Some(stack) =>
          val world = host.world
          val entity = new EntityItem(world, host.xPosition, host.yPosition, host.zPosition, stack.copy())
          entity.motionY = 0.04
          entity.delayBeforeCanPickup = 5
          world.spawnEntityInWorld(entity)
          inventory = None
        case _ =>
      }
      remainingTicks = 0
    }
  }

  override def load(nbt: NBTTagCompound) {
    super.load(nbt)
    if (nbt.hasKey("inventory")) {
      inventory = Option(ItemStack.loadItemStackFromNBT(nbt.getCompoundTag("inventory")))
    }
    remainingTicks = nbt.getInteger("remainingTicks")
  }

  override def save(nbt: NBTTagCompound) {
    super.save(nbt)
    inventory match {
      case Some(stack) => nbt.setNewCompoundTag("inventory", stack.writeToNBT)
      case _ =>
    }
    if (remainingTicks > 0) {
      nbt.setInteger("remainingTicks", remainingTicks)
    }
  }
}
