package com.mrcrayfish.furniture.gui;

import com.mrcrayfish.furniture.gui.containers.ContainerRemoteBattery;
import com.mrcrayfish.furniture.gui.inventory.InventoryRemoteBattery;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * 电视遥控器的电池仓（悠然服务器魔改）。借用原版一行箱子的贴图，把这一行除中间那格以外的格子盖掉。
 */
public class GuiRemoteBattery extends GuiContainer
{
    private static final ResourceLocation CHEST = new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int BACKGROUND = 0xFFC6C6C6;

    public GuiRemoteBattery(InventoryPlayer playerInventory, InventoryRemoteBattery batteryInventory)
    {
        super(new ContainerRemoteBattery(playerInventory, batteryInventory));
        this.ySize = 114 + 18;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.renderHoveredToolTip(mouseX, mouseY);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
    {
        this.fontRenderer.drawString(I18n.format("cfm.tv_remote.battery_compartment"), 8, 6, 4210752);
        this.fontRenderer.drawString(I18n.format("container.inventory"), 8, this.ySize - 96 + 2, 4210752);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
    {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.getTextureManager().bindTexture(CHEST);
        int x = (this.width - this.xSize) / 2;
        int y = (this.height - this.ySize) / 2;
        this.drawTexturedModalRect(x, y, 0, 0, this.xSize, 18 + 17);
        this.drawTexturedModalRect(x, y + 18 + 17, 0, 126, this.xSize, 96);
        // 只留中间一格
        drawRect(x + 7, y + 17, x + 79, y + 35, BACKGROUND);
        drawRect(x + 97, y + 17, x + 169, y + 35, BACKGROUND);
    }
}
