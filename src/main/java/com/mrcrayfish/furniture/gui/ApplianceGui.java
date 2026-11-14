package com.mrcrayfish.furniture.gui;

import com.mrcrayfish.furniture.power.ApplianceEnergy;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Slot;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 电器界面上的卡槽与电量条（悠然服务器魔改）。原界面贴图里没有这一格，按原版格子的样子画出来；
 * 右边一根竖条是缓存电量。鼠标指着电量条（或空卡槽）时显示说明。
 */
public final class ApplianceGui
{
    private static final int DARK = 0xFF373737;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int BAR = 0xFFE03C28;
    private static final int BAR_OFF = 0xFF555555;

    private ApplianceGui() {}

    /**
     * 画卡槽（slot 为 null 时只画电量条）。x、y 是卡槽左上角（物品位置），相对界面。
     */
    public static void draw(int guiLeft, int guiTop, int x, int y, @Nullable Slot slot, ApplianceEnergy energy)
    {
        int left = guiLeft + x - 1;
        int top = guiTop + y - 1;
        if(slot != null)
        {
            Gui.drawRect(left, top, left + 18, top + 18, DARK);
            Gui.drawRect(left + 1, top + 1, left + 18, top + 18, LIGHT);
            Gui.drawRect(left + 1, top + 1, left + 17, top + 17, SLOT);
        }
        int bar = left + 19;
        Gui.drawRect(bar, top, bar + 5, top + 18, DARK);
        Gui.drawRect(bar + 1, top + 1, bar + 4, top + 17, BAR_OFF);
        int capacity = Math.max(1, energy.getCapacity());
        int height = (int) ((long) 16 * Math.min(energy.getStored(), capacity) / capacity);
        if(height > 0)
        {
            Gui.drawRect(bar + 1, top + 17 - height, bar + 4, top + 17, BAR);
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * 鼠标指着电量条、或空卡槽时的说明；不在范围里返回 null。mouseX、mouseY 是屏幕坐标。
     */
    @Nullable
    public static List<String> tooltip(int guiLeft, int guiTop, int x, int y, @Nullable Slot slot, ApplianceEnergy energy, int mouseX, int mouseY)
    {
        int left = guiLeft + x - 1;
        int top = guiTop + y - 1;
        boolean overBar = mouseX >= left + 19 && mouseX < left + 24 && mouseY >= top && mouseY < top + 18;
        boolean overEmptySlot = slot != null && !slot.getHasStack() && mouseX >= left && mouseX < left + 18 && mouseY >= top && mouseY < top + 18;
        if(!overBar && !overEmptySlot)
            return null;
        List<String> lines = new ArrayList<>();
        lines.add(I18n.format("cfm.appliance.buffer", energy.getStored(), energy.getCapacity()));
        lines.add((energy.isRunning() ? TextFormatting.GREEN : TextFormatting.GRAY) + I18n.format(energy.isRunning() ? "cfm.appliance.running" : "cfm.appliance.idle"));
        if(slot != null)
        {
            lines.add(TextFormatting.GRAY + I18n.format(slot.getHasStack() ? "cfm.appliance.card_in" : "cfm.appliance.card_hint"));
        }
        return lines;
    }
}
