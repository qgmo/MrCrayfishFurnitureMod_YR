package com.mrcrayfish.furniture.gui.slots;

import com.mrcrayfish.furniture.compat.RfToolsCards;
import com.mrcrayfish.furniture.gui.inventory.InventoryApplianceCard;
import com.mrcrayfish.furniture.power.PartRules;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 电器界面里放 RFTools 能量单元卡的格子（悠然服务器魔改）
 */
public class SlotPowerCard extends Slot implements PartSlot
{
    private final InventoryApplianceCard cardInventory;

    public SlotPowerCard(InventoryApplianceCard inventory, int xPosition, int yPosition)
    {
        super(inventory, 0, xPosition, yPosition);
        this.cardInventory = inventory;
    }

    public InventoryApplianceCard getCardInventory()
    {
        return cardInventory;
    }

    @Override
    public boolean isItemValid(ItemStack stack)
    {
        return RfToolsCards.isCard(stack);
    }

    /** 创造模式限制的维度里，别的玩家拿不走（界面点击另有统一的拦截，见 {@link PartRules#slotClick}） */
    @Override
    public boolean canTakeStack(EntityPlayer player)
    {
        return PartRules.canChange(player) && super.canTakeStack(player);
    }

    @Override
    public int getSlotStackLimit()
    {
        return 1;
    }
}
