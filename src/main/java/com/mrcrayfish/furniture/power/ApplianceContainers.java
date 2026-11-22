package com.mrcrayfish.furniture.power;

import com.mrcrayfish.furniture.compat.RfToolsCards;
import com.mrcrayfish.furniture.gui.inventory.InventoryApplianceCard;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 电器界面的公共部分（悠然服务器魔改）：能量单元卡槽、Shift 点击、把缓存电量同步给界面。
 * 卡槽只在装了 RFTools 时加（服务端、客户端模组一致，两边槽位对得上），加在所有原有槽位之后，原有槽位编号不变。
 */
public final class ApplianceContainers
{
    /** 界面属性编号：缓存电量、在不在工作（避开原有的 0..n） */
    public static final int PROPERTY_STORED = 90;
    public static final int PROPERTY_RUNNING = 91;

    public interface Merger
    {
        boolean merge(ItemStack stack, int start, int end, boolean reverse);
    }

    private ApplianceContainers() {}

    /** 要不要加卡槽（装了 RFTools 才加） */
    @Nullable
    public static SlotPowerCard cardSlot(IAppliance appliance, IInventory owner, int x, int y)
    {
        if(!RfToolsCards.isLoaded())
            return null;
        return new SlotPowerCard(new InventoryApplianceCard(appliance.getAppliance(), owner), x, y);
    }

    /** 电器本身不是库存时（电视）的卡槽 */
    @Nullable
    public static SlotPowerCard cardSlot(InventoryApplianceCard inventory, int x, int y)
    {
        if(!RfToolsCards.isLoaded())
            return null;
        return new SlotPowerCard(inventory, x, y);
    }

    /**
     * 卡槽相关的 Shift 点击。返回 null 表示不归卡槽管，交给界面原来的处理。
     *
     * @param player      点的玩家
     * @param cardIndex   卡槽的编号（没有卡槽时 -1）
     * @param playerStart 玩家背包槽位的起始编号（含）
     * @param playerEnd   玩家背包槽位的结束编号（不含）
     */
    @Nullable
    public static ItemStack transfer(Container container, EntityPlayer player, int index, int cardIndex, int playerStart, int playerEnd, Merger merger)
    {
        if(cardIndex < 0 || index < 0 || index >= container.inventorySlots.size())
            return null;
        // 只许创造模式的维度里，卡槽的放、拿都不认（界面点击在 PartRules.slotClick 里已经拦过，这里是再保险）
        if(!PartRules.canChange(player))
        {
            boolean toCard = index == cardIndex || (RfToolsCards.isCard(container.inventorySlots.get(index).getStack()) && index >= playerStart && index < playerEnd);
            if(toCard)
                return ItemStack.EMPTY;
        }
        Slot slot = container.inventorySlots.get(index);
        if(slot == null || !slot.getHasStack())
            return null;
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();
        if(index == cardIndex)
        {
            if(!merger.merge(stack, playerStart, playerEnd, true))
                return ItemStack.EMPTY;
        }
        else if(RfToolsCards.isCard(stack) && index >= playerStart && index < playerEnd)
        {
            Slot card = container.inventorySlots.get(cardIndex);
            if(card.getHasStack())
                return ItemStack.EMPTY;
            card.putStack(stack.splitStack(1));
        }
        else
        {
            return null;
        }
        if(stack.isEmpty())
            slot.putStack(ItemStack.EMPTY);
        else
            slot.onSlotChanged();
        return copy;
    }

    /** 服务端：缓存电量、工作状态变了就发给开着界面的玩家 */
    public static final class Sync
    {
        private int lastStored = -1;
        private int lastRunning = -1;

        public void detect(Container container, List<IContainerListener> listeners, ApplianceEnergy energy)
        {
            int stored = energy.getStored();
            int running = energy.isRunning() ? 1 : 0;
            for(IContainerListener listener : listeners)
            {
                if(stored != lastStored)
                    listener.sendWindowProperty(container, PROPERTY_STORED, Math.min(stored, Short.MAX_VALUE));
                if(running != lastRunning)
                    listener.sendWindowProperty(container, PROPERTY_RUNNING, running);
            }
            lastStored = stored;
            lastRunning = running;
        }

        public void addListener(Container container, IContainerListener listener, ApplianceEnergy energy)
        {
            listener.sendWindowProperty(container, PROPERTY_STORED, Math.min(energy.getStored(), Short.MAX_VALUE));
            listener.sendWindowProperty(container, PROPERTY_RUNNING, energy.isRunning() ? 1 : 0);
        }

        /** 客户端收到界面属性；是电器的属性返回 true */
        public static boolean update(int id, int value, ApplianceEnergy energy)
        {
            if(id == PROPERTY_STORED)
            {
                energy.setClientStored(value);
                return true;
            }
            if(id == PROPERTY_RUNNING)
            {
                energy.setClientRunning(value != 0);
                return true;
            }
            return false;
        }
    }
}
