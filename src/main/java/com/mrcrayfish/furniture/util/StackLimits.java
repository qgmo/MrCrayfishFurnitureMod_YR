package com.mrcrayfish.furniture.util;

import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

/**
 * 从物品数据读库存时的数量夹取（悠然服务器魔改）。
 */
public final class StackLimits
{
    private StackLimits() {}

    /**
     * 把数量夹到 [0, max]。
     */
    public static int clampCount(int count, int max)
    {
        if(count < 0)
            return 0;
        return Math.min(count, max);
    }

    /**
     * 每格的数量不超过物品自身的堆叠上限与库存上限中较小的那个。
     */
    public static void clampAll(NonNullList<ItemStack> stacks, int inventoryLimit)
    {
        for(int i = 0; i < stacks.size(); i++)
        {
            ItemStack stack = stacks.get(i);
            if(stack.isEmpty())
                continue;
            int count = clampCount(stack.getCount(), Math.min(stack.getMaxStackSize(), inventoryLimit));
            if(count != stack.getCount())
            {
                stack.setCount(count);
                if(stack.isEmpty())
                    stacks.set(i, ItemStack.EMPTY);
            }
        }
    }
}
