package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.gui.containers.ContainerFridge;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import javax.annotation.Nullable;

/**
 * 悠然服务器魔改：冷藏室和下面的冷冻柜算一台电器，电存在冷冻柜里；冷藏室的背面也能接电（转给冷冻柜）。
 */
public class TileEntityFridge extends TileEntityFurniture implements ISidedInventory
{
    private static final int[] slots = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

    public TileEntityFridge()
    {
        super("fridge", 15);
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        this.fillWithLoot(playerIn);
        return new ContainerFridge(playerInventory, this);
    }

    @Override
    public int[] getSlotsForFace(EnumFacing side)
    {
        return slots;
    }

    @Override
    public boolean canInsertItem(int index, ItemStack stack, EnumFacing direction)
    {
        return stack.getItem() instanceof ItemFood && !isLocked();
    }

    @Override
    public boolean canExtractItem(int index, ItemStack stack, EnumFacing direction)
    {
        return !isLocked();
    }

    @Nullable
    private TileEntityFreezer freezerBelow()
    {
        if(world == null)
            return null;
        TileEntity below = world.getTileEntity(pos.down());
        return below instanceof TileEntityFreezer ? (TileEntityFreezer) below : null;
    }

    private boolean isPowerFace(@Nullable EnumFacing facing)
    {
        return facing != null && facing == ApplianceFaces.back(this) && freezerBelow() != null;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing)
    {
        if(capability == CapabilityEnergy.ENERGY)
            return isPowerFace(facing);
        return super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing)
    {
        if(capability == CapabilityEnergy.ENERGY)
        {
            TileEntityFreezer freezer = isPowerFace(facing) ? freezerBelow() : null;
            return freezer != null ? CapabilityEnergy.ENERGY.cast(freezer.getAppliance().port()) : null;
        }
        return super.getCapability(capability, facing);
    }
}
