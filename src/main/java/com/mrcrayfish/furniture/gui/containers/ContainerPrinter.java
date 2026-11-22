package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.slots.SlotPrinter;
import com.mrcrayfish.furniture.gui.slots.SlotPrinterInput;
import com.mrcrayfish.furniture.tileentity.TileEntityPrinter;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import com.mrcrayfish.furniture.power.ApplianceContainers;
import com.mrcrayfish.furniture.power.PartRules;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import net.minecraft.inventory.IContainerListener;
import javax.annotation.Nullable;

public class ContainerPrinter extends Container
{
    private IInventory printerInventory;
    /** 玩家背包槽位结束处（不含）；之后是能量单元卡槽（悠然服务器魔改） */
    private int end;
    private int cardIndex = -1;
    @Nullable
    private SlotPowerCard cardSlot;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    /** 卡槽在界面上的位置 */
    public static final int CARD_X = 24, CARD_Y = 30;

    public ContainerPrinter(IInventory playerInventory, IInventory printerInventory)
    {
        this.printerInventory = printerInventory;
        this.addSlotToContainer(new SlotPrinterInput(printerInventory, 0, 80, 5));
        this.addSlotToContainer(new Slot(printerInventory, 1, 55, 30));
        this.addSlotToContainer(new SlotPrinter(printerInventory, 2, 80, 61));

        for(int i = 0; i < 3; i++)
        {
            for(int j = 0; j < 9; ++j)
            {
                this.addSlotToContainer(new Slot(playerInventory, j + i * 9 + 9, j * 18 + 8, i * 18 + 103));
            }
        }

        for(int i = 0; i < 9; i++)
        {
            this.addSlotToContainer(new Slot(playerInventory, i, i * 18 + 8, 161));
        }

        this.end = this.inventorySlots.size();
        if(printerInventory instanceof IAppliance)
        {
            this.cardSlot = ApplianceContainers.cardSlot((IAppliance) printerInventory, printerInventory, CARD_X, CARD_Y);
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
        if(printerInventory instanceof IAppliance)
            sync.addListener(this, listener, ((IAppliance) printerInventory).getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        if(printerInventory instanceof IAppliance)
            sync.detect(this, this.listeners, ((IAppliance) printerInventory).getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(printerInventory instanceof IAppliance && ApplianceContainers.Sync.update(id, data, ((IAppliance) printerInventory).getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer par1EntityPlayer)
    {
        return this.printerInventory.isUsableByPlayer(par1EntityPlayer);
    }

    /** 能量单元卡槽：只许创造模式的维度里，生存等玩家放不进、拿不走（{@link PartRules}） */
    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player)
    {
        return PartRules.slotClick(this, slotId, dragType, clickType, player, super::slotClick);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotNum)
    {
        ItemStack card = ApplianceContainers.transfer(this, player, slotNum, cardIndex, 3, end, this::mergeItemStack);
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
                RecipeData data = RecipeAPI.getPrinterRecipeFromInput(new ItemStack(itemCopy.getItem()));
                if(data != null)
                {
                    if(!this.mergeItemStack(item, 0, 1, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(TileEntityPrinter.isItemFuel(item))
                {
                    if(!this.mergeItemStack(item, 1, 2, false))
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
                return null;
            }

            slot.onTake(player, item);
        }

        return itemCopy;
    }

    @Override
    public void onContainerClosed(EntityPlayer player)
    {
        super.onContainerClosed(player);
        printerInventory.closeInventory(player);
    }
}
