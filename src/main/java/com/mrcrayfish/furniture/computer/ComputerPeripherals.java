package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.peripheral.Peripherals;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 电脑的外设槽（悠然服务器魔改，服主 11-15）：插在电脑上的外设物品，两格（服主 11-27 起身份模块和定位模块并列，MineBay 结算两样都要）。
 * “装没装某种外设”的判断只在这里。存档在方块实体 NBT 的 {@value #TAG} 里。
 * 走 YouranMod 的统一外设接口（{@code api.peripheral}）：什么外设都能插（服主 11-16），但只有定位模块（能力 {@code location}）、
 * 身份模块（能力 {@code identity}）起作用；汉卡、软驱、烧录器等插着没有效果（那个年代系统自带中文，用不着汉卡）。
 */
public final class ComputerPeripherals
{
    public static final String TAG = "Peripherals";
    /** 外设槽的格数 */
    public static final int SLOTS = 2;

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
        return slot >= 0 && slot < SLOTS && isPeripheral(stack);
    }

    /** 是不是外设（任何种类；Shift 点击时找格子用） */
    public static boolean isPeripheral(ItemStack stack)
    {
        return Peripherals.kind(stack) != null;
    }

    /** 装着的外设（空格不算） */
    public List<ItemStack> installed()
    {
        List<ItemStack> list = new ArrayList<>();
        for(ItemStack s : slots)
        {
            if(!s.isEmpty())
                list.add(s);
        }
        return list;
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

    /** 装了定位模块：能签位置证明 */
    public boolean hasLocator()
    {
        return Peripherals.hasCapability(Arrays.asList(slots), Peripherals.CAP_LOCATION);
    }

    /** 装了身份模块：能签操作者证明（一键登录） */
    public boolean hasIdentity()
    {
        return Peripherals.hasCapability(Arrays.asList(slots), Peripherals.CAP_IDENTITY);
    }

    /** 这个外设在家具电脑上起不起作用（定位模块、身份模块起作用；悬停提示用） */
    public static boolean isUseful(ItemStack stack)
    {
        java.util.List<ItemStack> one = java.util.Collections.singletonList(stack);
        return Peripherals.hasCapability(one, Peripherals.CAP_LOCATION) || Peripherals.hasCapability(one, Peripherals.CAP_IDENTITY);
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
