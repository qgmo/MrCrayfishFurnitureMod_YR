package com.mrcrayfish.furniture.gui.inventory;

import com.mrcrayfish.furniture.computer.ComputerPeripherals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.function.Predicate;

/**
 * 电脑界面里的外设槽（悠然服务器魔改）。外设存在 {@link ComputerPeripherals} 里，不算电脑本身的库存（漏斗、比较器碰不到）。
 * 谁能放、拿由槽（{@code SlotPeripheral}）与界面按操作者、保护检查决定。
 */
public class InventoryPeripherals implements IInventory
{
    private final ComputerPeripherals peripherals;
    private final Runnable dirty;
    private final Predicate<EntityPlayer> usable;

    public InventoryPeripherals(ComputerPeripherals peripherals, Runnable dirty, Predicate<EntityPlayer> usable)
    {
        this.peripherals = peripherals;
        this.dirty = dirty;
        this.usable = usable;
    }

    @Override
    public int getSizeInventory()
    {
        return ComputerPeripherals.SLOTS;
    }

    @Override
    public boolean isEmpty()
    {
        return peripherals.isEmpty();
    }

    @Override
    public ItemStack getStackInSlot(int index)
    {
        return peripherals.get(index);
    }

    @Override
    public ItemStack decrStackSize(int index, int count)
    {
        ItemStack s = peripherals.get(index);
        if(count <= 0 || s.isEmpty())
            return ItemStack.EMPTY;
        return peripherals.set(index, ItemStack.EMPTY);
    }

    @Override
    public ItemStack removeStackFromSlot(int index)
    {
        return peripherals.set(index, ItemStack.EMPTY);
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack)
    {
        peripherals.set(index, stack);
    }

    @Override
    public int getInventoryStackLimit()
    {
        return 1;
    }

    @Override
    public void markDirty()
    {
        dirty.run();
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player)
    {
        return usable.test(player);
    }

    @Override
    public void openInventory(EntityPlayer player) {}

    @Override
    public void closeInventory(EntityPlayer player) {}

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack)
    {
        return ComputerPeripherals.accepts(index, stack);
    }

    @Override
    public int getField(int id)
    {
        return 0;
    }

    @Override
    public void setField(int id, int value) {}

    @Override
    public int getFieldCount()
    {
        return 0;
    }

    @Override
    public void clear()
    {
        for(int i = 0; i < ComputerPeripherals.SLOTS; i++)
            peripherals.set(i, ItemStack.EMPTY);
    }

    @Override
    public String getName()
    {
        return "cfm.computer.peripherals";
    }

    @Override
    public boolean hasCustomName()
    {
        return false;
    }

    @Override
    public ITextComponent getDisplayName()
    {
        return new TextComponentString(getName());
    }
}
