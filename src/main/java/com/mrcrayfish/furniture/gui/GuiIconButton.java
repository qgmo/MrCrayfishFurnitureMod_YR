package com.mrcrayfish.furniture.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

/**
 * 带一个小图标的按钮（悠然服务器魔改，服主 11-18：频道格右边的“搜索”前面有放大镜）。高度可以小于原版按钮的 20：
 * 自己画一个平的按钮（边框、底色，鼠标移上去变亮），图标在文字前面；文字放不下时只画图标。
 */
public class GuiIconButton extends GuiButton
{
    public enum Icon
    {
        MAGNIFIER, NONE
    }

    private final Icon icon;
    /** 被选中（例如搜索类型的切换） */
    public boolean selected;

    public GuiIconButton(int id, int x, int y, int width, int height, Icon icon, String text)
    {
        super(id, x, y, width, height, text);
        this.icon = icon;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks)
    {
        if(!visible)
            return;
        hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        int border = enabled ? (hovered ? 0xFFFFFFFF : 0xFF000000) : 0xFF303030;
        int fill = !enabled ? 0xFF404040 : selected ? 0xFF4A6FA5 : hovered ? 0xFF8A8A8A : 0xFF6E6E6E;
        Gui.drawRect(x, y, x + width, y + height, border);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + 2, 0x40FFFFFF);
        FontRenderer font = mc.fontRenderer;
        int color = enabled ? 0xFFFFFF : 0xA0A0A0;
        int iconW = icon == Icon.NONE ? 0 : TvIcons.SIZE + 2;
        int textW = font.getStringWidth(displayString);
        boolean showText = iconW + textW <= width - 4;
        int total = iconW + (showText ? textW : 0) - (showText || iconW == 0 ? 0 : 2);
        int cx = x + (width - total) / 2;
        int iconY = y + (height - TvIcons.SIZE) / 2;
        if(icon == Icon.MAGNIFIER)
            TvIcons.magnifier(cx, iconY, 1, 0xFF000000 | color);
        if(showText)
            font.drawStringWithShadow(displayString, cx + iconW, y + (height - 8) / 2, color);
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    /** 文字放不下（只画了图标）时，鼠标悬停要显示的提示 */
    public boolean textHidden(FontRenderer font)
    {
        int iconW = icon == Icon.NONE ? 0 : TvIcons.SIZE + 2;
        return iconW + font.getStringWidth(displayString) > width - 4;
    }
}
