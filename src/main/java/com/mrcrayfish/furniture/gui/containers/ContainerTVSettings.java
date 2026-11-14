package com.mrcrayfish.furniture.gui.containers;

import com.mrcrayfish.furniture.gui.inventory.InventoryApplianceCard;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import com.mrcrayfish.furniture.power.ApplianceContainers;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 电视的频道设置界面（悠然服务器魔改，服主 11-14）：只从大屏的“设置”按钮进。界面上除了三个频道、拉伸开关，
 * 还有能量单元卡槽与电量条，所以要有服务端的 Container（卡槽照烤箱等电器的做法，由服务端校验）。
 * 频道的保存走 {@link com.mrcrayfish.furniture.network.message.MessageTVChannels}，服务端只收开着这个界面的玩家发的。
 */
public class ContainerTVSettings extends Container
{
    /** 卡槽在界面上的位置；玩家背包从 {@link #INVENTORY_Y} 开始 */
    public static final int CARD_X = 8, CARD_Y = 94;
    public static final int INVENTORY_Y = 136;

    private final TileEntityTV tv;
    private final int end;
    private int cardIndex = -1;
    @Nullable
    private SlotPowerCard cardSlot;
    private final ApplianceContainers.Sync sync = new ApplianceContainers.Sync();

    public ContainerTVSettings(IInventory playerInventory, TileEntityTV tv)
    {
        this.tv = tv;
        for(int i = 0; i < 3; i++)
        {
            for(int j = 0; j < 9; ++j)
            {
                this.addSlotToContainer(new Slot(playerInventory, j + i * 9 + 9, j * 18 + 8, i * 18 + INVENTORY_Y));
            }
        }
        for(int i = 0; i < 9; i++)
        {
            this.addSlotToContainer(new Slot(playerInventory, i, i * 18 + 8, INVENTORY_Y + 58));
        }
        this.end = this.inventorySlots.size();
        InventoryApplianceCard card = new InventoryApplianceCard(tv.getAppliance(), tv::markDirty, tv::isUsableByPlayer);
        this.cardSlot = ApplianceContainers.cardSlot(card, CARD_X, CARD_Y);
        if(this.cardSlot != null)
        {
            this.cardIndex = this.inventorySlots.size();
            this.addSlotToContainer(this.cardSlot);
        }
    }

    public TileEntityTV getTv()
    {
        return tv;
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
        sync.addListener(this, listener, tv.getAppliance());
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        sync.detect(this, this.listeners, tv.getAppliance());
    }

    @Override
    public void updateProgressBar(int id, int data)
    {
        if(ApplianceContainers.Sync.update(id, data, tv.getAppliance()))
            return;
        super.updateProgressBar(id, data);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player)
    {
        return tv.isUsableByPlayer(player) && !tv.isDisabled();
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index)
    {
        ItemStack card = ApplianceContainers.transfer(this, index, cardIndex, 0, end, this::mergeItemStack);
        return card != null ? card : ItemStack.EMPTY;
    }
}
