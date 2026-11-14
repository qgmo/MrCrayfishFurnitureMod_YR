package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.slots.SlotOven;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import com.mrcrayfish.furniture.power.ApplianceContainers;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import net.minecraft.inventory.IContainerListener;
import javax.annotation.Nullable;

public class ContainerOven extends Container
{
    private IInventory ovenInventory;
    /** 玩家背包槽位结束处（不含）；之后是能量单元卡槽（悠然服务器魔改） */
    private int end;
    private int cardIndex = -1;
    @Nullable
    private SlotPowerCard cardSlot;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    /** 卡槽在界面上的位置 */
    public static final int CARD_X = 33, CARD_Y = 104;

    public ContainerOven(IInventory playerInventory, IInventory ovenInventory)
    {
        this.ovenInventory = ovenInventory;
        for(int i = 0; i < 4; i++)
        {
            this.addSlotToContainer(new Slot(ovenInventory, i, i * 18 + 53, 31));
        }

        for(int i = 0; i < 4; i++)
        {
            this.addSlotToContainer(new SlotOven(ovenInventory, i + 4, i * 18 + 53, 73));
        }

        for(int i = 0; i < 3; i++)
        {
            for(int j = 0; j < 9; ++j)
            {
                this.addSlotToContainer(new Slot(playerInventory, j + i * 9 + 9, j * 18 + 8, i * 18 + 146));
            }
        }

        for(int i = 0; i < 9; i++)
        {
            this.addSlotToContainer(new Slot(playerInventory, i, i * 18 + 8, 204));
        }

        this.end = this.inventorySlots.size();
        if(ovenInventory instanceof IAppliance)
        {
            this.cardSlot = ApplianceContainers.cardSlot((IAppliance) ovenInventory, ovenInventory, CARD_X, CARD_Y);
            if(this.cardSlot != null)
            {
                this.cardIndex = end;
                this.addSlotToContainer(this.cardSlot);
            }
        }
    }

    @Nullable
    public SlotPowerCard getCardSlot()
    {
        return cardSlot;
    }

    @Override
    public void addListener(IContainerListener listener)
    {
        super.addListener(listener);
        if(ovenInventory instanceof IAppliance)
            sync.addListener(this, listener, ((IAppliance) ovenInventory).getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        if(ovenInventory instanceof IAppliance)
            sync.detect(this, this.listeners, ((IAppliance) ovenInventory).getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(ovenInventory instanceof IAppliance && ApplianceContainers.Sync.update(id, data, ((IAppliance) ovenInventory).getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player)
    {
        return this.ovenInventory.isUsableByPlayer(player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotNum)
    {
        ItemStack card = ApplianceContainers.transfer(this, slotNum, cardIndex, 8, end, this::mergeItemStack);
        if(card != null)
            return card;
        if(slotNum >= end)
            return ItemStack.EMPTY;
        ItemStack itemCopy = ItemStack.EMPTY;
        Slot slot = this.inventorySlots.get(slotNum);

        if(slot != null && slot.getHasStack())
        {
            ItemStack item = slot.getStack();
            itemCopy = item.copy();

            RecipeData data = RecipeAPI.getOvenRecipeFromInput(item);

            if(slotNum < 18)
            {
                if(!this.mergeItemStack(item, 18, end, true))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(data != null)
            {
                if(!this.mergeItemStack(item, 0, 9, false))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(slotNum >= 18 && slotNum < end - 9)
            {
                if(!this.mergeItemStack(item, end - 9, end, false))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(slotNum >= end - 9 && slotNum < end)
            {
                if(!this.mergeItemStack(item, 18, end - 9, false))
                {
                    return ItemStack.EMPTY;
                }
            }

            if(item.getCount() == 0)
            {
                slot.putStack(ItemStack.EMPTY);
            }
            else
            {
                slot.onSlotChanged();
            }
        }
        return itemCopy;
    }

    @Override
    public void onContainerClosed(EntityPlayer player)
    {
        super.onContainerClosed(player);
        this.ovenInventory.closeInventory(player);
    }
}
