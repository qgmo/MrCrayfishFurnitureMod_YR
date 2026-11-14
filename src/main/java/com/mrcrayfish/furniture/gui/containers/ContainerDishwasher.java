package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.slots.SlotSoapyWater;
import com.mrcrayfish.furniture.gui.slots.SlotTool;
import com.mrcrayfish.furniture.init.FurnitureItems;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.*;
import com.mrcrayfish.furniture.power.ApplianceContainers;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import net.minecraft.inventory.IContainerListener;
import javax.annotation.Nullable;

public class ContainerDishwasher extends Container
{
    private IInventory dishwasherInventory;
    /** 玩家背包槽位结束处（不含）；之后是能量单元卡槽（悠然服务器魔改） */
    private int end;
    private int cardIndex = -1;
    @Nullable
    private SlotPowerCard cardSlot;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    /** 卡槽在界面上的位置（上半部左下的空处） */
    public static final int CARD_X = 37, CARD_Y = 105;

    public ContainerDishwasher(IInventory playerInventory, IInventory dishwasherInventory)
    {
        this.dishwasherInventory = dishwasherInventory;
        dishwasherInventory.openInventory(null);

        this.addSlotToContainer(new SlotTool(dishwasherInventory, 0, 56, 43, 0));
        this.addSlotToContainer(new SlotTool(dishwasherInventory, 1, 80, 43, 1));
        this.addSlotToContainer(new SlotTool(dishwasherInventory, 2, 104, 43, 2));
        this.addSlotToContainer(new SlotTool(dishwasherInventory, 3, 56, 74, 3));
        this.addSlotToContainer(new SlotTool(dishwasherInventory, 4, 80, 74, 4));
        this.addSlotToContainer(new SlotTool(dishwasherInventory, 5, 104, 74, 5));
        this.addSlotToContainer(new SlotSoapyWater(dishwasherInventory, 6, 125, 7));

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
        if(dishwasherInventory instanceof IAppliance)
        {
            this.cardSlot = ApplianceContainers.cardSlot((IAppliance) dishwasherInventory, dishwasherInventory, CARD_X, CARD_Y);
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
        if(dishwasherInventory instanceof IAppliance)
            sync.addListener(this, listener, ((IAppliance) dishwasherInventory).getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        if(dishwasherInventory instanceof IAppliance)
            sync.detect(this, this.listeners, ((IAppliance) dishwasherInventory).getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(dishwasherInventory instanceof IAppliance && ApplianceContainers.Sync.update(id, data, ((IAppliance) dishwasherInventory).getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player)
    {
        return this.dishwasherInventory.isUsableByPlayer(player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotNum)
    {
        ItemStack card = ApplianceContainers.transfer(this, slotNum, cardIndex, 7, end, this::mergeItemStack);
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

            if(slotNum <= 6)
            {
                if(!this.mergeItemStack(item, 7, end, false))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(slotNum > 6)
            {
                RecipeData data = RecipeAPI.getDishwasherRecipeFromInput(item);

                if(data != null)
                {
                    int corroSlot = toolToSlot(item);
                    if(!this.mergeItemStack(item, corroSlot, corroSlot + 1, true))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(item.getItem() == FurnitureItems.SOAPY_WATER | item.getItem() == FurnitureItems.SUPER_SOAPY_WATER)
                {
                    if(!this.mergeItemStack(item, 6, 7, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(slotNum > 6 && slotNum < end - 9)
                {
                    if(!this.mergeItemStack(item, end - 9, end, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(slotNum >= end - 9 && slotNum < end)
                {
                    if(!this.mergeItemStack(item, 7, end - 9, false))
                    {
                        return ItemStack.EMPTY;
                    }
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

    public static int toolToSlot(ItemStack stack)
    {
        Item item = stack.getItem();
        if(item instanceof ItemPickaxe)
        {
            return 0;
        }

        if(item instanceof ItemSpade)
        {
            return 1;
        }

        if(item instanceof ItemSword)
        {
            return 2;
        }

        if(item instanceof ItemAxe)
        {
            return 3;
        }

        if(item instanceof ItemHoe)
        {
            return 4;
        }

        return 5;
    }

    public IInventory getDishwasherInventory()
    {
        return dishwasherInventory;
    }
}
