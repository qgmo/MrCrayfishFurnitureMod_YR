package com.mrcrayfish.furniture.gui.inventory;

import com.mrcrayfish.furniture.compat.ItemBatteries;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * 电视遥控器的电池仓（一格，悠然服务器魔改）。电池存在遥控器物品的 NBT 里（{@value #NAME} / Items，格式同信封）。
 * 界面的安全规则沿用 {@link ItemInventory}：只有主手还拿着打开界面时的那个遥控器才能用，每次变化立即写回遥控器。
 */
public class InventoryRemoteBattery extends ItemInventory
{
    public static final String NAME = "RemoteBattery";

    public InventoryRemoteBattery(ItemStack remote)
    {
        super(remote, NAME, 1);
    }

    @Override
    public int getInventoryStackLimit()
    {
        return 1;
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack)
    {
        return isBatteryItem(stack);
    }

    public static boolean isBatteryItem(ItemStack stack)
    {
        return !stack.isEmpty() && ItemBatteries.isBattery(ItemBatteries.single(stack));
    }

    /** 读遥控器里的电池（不改遥控器）；没有则空 */
    public static ItemStack peek(ItemStack remote)
    {
        NBTTagCompound tag = remote.getTagCompound();
        if(tag == null || !tag.hasKey(NAME, Constants.NBT.TAG_COMPOUND))
            return ItemStack.EMPTY;
        NBTTagList items = tag.getCompoundTag(NAME).getTagList("Items", Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < items.tagCount(); i++)
        {
            NBTTagCompound entry = items.getCompoundTagAt(i);
            if((entry.getByte("Slot") & 0xff) == 0)
            {
                ItemStack battery = new ItemStack(entry);
                return battery.isEmpty() ? ItemStack.EMPTY : battery;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 把电池写进遥控器（空则清掉） */
    public static void store(ItemStack remote, ItemStack battery)
    {
        NBTTagCompound inv = new NBTTagCompound();
        NBTTagList items = new NBTTagList();
        if(!battery.isEmpty())
        {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setByte("Slot", (byte) 0);
            battery.writeToNBT(entry);
            items.appendTag(entry);
        }
        inv.setTag("Items", items);
        NBTTagCompound tag = remote.getTagCompound();
        if(tag == null)
        {
            tag = new NBTTagCompound();
            remote.setTagCompound(tag);
        }
        tag.setTag(NAME, inv);
    }
}
