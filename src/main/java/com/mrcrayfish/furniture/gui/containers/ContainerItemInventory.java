package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.gui.inventory.ItemInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

/**
 * Author: MrCrayfish
 */
public abstract class ContainerItemInventory extends Container
{
    public abstract ItemInventory getItemInventory();

    @Override
    public boolean canInteractWith(EntityPlayer player)
    {
        return this.getItemInventory().isUsableByPlayer(player);
    }

    /**
     * 服务端在玩家主手不再拿着这个物品时拒绝一切点击（悠然服务器魔改）；界面会在下一 tick 因
     * canInteractWith 为 false 被关掉，这里堵住关掉前同一 tick 内的点击。
     */
    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player)
    {
        if(!player.world.isRemote && !this.canInteractWith(player))
        {
            return ItemStack.EMPTY;
        }
        return super.slotClick(slotId, dragType, clickType, player);
    }
}
