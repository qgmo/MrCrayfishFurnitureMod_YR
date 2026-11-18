package com.mrcrayfish.furniture.gui;

import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * 频道设置界面的搜索（悠然服务器魔改，服主 11-18）：单独的界面（普通 GuiScreen，不是 GuiContainer），
 * 所以 JEI 等给容器界面画的物品列表不会盖在上面。内容由 {@link GuiTVSearchPanel} 画（按窗口大小排列、滚动、加载下一页）。
 * Esc、“返回”、点一个结果都回到打开它的那个设置界面实例（服务端的 Container 一直开着，见 {@link TvSearchLink}）。
 * 输入的字与结果放在 {@link com.mrcrayfish.furniture.client.tv.TvSearchState}，同一次游戏内一直在。
 */
public class GuiTVSearch extends GuiScreen
{
    private final GuiTVSettings settings;
    private final GuiTVSearchPanel panel;
    private boolean returning;

    GuiTVSearch(GuiTVSettings settings, int channelNumber)
    {
        this.settings = settings;
        this.panel = new GuiTVSearchPanel(net.minecraft.client.Minecraft.getMinecraft(), new GuiTVSearchPanel.Host()
        {
            @Override
            public void onPick(String channel)
            {
                settings.fillFromSearch(channel);
                back();
            }

            @Override
            public void onClose()
            {
                back();
            }
        }, channelNumber);
    }

    /** 回到设置界面（同一个实例：填了一半的内容、等着的保存结果都在） */
    private void back()
    {
        returning = true;
        mc.displayGuiScreen(settings);
    }

    @Override
    public void initGui()
    {
        Keyboard.enableRepeatEvents(true);
        panel.init(width, height);
    }

    @Override
    public void updateScreen()
    {
        panel.update();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException
    {
        // Esc 也交给搜索（回到设置界面，不是关掉全部界面）
        panel.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException
    {
        panel.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException
    {
        super.handleMouseInput();
        panel.scroll(Mouse.getEventDWheel());
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        drawDefaultBackground();
        panel.draw(mouseX, mouseY, partialTicks);
    }

    @Override
    public void onGuiClosed()
    {
        Keyboard.enableRepeatEvents(false);
        if(!returning)
        {
            // 服务端关了容器（电视被拆、走远）、断线：不回设置界面；填的内容早在打开搜索时存进草稿了
            settings.searchClosedElsewhere();
        }
    }

    @Override
    public boolean doesGuiPauseGame()
    {
        return false;
    }
}
