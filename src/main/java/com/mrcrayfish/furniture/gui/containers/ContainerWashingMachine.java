package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.slots.SlotArmour;
import com.mrcrayfish.furniture.gui.slots.SlotSoapyWater;
import com.mrcrayfish.furniture.init.FurnitureItems;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import com.mrcrayfish.furniture.power.ApplianceContainers;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import net.minecraft.inventory.IContainerListener;
import javax.annotation.Nullable;

public class ContainerWashingMachine extends Container
{
    private IInventory washingMachineInventory;
    /** 玩家背包槽位结束处（不含）；之后是能量单元卡槽（悠然服务器魔改） */
    private int end;
    private int cardIndex = -1;
    @Nullable
    private SlotPowerCard cardSlot;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    /** 卡槽在界面上的位置 */
    public static final int CARD_X = 34, CARD_Y = 102;

    public ContainerWashingMachine(IInventory playerInventory, IInventory washingMachineInventory)
    {
        this.washingMachineInventory = washingMachineInventory;

        this.addSlotToContainer(new SlotArmour(washingMachineInventory, 0, 80, 44, EntityEquipmentSlot.HEAD));
        this.addSlotToContainer(new SlotArmour(washingMachineInventory, 1, 64, 60, EntityEquipmentSlot.CHEST));
        this.addSlotToContainer(new SlotArmour(washingMachineInventory, 2, 96, 60, EntityEquipmentSlot.LEGS));
        this.addSlotToContainer(new SlotArmour(washingMachineInventory, 3, 80, 76, EntityEquipmentSlot.FEET));
        this.addSlotToContainer(new SlotSoapyWater(washingMachineInventory, 4, 125, 7));

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
        if(washingMachineInventory instanceof IAppliance)
        {
            this.cardSlot = ApplianceContainers.cardSlot((IAppliance) washingMachineInventory, washingMachineInventory, CARD_X, CARD_Y);
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
        if(washingMachineInventory instanceof IAppliance)
            sync.addListener(this, listener, ((IAppliance) washingMachineInventory).getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        if(washingMachineInventory instanceof IAppliance)
            sync.detect(this, this.listeners, ((IAppliance) washingMachineInventory).getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(washingMachineInventory instanceof IAppliance && ApplianceContainers.Sync.update(id, data, ((IAppliance) washingMachineInventory).getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player)
    {
        return this.washingMachineInventory.isUsableByPlayer(player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotNum)
    {
        ItemStack card = ApplianceContainers.transfer(this, slotNum, cardIndex, 5, end, this::mergeItemStack);
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

            if(slotNum < 5)
            {
                if(!this.mergeItemStack(item, 5, end, false))
                {
                    return ItemStack.EMPTY;
                }
            }
            else if(slotNum > 4)
            {
                RecipeData data = RecipeAPI.getWashingMachineRecipeFromInput(item);
                if(data != null && (itemCopy.getItem() == Item.getItemFromBlock(Blocks.PUMPKIN) || itemCopy.getItem() == Items.SKULL || itemCopy.getItem().getEquipmentSlot(itemCopy) != null || (itemCopy.getItem() instanceof ItemArmor && ((ItemArmor) itemCopy.getItem()).armorType != null) || EntityLiving.getSlotForItemStack(itemCopy) != EntityEquipmentSlot.HEAD))
                {
                    if(!this.mergeItemStack(item, 0, 4, true))
                    {
                        return ItemStack.EMPTY;
                    }
                    else if(slotNum > 4 && slotNum < end - 9)
                    {
                        if(!this.mergeItemStack(item, end - 9, end, false))
                        {
                            return ItemStack.EMPTY;
                        }
                    }
                    else if(slotNum >= end - 9 && slotNum < end)
                    {
                        if(!this.mergeItemStack(item, 5, end - 9, false))
                        {
                            return ItemStack.EMPTY;
                        }
                    }
                }
                else if(item.getItem() == FurnitureItems.SOAPY_WATER | item.getItem() == FurnitureItems.SUPER_SOAPY_WATER)
                {
                    if(!this.mergeItemStack(item, 4, 5, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(slotNum > 4 && slotNum < end - 9)
                {
                    if(!this.mergeItemStack(item, end - 9, end, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
                else if(slotNum >= end - 9 && slotNum < end)
                {
                    if(!this.mergeItemStack(item, 5, end - 9, false))
                    {
                        return ItemStack.EMPTY;
                    }
                }
            }
            else if(!this.mergeItemStack(item, 0, 9, false))
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
        washingMachineInventory.closeInventory(player);
    }

    public IInventory getWashingMachineInventory()
    {
        return washingMachineInventory;
    }
}
