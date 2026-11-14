package com.mrcrayfish.furniture.compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 合成时读合成格里的电池（悠然服务器魔改）。电栅栏、电视遥控器的配方在整合包的 CraftTweaker 脚本里（要一节电池），
 * 合成出来的东西要带上那节电池的电量（电栅栏还有容量），模组在物品的 {@code onCreated} 里调这里取电池。
 * <p>
 * 为什么放在 {@code onCreated}：点取、Shift 点取（含连续合成多个）都会在合成格被扣掉之前、产物进背包之前对产物本身调用它，
 * 改产物的 NBT 就是改玩家拿到的那一份。Forge 的 {@code ItemCraftedEvent} 在 Shift 点取时拿到的是剩下的空物品，改了不生效。
 * <p>
 * 合成格按“玩家当前打开的界面里，属于 {@link InventoryCrafting} 的格子”找：原版工作台、背包里的 2×2，以及用原版合成格的
 * 其他模组的工作台都适用。找不到时（如自动合成机）返回空，产物按“没有电”处理。
 */
public final class CraftingBatteries
{
    private CraftingBatteries() {}

    /** 合成格里的第一节电池（复制一份、数量 1）；没有则返回空 */
    public static ItemStack findBattery(EntityPlayer player)
    {
        if(player == null)
            return ItemStack.EMPTY;
        Container container = player.openContainer;
        if(container == null)
            return ItemStack.EMPTY;
        for(Slot slot : container.inventorySlots)
        {
            if(slot.inventory instanceof InventoryCrafting)
            {
                ItemStack stack = slot.getStack();
                if(!stack.isEmpty())
                {
                    ItemStack one = ItemBatteries.single(stack);
                    if(ItemBatteries.isBattery(one))
                        return one;
                }
            }
        }
        return ItemStack.EMPTY;
    }
}
