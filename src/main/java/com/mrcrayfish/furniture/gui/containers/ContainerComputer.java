package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import com.mrcrayfish.furniture.power.ApplianceContainers;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 电脑的界面（悠然服务器魔改）：浏览器本身全在客户端，服务端这边只有能量单元卡槽和玩家背包（界面上的“物品”面板），
 * 并把缓存电量、工作状态同步给界面。原来 MineBay 的交易槽去掉了（MineBay 改成了网站）。
 */
public class ContainerComputer extends Container
{
    /** 面板大小与卡槽位置（相对面板左上角） */
    public static final int PANEL_WIDTH = 176, PANEL_HEIGHT = 114;
    public static final int CARD_X = 8, CARD_Y = 8;

    private final TileEntityComputer computer;
    @Nullable
    private final SlotPowerCard cardSlot;
    private final int cardIndex;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    public ContainerComputer(IInventory playerInventory, TileEntityComputer computer)
    {
        this.computer = computer;

        for(int i = 0; i < 3; i++)
        {
            for(int j = 0; j < 9; ++j)
            {
                this.addSlotToContainer(new Slot(playerInventory, j + i * 9 + 9, j * 18 + 8, i * 18 + 32));
            }
        }
        for(int i = 0; i < 9; i++)
        {
            this.addSlotToContainer(new Slot(playerInventory, i, i * 18 + 8, 90));
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
        ItemStack card = ApplianceContainers.transfer(this, slotNum, cardIndex, 0, 36, this::mergeItemStack);
        if(card != null)
            return card;
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
