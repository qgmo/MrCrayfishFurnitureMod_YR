package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.gui.containers.ContainerCrate;
import com.mrcrayfish.furniture.util.StackLimits;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;

public class TileEntityCrate extends TileEntityFurniture
{
    public boolean sealed = false;

    public TileEntityCrate()
    {
        super("crate", 16);
    }

    /**
     * Seals the crate so the player can't open unless they break it with a crow bar.
     */
    public void seal()
    {
        if(!sealed)
        {
            sealed = true;
            TileEntityUtil.markBlockForUpdate(world, pos);
        }
    }

    /**
     * 封好的板条箱放下时从物品数据读回库存（BlockCrate#onBlockPlacedBy），数据由物品带来，
     * 这里兜住异常并夹取每格数量（悠然服务器魔改）。超出 16 格的槽位原来就由
     * ItemStackHelper.loadAllItems 忽略。
     */
    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        try
        {
            super.readFromNBT(tagCompound);
        }
        catch(RuntimeException e)
        {
            MrCrayfishFurnitureMod.logger().warn("Ignored invalid crate inventory data: {}", e.toString());
            this.inventory = NonNullList.withSize(this.getSizeInventory(), ItemStack.EMPTY);
        }
        StackLimits.clampAll(this.inventory, this.getInventoryStackLimit());
        this.sealed = tagCompound.getBoolean("Sealed");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setBoolean("Sealed", this.sealed);
        return tagCompound;
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        this.fillWithLoot(playerIn);
        return new ContainerCrate(playerInventory, this);
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player)
    {
        return super.isUsableByPlayer(player) && !sealed;
    }
}
