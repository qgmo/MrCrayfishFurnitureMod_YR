package com.mrcrayfish.furniture.gui.slots;

import com.mrcrayfish.furniture.computer.ComputerPeripherals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import java.util.function.Predicate;

/**
 * 电脑界面里的外设槽（悠然服务器魔改）：只收认得的外设（{@link ComputerPeripherals#accepts}），一格一个；
 * 只有 {@code canChange} 通过的玩家（操作者、且过保护检查）能放、拿。
 */
public class SlotPeripheral extends Slot
{
    private final Predicate<EntityPlayer> canChange;

    public SlotPeripheral(IInventory inventory, int index, int xPosition, int yPosition, Predicate<EntityPlayer> canChange)
    {
        super(inventory, index, xPosition, yPosition);
        this.canChange = canChange;
    }

    public boolean canChange(EntityPlayer player)
    {
        return canChange.test(player);
    }

    @Override
    public boolean isItemValid(ItemStack stack)
    {
        return ComputerPeripherals.accepts(getSlotIndex(), stack);
    }

    @Override
    public boolean canTakeStack(EntityPlayer player)
    {
        return canChange(player) && super.canTakeStack(player);
    }

    @Override
    public int getSlotStackLimit()
    {
        return 1;
    }
}
