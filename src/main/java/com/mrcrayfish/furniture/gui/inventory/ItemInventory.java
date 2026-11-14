package com.mrcrayfish.furniture.gui.inventory;

import com.mrcrayfish.furniture.util.NBTHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * Author: MrCrayfish
 */
public class ItemInventory extends InventoryBasic
{
    private ItemStack stack;
    private boolean canInsertItems = true;
    private boolean loading;

    public ItemInventory(ItemStack stack, String title, int slotCount)
    {
        super(title, false, slotCount);
        this.stack = stack;
        this.loadInventory();
    }

    public ItemInventory setCanInsertItems(boolean canInsertItems)
    {
        this.canInsertItems = canInsertItems;
        return this;
    }

    private void loadInventory()
    {
        this.loading = true;
        try
        {
            NBTTagList itemList = NBTHelper.getCompoundTag(stack, this.getName()).getTagList("Items", Constants.NBT.TAG_COMPOUND);
            for(int i = 0; i < itemList.tagCount(); i++)
            {
                NBTTagCompound slotEntry = itemList.getCompoundTagAt(i);
                int j = slotEntry.getByte("Slot") & 0xff;
                if(j >= 0 && j < this.getSizeInventory())
                {
                    this.setInventorySlotContents(j, new ItemStack(slotEntry));
                }
            }
        }
        finally
        {
            this.loading = false;
        }
    }

    public boolean canInsertItems()
    {
        return canInsertItems;
    }

    /**
     * 界面只在玩家主手还拿着打开它的那一个物品时可用（悠然服务器魔改）。
     * 原来恒为 true：切换快捷栏、把物品丢出去或换到别的格子后，界面仍然开着、还能取物，
     * 关界面时内容又写到当时主手的物品上，原物品里的东西就多出一份。
     */
    public boolean isHeldBy(EntityPlayer player)
    {
        ItemStack held = player.getHeldItemMainhand();
        return held == this.stack && !held.isEmpty();
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player)
    {
        return this.isHeldBy(player);
    }

    /**
     * 每次内容变化都立即写回物品（悠然服务器魔改），物品在任何时刻被复制（丢出、拆分）
     * 带走的都是当前内容，不会留下旧内容的副本。
     */
    @Override
    public void markDirty()
    {
        super.markDirty();
        if(!this.loading)
        {
            this.saveToStack();
        }
    }

    /**
     * 把库存写进打开界面时的那个物品的 NBT。
     */
    public void saveToStack()
    {
        if(this.stack.isEmpty())
            return;
        NBTTagCompound tagCompound = new NBTTagCompound();
        NBTTagList itemList = new NBTTagList();
        for(int i = 0; i < this.getSizeInventory(); i++)
        {
            if(!this.getStackInSlot(i).isEmpty())
            {
                NBTTagCompound slotEntry = new NBTTagCompound();
                slotEntry.setByte("Slot", (byte) i);
                this.getStackInSlot(i).writeToNBT(slotEntry);
                itemList.appendTag(slotEntry);
            }
        }
        tagCompound.setTag("Items", itemList);
        NBTHelper.setCompoundTag(this.stack, this.getName(), tagCompound);
    }
}
