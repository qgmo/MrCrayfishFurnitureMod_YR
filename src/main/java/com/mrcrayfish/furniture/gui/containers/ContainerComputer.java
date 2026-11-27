package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.computer.ComputerPeripherals;
import com.mrcrayfish.furniture.gui.inventory.InventoryPeripherals;
import com.mrcrayfish.furniture.gui.slots.SlotPeripheral;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import com.mrcrayfish.furniture.power.ApplianceContainers;
import com.mrcrayfish.furniture.power.PartRules;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketSetSlot;

import javax.annotation.Nullable;

/**
 * 电脑的界面（悠然服务器魔改）：浏览器本身全在客户端，服务端这边只有能量单元卡槽、两格外设槽（什么外设都能插，只有定位模块、身份模块起作用）和玩家背包（界面上的“物品”面板），
 * 并把缓存电量、工作状态同步给界面。原来 MineBay 的交易槽去掉了（MineBay 改成了网站）。
 * 外设槽只有操作者、且过保护检查（同接管电脑）的玩家能放、拿（服主 11-15）；旁观者点了服务端不认，并把界面重新同步给他。
 */
public class ContainerComputer extends Container
{
    /** 面板大小与卡槽位置（相对面板左上角）；卡槽那一排下面一行写 CPU（服主 11-16），再下面是玩家背包 */
    public static final int PANEL_WIDTH = 176, PANEL_HEIGHT = 126;
    public static final int CPU_Y = 31;
    static final int INVENTORY_Y = 44;
    public static final int CARD_X = 8, CARD_Y = 8;
    /** 第一格外设槽的位置（右上角，往右一格一格排，标签画在最后一格右边） */
    public static final int PERIPHERAL_X = 86, PERIPHERAL_Y = 8;
    /** 外设槽的标签从哪里画起 */
    public static final int PERIPHERAL_LABEL_X = PERIPHERAL_X + 18 * ComputerPeripherals.SLOTS + 2;

    private final TileEntityComputer computer;
    @Nullable
    private final SlotPowerCard cardSlot;
    private final int cardIndex;
    private final SlotPeripheral[] peripheralSlots = new SlotPeripheral[ComputerPeripherals.SLOTS];
    /** 第一格外设槽的编号（各格连着） */
    private final int peripheralIndex;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    public ContainerComputer(IInventory playerInventory, TileEntityComputer computer)
    {
        this.computer = computer;

        for(int i = 0; i < 3; i++)
        {
            for(int j = 0; j < 9; ++j)
            {
                this.addSlotToContainer(new Slot(playerInventory, j + i * 9 + 9, j * 18 + 8, i * 18 + INVENTORY_Y));
            }
        }
        for(int i = 0; i < 9; i++)
        {
            this.addSlotToContainer(new Slot(playerInventory, i, i * 18 + 8, INVENTORY_Y + 58));
        }
        this.cardSlot = ApplianceContainers.cardSlot(computer, computer, CARD_X, CARD_Y);
        if(cardSlot != null)
        {
            this.cardIndex = this.inventorySlots.size();
            this.addSlotToContainer(cardSlot);
        }
        else
        {
            this.cardIndex = -1;
        }
        // 外设槽：加在所有槽位之后（原有槽位编号不变）；两端都加
        InventoryPeripherals peripherals = new InventoryPeripherals(computer.getPeripherals(), computer::markDirty, computer::isUsableByPlayer);
        this.peripheralIndex = this.inventorySlots.size();
        for(int i = 0; i < ComputerPeripherals.SLOTS; i++)
        {
            peripheralSlots[i] = new SlotPeripheral(peripherals, i, PERIPHERAL_X + 18 * i, PERIPHERAL_Y, computer::canChangePeripherals);
            this.addSlotToContainer(peripheralSlots[i]);
        }
    }

    /** 第 {@code i} 格外设槽 */
    public SlotPeripheral getPeripheralSlot(int i)
    {
        return peripheralSlots[i];
    }

    public boolean isPeripheralSlot(@Nullable Slot slot)
    {
        for(SlotPeripheral s : peripheralSlots)
        {
            if(s == slot)
                return true;
        }
        return false;
    }

    private boolean isPeripheralIndex(int slotId)
    {
        return slotId >= peripheralIndex && slotId < peripheralIndex + ComputerPeripherals.SLOTS;
    }

    /** 外设槽：不是操作者（或过不了保护检查）的玩家点它、或 Shift 点背包里的外设都不算；服务端把界面与手上拿着的重新同步给他 */
    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player)
    {
        // 先过“只许创造模式改部件”的统一拦截（卡槽、外设槽），再是下面外设槽原有的操作者判断
        return PartRules.slotClick(this, slotId, dragType, clickType, player, this::operatorClick);
    }

    private ItemStack operatorClick(int slotId, int dragType, ClickType clickType, EntityPlayer player)
    {
        boolean touchesPeripheral = isPeripheralIndex(slotId)
                || (clickType == ClickType.QUICK_MOVE && slotId >= 0 && slotId < 36 && ComputerPeripherals.isPeripheral(inventorySlots.get(slotId).getStack()));
        if(touchesPeripheral && !peripheralSlots[0].canChange(player))
        {
            if(player instanceof EntityPlayerMP)
            {
                EntityPlayerMP mp = (EntityPlayerMP) player;
                mp.sendAllContents(this, this.getInventory());
                mp.connection.sendPacket(new SPacketSetSlot(-1, -1, mp.inventory.getItemStack()));
            }
            if(isPeripheralIndex(slotId))
                return ItemStack.EMPTY;
        }
        return super.slotClick(slotId, dragType, clickType, player);
    }

    @Override
    public boolean canDragIntoSlot(Slot slot)
    {
        // 拖动分发不进外设槽（只能单独点）
        return !isPeripheralSlot(slot) && super.canDragIntoSlot(slot);
    }

    public TileEntityComputer getComputer()
    {
        return computer;
    }

    /** 兼容原来的叫法 */
    public IInventory getComputerInventory()
    {
        return computer;
    }

    @Nullable
    public SlotPowerCard getCardSlot()
    {
        return cardSlot;
    }

    @Override
    public void addListener(IContainerListener listener)
    {
        super.addListener(listener);
        sync.addListener(this, listener, computer.getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        sync.detect(this, this.listeners, computer.getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(ApplianceContainers.Sync.update(id, data, computer.getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer entityPlayer)
    {
        // 方块实体还在、距离不超过 8 格（同原版箱子）
        return !computer.isInvalid() && computer.getWorld() != null && computer.getWorld().getTileEntity(computer.getPos()) == computer
                && entityPlayer.getDistanceSq(computer.getPos().getX() + 0.5, computer.getPos().getY() + 0.5, computer.getPos().getZ() + 0.5) <= 64.0;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer entityPlayer, int slotNum)
    {
        ItemStack card = ApplianceContainers.transfer(this, entityPlayer, slotNum, cardIndex, 0, 36, this::mergeItemStack);
        if(card != null)
            return card;
        ItemStack peripheral = transferPeripheral(entityPlayer, slotNum);
        if(peripheral != null)
            return peripheral;
        if(slotNum < 0 || slotNum >= 36)
            return ItemStack.EMPTY;
        // 背包与快捷栏之间挪
        Slot slot = this.inventorySlots.get(slotNum);
        if(slot == null || !slot.getHasStack())
            return ItemStack.EMPTY;
        ItemStack item = slot.getStack();
        ItemStack copy = item.copy();
        if(slotNum < 27)
        {
            if(!this.mergeItemStack(item, 27, 36, false))
                return ItemStack.EMPTY;
        }
        else if(!this.mergeItemStack(item, 0, 27, false))
        {
            return ItemStack.EMPTY;
        }
        if(item.isEmpty())
            slot.putStack(ItemStack.EMPTY);
        else
            slot.onSlotChanged();
        return copy;
    }

    /** 外设槽相关的 Shift 点击；不归它管返回 null */
    private ItemStack transferPeripheral(EntityPlayer player, int slotNum)
    {
        if(slotNum < 0 || slotNum >= inventorySlots.size())
            return null;
        Slot slot = inventorySlots.get(slotNum);
        if(slot == null || !slot.getHasStack())
            return null;
        ItemStack stack = slot.getStack();
        if(isPeripheralIndex(slotNum))
        {
            if(!peripheralSlots[0].canChange(player))
                return ItemStack.EMPTY;
            ItemStack copy = stack.copy();
            if(!this.mergeItemStack(stack, 0, 36, true))
                return ItemStack.EMPTY;
            slot.putStack(stack.isEmpty() ? ItemStack.EMPTY : stack);
            return copy;
        }
        SlotPeripheral free = null;
        for(SlotPeripheral s : peripheralSlots)
        {
            if(!s.getHasStack() && s.isItemValid(stack))
            {
                free = s;
                break;
            }
        }
        if(slotNum < 36 && ComputerPeripherals.isPeripheral(stack) && free != null)
        {
            if(!free.canChange(player))
                return ItemStack.EMPTY;
            free.putStack(stack.splitStack(1));
            if(stack.isEmpty())
                slot.putStack(ItemStack.EMPTY);
            else
                slot.onSlotChanged();
            // 只放一个；返回空，原版的 Shift 点击就不再把剩下的挪到背包别处
            return ItemStack.EMPTY;
        }
        return null;
    }

    @Override
    public void onContainerClosed(EntityPlayer player)
    {
        super.onContainerClosed(player);
        if(!player.world.isRemote)
        {
            computer.onClosed(player);
        }
    }
}
