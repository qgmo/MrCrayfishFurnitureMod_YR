package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.slots.SlotMicrowave;
import com.mrcrayfish.furniture.tileentity.TileEntityMicrowave;
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

public class ContainerMicrowave extends Container
{
    private IInventory microwaveInventory;
    /** 玩家背包槽位结束处（不含）；之后是能量单元卡槽（悠然服务器魔改） */
    private int end;
    private int cardIndex = -1;
    @Nullable
    private SlotPowerCard cardSlot;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    /** 卡槽在界面上的位置 */
    public static final int CARD_X = 156, CARD_Y = 44;

    public ContainerMicrowave(IInventory playerInventory, TileEntityMicrowave microwaveInventory)
    {
        this.microwaveInventory = microwaveInventory;

        this.addSlotToContainer(new SlotMicrowave(microwaveInventory, 0, 65, 43));

        for(int i = 0; i < 3; i++)
        {
            for(int j = 0; j < 9; ++j)
            {
                this.addSlotToContainer(new Slot(playerInventory, j + i * 9 + 9, j * 18 + 8, i * 18 + 92));
            }
        }

        for(int i = 0; i < 9; i++)
        {
            this.addSlotToContainer(new Slot(playerInventory, i, i * 18 + 8, 150));
        }

        this.end = this.inventorySlots.size();
        if(microwaveInventory instanceof IAppliance)
        {
            this.cardSlot = ApplianceContainers.cardSlot((IAppliance) microwaveInventory, microwaveInventory, CARD_X, CARD_Y);
            if(this.cardSlot != null)
            {
                this.cardIndex = this.inventorySlots.size();
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
        if(microwaveInventory instanceof IAppliance)
            sync.addListener(this, listener, ((IAppliance) microwaveInventory).getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        if(microwaveInventory instanceof IAppliance)
            sync.detect(this, this.listeners, ((IAppliance) microwaveInventory).getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(microwaveInventory instanceof IAppliance && ApplianceContainers.Sync.update(id, data, ((IAppliance) microwaveInventory).getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player)
    {
        return this.microwaveInventory.isUsableByPlayer(player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotNum)
    {
        ItemStack card = ApplianceContainers.transfer(this, slotNum, cardIndex, 1, end, this::mergeItemStack);
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

            RecipeData data = RecipeAPI.getMicrowaveRecipeFromIngredients(item);

            if(slotNum < 1)
            {
                if(!this.mergeItemStack(item, 1, end, false))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(data != null)
            {
                if(!this.mergeItemStack(item, 0, 1, false))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(slotNum > 0 && slotNum < 27)
            {
                if(!this.mergeItemStack(item, 27, end, true))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(!this.mergeItemStack(item, 1, 27, false))
            {
                return ItemStack.EMPTY;
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
        this.microwaveInventory.closeInventory(player);
    }

    public IInventory getMicrowaveInventory()
    {
        return microwaveInventory;
    }
}
