package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.gui.inventory.InventoryRemoteBattery;
import com.mrcrayfish.furniture.gui.inventory.ItemInventory;
import com.mrcrayfish.furniture.gui.slots.SlotBattery;
import com.mrcrayfish.furniture.gui.slots.SlotDisabled;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 电视遥控器的电池仓界面（悠然服务器魔改）：一格电池 + 玩家背包。拿着的遥控器那一格锁住，不能挪走。
 * 布局同一行的箱子（槽位坐标与原版 GuiChest 一行时一致）。
 */
public class ContainerRemoteBattery extends ContainerItemInventory
{
    private final InventoryRemoteBattery batteryInventory;

    public ContainerRemoteBattery(InventoryPlayer playerInventory, InventoryRemoteBattery batteryInventory)
    {
        this.batteryInventory = batteryInventory;
        this.addSlotToContainer(new SlotBattery(batteryInventory, 0, 80, 18));

        int offset = (1 - 4) * 18;
        for(int row = 0; row < 3; row++)
        {
            for(int col = 0; col < 9; col++)
            {
                this.addSlotToContainer(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + offset));
            }
        }
        for(int col = 0; col < 9; col++)
        {
            if(col == playerInventory.currentItem)
                this.addSlotToContainer(new SlotDisabled(playerInventory, col, 8 + col * 18, 161 + offset));
            else
                this.addSlotToContainer(new Slot(playerInventory, col, 8 + col * 18, 161 + offset));
        }
    }

    @Override
    public ItemInventory getItemInventory()
    {
        return batteryInventory;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index)
    {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = this.inventorySlots.get(index);
        if(slot != null && slot.getHasStack())
        {
            ItemStack stack = slot.getStack();
            copy = stack.copy();
            if(index == 0)
            {
                if(!this.mergeItemStack(stack, 1, this.inventorySlots.size(), true))
                    return ItemStack.EMPTY;
            }
            else
            {
                if(!InventoryRemoteBattery.isBatteryItem(stack) || this.inventorySlots.get(0).getHasStack())
                    return ItemStack.EMPTY;
                // 一次只放一节
                this.inventorySlots.get(0).putStack(stack.splitStack(1));
            }
            if(stack.isEmpty())
                slot.putStack(ItemStack.EMPTY);
            else
                slot.onSlotChanged();
        }
        return copy;
    }
}
