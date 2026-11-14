package com.mrcrayfish.furniture.handler;

import com.mrcrayfish.furniture.Reference;
import com.mrcrayfish.furniture.gui.containers.ContainerItemInventory;
import net.minecraft.inventory.Container;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Author: MrCrayfish
 */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class ContainerEvents
{
    @SubscribeEvent
    public static void onContainerClosed(PlayerContainerEvent.Close event)
    {
        Container container = event.getContainer();
        if(container instanceof ContainerItemInventory)
        {
            // 写回打开界面时的那个物品（悠然服务器魔改）。原来写到关界面时主手的物品上，
            // 主手换了东西时原物品保留旧内容，取出的东西就多出一份。签名时（MessageSignItem）
            // 新物品复制的是原物品当时的 NBT，内容已随每次改动写回，不受影响。
            ((ContainerItemInventory) container).getItemInventory().saveToStack();
        }
    }
}
