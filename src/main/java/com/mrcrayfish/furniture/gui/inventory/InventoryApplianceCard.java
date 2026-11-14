package com.mrcrayfish.furniture.gui.inventory;

import com.mrcrayfish.furniture.compat.RfToolsCards;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/**
 * 电器界面里的能量单元卡槽（一格，悠然服务器魔改）。卡存在电器的 {@link ApplianceEnergy} 里，不算电器本身的库存
 * （漏斗、比较器碰不到它）。能不能用跟着电器的库存走。
 */
public class InventoryApplianceCard implements IInventory
{
    private final ApplianceEnergy energy;
    private final IInventory owner;

    public InventoryApplianceCard(ApplianceEnergy energy, IInventory owner)
    {
        this.energy = energy;
        this.owner = owner;
    }

    public ApplianceEnergy getEnergy()
    {
        return energy;
    }

    @Override
    public int getSizeInventory()
    {
        return 1;
    }

    @Override
    public boolean isEmpty()
    {
        return !energy.hasCard();
    }

    @Override
    public ItemStack getStackInSlot(int index)
    {
        return index == 0 ? energy.getCard() : ItemStack.EMPTY;
    }

    @Override
    public ItemStack decrStackSize(int index, int count)
    {
        if(index != 0 || count <= 0 || !energy.hasCard())
            return ItemStack.EMPTY;
        return energy.setCard(ItemStack.EMPTY);
    }

    @Override
    public ItemStack removeStackFromSlot(int index)
    {
        return index == 0 ? energy.setCard(ItemStack.EMPTY) : ItemStack.EMPTY;
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack)
    {
        if(index == 0)
        {
            energy.setCard(stack);
        }
    }

    @Override
    public int getInventoryStackLimit()
    {
        return 1;
    }

    @Override
    public void markDirty()
    {
        owner.markDirty();
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player)
    {
        return owner.isUsableByPlayer(player);
    }

    @Override
    public void openInventory(EntityPlayer player) {}

    @Override
    public void closeInventory(EntityPlayer player) {}

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack)
    {
        return RfToolsCards.isCard(stack);
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
        energy.setCard(ItemStack.EMPTY);
    }

    @Override
    public String getName()
    {
        return "cfm.appliance.card";
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
