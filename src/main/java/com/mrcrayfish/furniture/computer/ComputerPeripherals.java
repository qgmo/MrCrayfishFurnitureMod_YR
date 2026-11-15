package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.DeviceCapability;
import moe.qingu.youran.api.network.location.LocatorModule;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.EnumSet;
import java.util.Set;

/**
 * 电脑的外设槽（悠然服务器魔改，服主 11-15）：插在电脑上的外设物品，现在只有一格，放 YouranMod 的定位模块。
 * 外设决定设备在 YouranMod 设备表里声明哪些能力（{@link #capabilities()}）；“装没装某种外设”的判断只在这里。
 * 外设物品没有状态（定位模块可以堆叠，槽里只放一个）。存档在方块实体 NBT 的 {@value #TAG} 里。
 * 以后 YouranMod 的统一外设接口（{@code api.peripheral}）合进来后，{@link #accepts}、{@link #capabilities} 改成问外设接口。
 */
public final class ComputerPeripherals
{
    public static final String TAG = "Peripherals";
    /** 外设槽的格数 */
    public static final int SLOTS = 1;

    private final ItemStack[] slots = new ItemStack[SLOTS];
    private final Runnable changed;

    public ComputerPeripherals(Runnable changed)
    {
        this.changed = changed;
        for(int i = 0; i < SLOTS; i++)
            slots[i] = ItemStack.EMPTY;
    }

    /** 这一格能不能放这个物品（是认得的外设） */
    public static boolean accepts(int slot, ItemStack stack)
    {
        return slot >= 0 && slot < SLOTS && LocatorModule.is(stack);
    }

    /** 是不是认得的外设（Shift 点击时找格子用） */
    public static boolean isPeripheral(ItemStack stack)
    {
        return LocatorModule.is(stack);
    }

    public ItemStack get(int slot)
    {
        return slot >= 0 && slot < SLOTS ? slots[slot] : ItemStack.EMPTY;
    }

    /** 放（或清空），返回原来的 */
    public ItemStack set(int slot, ItemStack stack)
    {
        if(slot < 0 || slot >= SLOTS)
            return ItemStack.EMPTY;
        ItemStack old = slots[slot];
        slots[slot] = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
        changed.run();
        return old;
    }

    public boolean isEmpty()
    {
        for(ItemStack s : slots)
        {
            if(!s.isEmpty())
                return false;
        }
        return true;
    }

    /** 装着的外设给设备带来的能力 */
    public Set<DeviceCapability> capabilities()
    {
        EnumSet<DeviceCapability> set = EnumSet.noneOf(DeviceCapability.class);
        for(ItemStack s : slots)
        {
            if(LocatorModule.is(s))
                set.add(DeviceCapability.LOCATION);
        }
        return set;
    }

    public boolean hasLocator()
    {
        return capabilities().contains(DeviceCapability.LOCATION);
    }

    public void writeToNBT(NBTTagCompound tag)
    {
        NBTTagList list = new NBTTagList();
        for(int i = 0; i < SLOTS; i++)
        {
            if(!slots[i].isEmpty())
            {
                NBTTagCompound t = slots[i].writeToNBT(new NBTTagCompound());
                t.setByte("Slot", (byte) i);
                list.appendTag(t);
            }
        }
        if(list.tagCount() > 0)
            tag.setTag(TAG, list);
        else
            tag.removeTag(TAG);
    }

    public void readFromNBT(NBTTagCompound tag)
    {
        for(int i = 0; i < SLOTS; i++)
            slots[i] = ItemStack.EMPTY;
        NBTTagList list = tag.getTagList(TAG, Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < list.tagCount(); i++)
        {
            NBTTagCompound t = list.getCompoundTagAt(i);
            int slot = t.getByte("Slot");
            if(slot >= 0 && slot < SLOTS)
                slots[slot] = new ItemStack(t);
        }
    }
}
