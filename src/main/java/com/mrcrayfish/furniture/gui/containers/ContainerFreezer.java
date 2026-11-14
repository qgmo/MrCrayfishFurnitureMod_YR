package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.slots.SlotFreezer;
import com.mrcrayfish.furniture.tileentity.TileEntityFreezer;
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

public class ContainerFreezer extends Container
{
    private IInventory freezerInventory;
    /** 玩家背包槽位结束处（不含）；之后是能量单元卡槽（悠然服务器魔改） */
    private int end;
    private int cardIndex = -1;
    @Nullable
    private SlotPowerCard cardSlot;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    /** 卡槽在界面上的位置 */
    public static final int CARD_X = 84, CARD_Y = 6;

    public ContainerFreezer(IInventory playerInventory, IInventory freezerInventory)
    {
        this.freezerInventory = freezerInventory;
        freezerInventory.openInventory(null);

        this.addSlotToContainer(new Slot(freezerInventory, 0, 130, 6));
        this.addSlotToContainer(new Slot(freezerInventory, 1, 42, 32));
        this.addSlotToContainer(new SlotFreezer(freezerInventory, 2, 89, 32));

        for(int i = 0; i < 3; i++)
        {
            for(int j = 0; j < 9; ++j)
            {
                this.addSlotToContainer(new Slot(playerInventory, j + i * 9 + 9, j * 18 + 8, i * 18 + 77));
            }
        }

        for(int i = 0; i < 9; i++)
        {
            this.addSlotToContainer(new Slot(playerInventory, i, i * 18 + 8, 135));
        }

        this.end = this.inventorySlots.size();
        if(freezerInventory instanceof IAppliance)
        {
            this.cardSlot = ApplianceContainers.cardSlot((IAppliance) freezerInventory, freezerInventory, CARD_X, CARD_Y);
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
        if(freezerInventory instanceof IAppliance)
            sync.addListener(this, listener, ((IAppliance) freezerInventory).getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        if(freezerInventory instanceof IAppliance)
            sync.detect(this, this.listeners, ((IAppliance) freezerInventory).getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(freezerInventory instanceof IAppliance && ApplianceContainers.Sync.update(id, data, ((IAppliance) freezerInventory).getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer par1EntityPlayer)
    {
        return this.freezerInventory.isUsableByPlayer(par1EntityPlayer);
    }

    public ItemStack transferStackInSlot(EntityPlayer player, int slotNum)
    {
        ItemStack card = ApplianceContainers.transfer(this, slotNum, cardIndex, 3, end, this::mergeItemStack);
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

            if(slotNum == 2)
            {
                if(!this.mergeItemStack(item, 3, 39, true))
                {
                    return ItemStack.EMPTY;
                }

                slot.onSlotChange(item, itemCopy);
            }
            else if(slotNum != 1 && slotNum != 0)
            {
                RecipeData data = RecipeAPI.getFreezerRecipeFromInput(item);

                if(data != null)
                {
                    if(!this.mergeItemStack(item, 1, 2, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(TileEntityFreezer.isFuel(item))
                {
                    if(!this.mergeItemStack(item, 0, 1, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(slotNum >= 3 && slotNum < 30)
                {
                    if(!this.mergeItemStack(item, 30, 39, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(slotNum >= 30 && slotNum < 39 && !this.mergeItemStack(item, 3, 30, false))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(!this.mergeItemStack(item, 3, 39, false))
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

            if(item.getCount() == itemCopy.getCount())
            {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, item);
        }

        return itemCopy;
    }

    public IInventory getFreezerInventory()
    {
        return freezerInventory;
    }
}
