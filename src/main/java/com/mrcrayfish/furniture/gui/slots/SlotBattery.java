package com.mrcrayfish.furniture.gui.slots;

import com.mrcrayfish.furniture.gui.inventory.InventoryRemoteBattery;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 只能放一节电池的格子（悠然服务器魔改：电视遥控器的电池仓）
 */
public class SlotBattery extends Slot
{
    public SlotBattery(IInventory inventoryIn, int index, int xPosition, int yPosition)
    {
        super(inventoryIn, index, xPosition, yPosition);
    }

    @Override
    public boolean isItemValid(ItemStack stack)
    {
        return InventoryRemoteBattery.isBatteryItem(stack);
    }

    @Override
    public int getSlotStackLimit()
    {
        return 1;
    }
}
