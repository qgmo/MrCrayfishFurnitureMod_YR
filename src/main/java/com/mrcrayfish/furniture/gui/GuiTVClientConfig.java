package com.mrcrayfish.furniture.gui;

import com.mrcrayfish.furniture.client.tv.BiliData;
import com.mrcrayfish.furniture.client.tv.NativeLibs;
import com.mrcrayfish.furniture.client.tv.TvClientConfig;
import com.mrcrayfish.furniture.client.tv.TvCookies;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * 电视视频的本地配置（悠然服务器魔改）：拿着遥控器、没对着电视时潜行 + 左键打开。只是本机的配置，不存进遥控器，也不发给服务端。
 * <p>
 * 改到一半关掉界面，改的内容留在内存里，下次打开接着改；只有点“保存”或“放弃修改”（再点一次确认）才结束。
 * bilibili cookie 不在界面上显示原文：只能从剪贴板粘贴或清除，界面上只写“已设置（N 个字符）”。
 * 代理的用户名、密码可以输入，但输入框里只显示星号，也不能复制、剪切出来（免得截图、录屏带出去）。
 */
public class GuiTVClientConfig extends GuiScreen
{
    /** 没保存的修改（关界面不丢） */
    @Nullable
    private static TvClientConfig.Values draft;

    @Nullable
    private final GuiScreen parent;
    private TvClientConfig.Values values;
    private GuiTextField startRange, hearRange, volume, proxyHost, proxyPort;
    private MaskedField proxyUsername, proxyPassword;
    private GuiButton quality, decoders, danmaku, proxyType, pasteCookie, clearCookie, save, discard;
    private long discardArmedAt;
    private String status = "";

    public GuiTVClientConfig(@Nullable GuiScreen parent)
    {
        this.parent = parent;
    }

    @Override
    public void initGui()
    {
        Keyboard.enableRepeatEvents(true);
        if(values == null)
        {
            values = draft != null ? draft.copy() : TvClientConfig.get().copy();
            if(draft != null)
                status = I18n.format("cfm.tv.config.draft");
        }
        int left = width / 2 - 150, right = width / 2 + 4;
        int top = top();
        buttonList.clear();
        quality = new GuiButton(0, left, top + ROW_BUTTONS, 146, 20, "");
        decoders = new GuiButton(1, right, top + ROW_BUTTONS, 146, 20, "");
        danmaku = new GuiButton(2, left, top + ROW_BUTTONS + 24, 146, 20, "");
        proxyType = new GuiButton(3, right, top + ROW_BUTTONS + 24, 146, 20, "");
        startRange = field(10, left + 96, top + ROW_FIELDS, 50, Integer.toString(values.startRange), 3);
        hearRange = field(11, right + 96, top + ROW_FIELDS, 50, Integer.toString(values.hearRange), 2);
        volume = field(12, left + 96, top + ROW_FIELDS + 22, 50, Integer.toString(values.masterVolume), 3);
        proxyPort = field(14, right + 96, top + ROW_FIELDS + 22, 50, values.proxyPort > 0 ? Integer.toString(values.proxyPort) : "", 5);
        proxyHost = field(13, right + 50, top + ROW_FIELDS + 44, 96, values.proxyHost, 253);
        proxyUsername = masked(15, left + 50, top + ROW_FIELDS + 66, 96, values.proxyUsername);
        proxyPassword = masked(16, right + 50, top + ROW_FIELDS + 66, 96, values.proxyPassword);
        pasteCookie = new GuiButton(4, left + 150, top + ROW_COOKIE, 72, 20, I18n.format("cfm.tv.config.cookie_paste"));
        clearCookie = new GuiButton(5, left + 226, top + ROW_COOKIE, 72, 20, I18n.format("cfm.tv.config.cookie_clear"));
        discard = new GuiButton(6, width / 2 - 150, top + ROW_SAVE, 146, 20, "");
        save = new GuiButton(7, width / 2 + 4, top + ROW_SAVE, 146, 20, I18n.format("cfm.tv.config.save"));
        buttonList.add(quality);
        buttonList.add(decoders);
        buttonList.add(danmaku);
        buttonList.add(proxyType);
        buttonList.add(pasteCookie);
        buttonList.add(clearCookie);
        buttonList.add(discard);
        buttonList.add(save);
        refresh();
    }

    private static final int ROW_BUTTONS = 26, ROW_FIELDS = 78, ROW_COOKIE = 168, ROW_STATUS = 194, ROW_SAVE = 208, HEIGHT = 230;

    private int top()
    {
        return Math.max(4, (height - HEIGHT) / 2);
    }

    private GuiTextField field(int id, int x, int y, int w, String text, int max)
    {
        GuiTextField f = new GuiTextField(id, fontRenderer, x, y, w, 16);
        f.setMaxStringLength(max);
        f.setText(text);
        return f;
    }

    private MaskedField masked(int id, int x, int y, int w, String text)
    {
        MaskedField f = new MaskedField(id, fontRenderer, x, y, w, 16);
        f.setMaxStringLength(TvClientConfig.MAX_CREDENTIAL);
        f.setText(text);
        return f;
    }

    private GuiTextField[] fields()
    {
        return new GuiTextField[]{startRange, hearRange, volume, proxyHost, proxyPort, proxyUsername, proxyPassword};
    }

    private void refresh()
    {
        quality.displayString = I18n.format("cfm.tv.config.quality", qualityName(values.maxQuality)) + (values.hasCookie() || values.maxQuality <= BiliData.QN_480P ? "" : "*");
        decoders.displayString = I18n.format("cfm.tv.config.decoders", values.maxDecoders);
        danmaku.displayString = I18n.format(values.alwaysHideDanmaku ? "cfm.tv.config.danmaku_hidden" : "cfm.tv.config.danmaku_shown");
        proxyType.displayString = I18n.format("cfm.tv.config.proxy", I18n.format("cfm.tv.config.proxy." + values.proxyType.name().toLowerCase(java.util.Locale.ROOT)));
        clearCookie.enabled = values.hasCookie();
        boolean armed = System.currentTimeMillis() - discardArmedAt < 3000;
        discard.displayString = I18n.format(armed ? "cfm.tv.config.discard_confirm" : "cfm.tv.config.discard");
    }

    private static String qualityName(int q)
    {
        switch(q)
        {
            case BiliData.QN_360P:
                return "360P";
            case BiliData.QN_480P:
                return "480P";
            case BiliData.QN_720P:
                return "720P";
            default:
                return "1080P";
        }
    }

    /** 把输入框里的数字读进 values（读不出的保留原值） */
    private void readFields()
    {
        values.startRange = number(startRange, values.startRange, 4, 128);
        values.hearRange = number(hearRange, values.hearRange, 1, 64);
        values.masterVolume = number(volume, values.masterVolume, 0, 100);
        values.proxyHost = proxyHost.getText().trim();
        values.proxyPort = proxyPort.getText().trim().isEmpty() ? 0 : number(proxyPort, values.proxyPort, 0, 65535);
        values.proxyUsername = TvClientConfig.cleanCredential(proxyUsername.getText());
        values.proxyPassword = TvClientConfig.cleanCredential(proxyPassword.getText());
    }

    private static int number(GuiTextField f, int old, int min, int max)
    {
        try
        {
            return Math.max(min, Math.min(max, Integer.parseInt(f.getText().trim())));
        }
        catch(NumberFormatException e)
        {
            return old;
        }
    }

    private void keepDraft()
    {
        readFields();
        draft = values.copy();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException
    {
        if(button == quality)
        {
            int[] q = TvClientConfig.QUALITIES;
            int i = 0;
            while(i < q.length && q[i] != values.maxQuality)
                i++;
            values.maxQuality = q[(i + 1) % q.length];
        }
        else if(button == decoders)
        {
            values.maxDecoders = values.maxDecoders % 8 + 1;
        }
        else if(button == danmaku)
        {
            values.alwaysHideDanmaku = !values.alwaysHideDanmaku;
        }
        else if(button == proxyType)
        {
            TvClientConfig.ProxyType[] t = TvClientConfig.ProxyType.values();
            values.proxyType = t[(values.proxyType.ordinal() + 1) % t.length];
        }
        else if(button == pasteCookie)
        {
            String clip = TvCookies.sanitize(GuiScreen.getClipboardString());
            if(clip.isEmpty())
            {
                status = I18n.format("cfm.tv.config.cookie_empty");
            }
            else
            {
                values.cookie = clip;
                status = I18n.format("cfm.tv.config.cookie_pasted");
            }
        }
        else if(button == clearCookie)
        {
            values.cookie = "";
        }
        else if(button == discard)
        {
            if(System.currentTimeMillis() - discardArmedAt < 3000)
            {
                draft = null;
                values = TvClientConfig.get().copy();
                discardArmedAt = 0;
                initGui();
                status = I18n.format("cfm.tv.config.discarded");
                return;
            }
            discardArmedAt = System.currentTimeMillis();
        }
        else if(button == save)
        {
            readFields();
            TvClientConfig.save(values);
            draft = null;
            values = TvClientConfig.get().copy();
            status = I18n.format("cfm.tv.config.saved");
            initGui();
            return;
        }
        keepDraft();
        refresh();
    }

    @Override
    public void updateScreen()
    {
        for(GuiTextField f : fields())
            f.updateCursorCounter();
        refresh();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException
    {
        if(keyCode == Keyboard.KEY_ESCAPE)
        {
            keepDraft();
            mc.displayGuiScreen(parent);
            return;
        }
        for(GuiTextField f : fields())
        {
            if(f.isFocused())
            {
                // 用户名、密码不能复制、剪切出来
                if(f instanceof MaskedField && (GuiScreen.isKeyComboCtrlC(keyCode) || GuiScreen.isKeyComboCtrlX(keyCode)))
                    return;
                // 数字框只收数字
                boolean numeric = f != proxyHost && !(f instanceof MaskedField);
                if(numeric && Character.isLetter(typedChar))
                    return;
                if(f.textboxKeyTyped(typedChar, keyCode))
                    keepDraft();
                return;
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException
    {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for(GuiTextField f : fields())
            f.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void onGuiClosed()
    {
        Keyboard.enableRepeatEvents(false);
        if(values != null && draft != null)
            keepDraft();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        drawDefaultBackground();
        int left = width / 2 - 150, right = width / 2 + 4;
        int top = top();
        drawCenteredString(fontRenderer, I18n.format("cfm.tv.config.title"), width / 2, top, 0xFFFFFF);
        // 只有一行：太长（例如别的语言）就截掉，不压到两边
        drawCenteredString(fontRenderer, fontRenderer.trimStringToWidth(libraryStatus(), width - 8), width / 2, top + 12, 0xA0A0A0);
        int y = top + ROW_FIELDS;
        fontRenderer.drawString(I18n.format("cfm.tv.config.start_range"), left, y + 4, 0xE0E0E0);
        fontRenderer.drawString(I18n.format("cfm.tv.config.hear_range"), right, y + 4, 0xE0E0E0);
        y += 22;
        fontRenderer.drawString(I18n.format("cfm.tv.config.volume"), left, y + 4, 0xE0E0E0);
        fontRenderer.drawString(I18n.format("cfm.tv.config.proxy_port"), right, y + 4, 0xE0E0E0);
        y += 22;
        fontRenderer.drawString(I18n.format("cfm.tv.config.proxy_host"), right, y + 4, 0xE0E0E0);
        y += 22;
        fontRenderer.drawString(I18n.format("cfm.tv.config.proxy_username"), left, y + 4, 0xE0E0E0);
        fontRenderer.drawString(I18n.format("cfm.tv.config.proxy_password"), right, y + 4, 0xE0E0E0);
        y = top + ROW_COOKIE;
        String cookie = values.hasCookie() ? I18n.format("cfm.tv.config.cookie_set", TvCookies.sanitize(values.cookie).length()) : I18n.format("cfm.tv.config.cookie_unset");
        fontRenderer.drawString(I18n.format("cfm.tv.config.cookie", cookie), left, y + 6, 0xE0E0E0);
        for(GuiTextField f : fields())
            f.drawTextBox();
        drawCenteredString(fontRenderer, status, width / 2, top + ROW_STATUS, 0xFFFF80);
        if(quality.isMouseOver() && !values.hasCookie() && values.maxQuality > BiliData.QN_480P)
            drawHoveringText(I18n.format("cfm.tv.config.quality_hint"), mouseX, mouseY);
        if(pasteCookie.isMouseOver())
            drawHoveringText(java.util.Arrays.asList(I18n.format("cfm.tv.config.cookie_format").split("\\n")), mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private static String libraryStatus()
    {
        switch(NativeLibs.getState())
        {
            case READY:
                return I18n.format("cfm.tv.config.lib_ready");
            case DOWNLOADING:
            case LOADING:
                return I18n.format("cfm.tv.video.downloading", Math.round(NativeLibs.progress() * 100));
            case FAILED:
                return I18n.format(NativeLibs.failedProxyAuth() ? "cfm.tv.config.lib_failed_proxy_auth" : "cfm.tv.config.lib_failed");
            case UNSUPPORTED:
                return I18n.format("cfm.tv.video.unsupported");
            default:
                return I18n.format("cfm.tv.config.lib_not_yet");
        }
    }

    @Override
    public boolean doesGuiPauseGame()
    {
        return false;
    }

    /** 只显示星号的输入框（代理用户名、密码）：画的时候临时换成同样长度的星号，画完换回来 */
    static final class MaskedField extends GuiTextField
    {
        MaskedField(int id, net.minecraft.client.gui.FontRenderer font, int x, int y, int w, int h)
        {
            super(id, font, x, y, w, h);
        }

        @Override
        public void drawTextBox()
        {
            String real = getText();
            int cursor = getCursorPosition();
            int selection = getSelectionEnd();
            setText(mask(real.length()));
            setCursorPosition(cursor);
            setSelectionPos(selection);
            try
            {
                super.drawTextBox();
            }
            finally
            {
                setText(real);
                setCursorPosition(cursor);
                setSelectionPos(selection);
            }
        }

        private static String mask(int n)
        {
            StringBuilder sb = new StringBuilder(n);
            for(int i = 0; i < n; i++)
                sb.append('*');
            return sb.toString();
        }
    }
}
