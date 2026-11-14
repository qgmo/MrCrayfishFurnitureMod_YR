package com.mrcrayfish.furniture.gui;

import com.mrcrayfish.furniture.client.tv.RemoteControls;
import com.mrcrayfish.furniture.client.tv.TvScreenPainter;
import com.mrcrayfish.furniture.client.tv.TvVideoManager;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageTVOpenSettings;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * 大屏观看（悠然服务器魔改，服主 11-14）：右键电视屏幕正面进入，声音转成立体声；Esc 回到游戏，声音变回单声道。
 * 右下角“设置”按钮进频道设置（唯一入口）。看大屏时照常能按遥控器（按键由这里转给 {@link RemoteControls}，
 * 操作的就是这台电视；主手、副手的遥控器都算，数字键、滚轮照常换快捷栏）。大屏也显示弹幕和进度条。电视被拆、离得太远时自动关掉（这个界面没有要保存的输入）。
 */
public class GuiTVScreen extends GuiScreen
{
    private static final double MAX_DISTANCE = 12.0;

    private final BlockPos pos;
    private GuiButton settings;

    public GuiTVScreen(BlockPos pos)
    {
        this.pos = pos;
    }

    @Override
    public void initGui()
    {
        buttonList.clear();
        settings = new GuiButton(0, width - 54, height - 24, 50, 20, I18n.format("cfm.tv.screen.settings"));
        buttonList.add(settings);
        TvVideoManager.INSTANCE.setFullscreen(pos);
        RemoteControls.INSTANCE.cancel();
    }

    private TileEntityTV tv()
    {
        if(mc.world == null)
            return null;
        TileEntity te = mc.world.getTileEntity(pos);
        return te instanceof TileEntityTV ? (TileEntityTV) te : null;
    }

    @Override
    public void updateScreen()
    {
        TileEntityTV tv = tv();
        if(tv == null || mc.player == null || mc.player.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE * MAX_DISTANCE)
        {
            mc.displayGuiScreen(null);
            return;
        }
        settings.visible = !tv.isDisabled();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException
    {
        if(button == settings)
        {
            PacketHandler.INSTANCE.sendToServer(new MessageTVOpenSettings(pos));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        drawRect(0, 0, width, height, 0xFF000000);
        TileEntityTV tv = tv();
        if(tv != null)
        {
            if(tv.isScreenOn())
            {
                GlStateManager.disableDepth();
                GlStateManager.pushMatrix();
                // 画布的“往观看者方向”是 −z，界面里是 +z
                GlStateManager.scale(1F, 1F, -1F);
                TvScreenPainter.paint(tv, width, height, 1.5F, true);
                GlStateManager.popMatrix();
                GlStateManager.enableDepth();
                // 画屏幕时开了光照（世界里要），界面里关掉，不然按钮发暗
                GlStateManager.disableLighting();
            }
            else
            {
                String text = I18n.format(tv.isPowered() ? "cfm.tv.screen.no_power" : "cfm.tv.screen.off");
                drawCenteredString(fontRenderer, text, width / 2, height / 2 - 4, 0x808080);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        RemoteControls.INSTANCE.tickInGui();
    }

    @Override
    public void handleMouseInput() throws IOException
    {
        int button = Mouse.getEventButton();
        if(button >= 0)
        {
            boolean down = Mouse.getEventButtonState();
            boolean onButton = settings.visible && settings.isMouseOver();
            if(!(down && onButton) && RemoteControls.INSTANCE.handleButton(button - 100, down, pos, RemoteControls.sneakHeld(), true))
                return;
        }
        // 滚轮换手里的东西（界面里也能换到遥控器）
        int wheel = Mouse.getEventDWheel();
        if(wheel != 0 && mc.player != null)
            mc.player.inventory.changeCurrentItem(wheel);
        super.handleMouseInput();
    }

    @Override
    public void handleKeyboardInput() throws IOException
    {
        int key = Keyboard.getEventKey() == 0 ? Keyboard.getEventCharacter() + 256 : Keyboard.getEventKey();
        if(key != Keyboard.KEY_ESCAPE && !Keyboard.isRepeatEvent() && RemoteControls.INSTANCE.handleButton(key, Keyboard.getEventKeyState(), pos, RemoteControls.sneakHeld(), true))
            return;
        // 快捷栏的数字键照常换手里的东西（看大屏时换到遥控器）
        if(Keyboard.getEventKeyState() && mc.player != null)
        {
            for(int i = 0; i < 9; i++)
            {
                if(mc.gameSettings.keyBindsHotbar[i].isActiveAndMatches(key))
                {
                    mc.player.inventory.currentItem = i;
                    return;
                }
            }
        }
        super.handleKeyboardInput();
    }

    @Override
    public void onGuiClosed()
    {
        RemoteControls.INSTANCE.cancel();
        if(pos.equals(TvVideoManager.INSTANCE.getFullscreen()))
            TvVideoManager.INSTANCE.setFullscreen(null);
    }

    @Override
    public boolean doesGuiPauseGame()
    {
        return false;
    }
}
