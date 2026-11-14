package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.TypingSync;
import com.mrcrayfish.furniture.computer.UrlInput;
import com.mrcrayfish.furniture.gui.ApplianceGui;
import com.mrcrayfish.furniture.gui.containers.ContainerComputer;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerAction;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import moe.qingu.youran.api.network.tls.CertificateInfo;
import moe.qingu.youran.api.network.ClientNic;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 电脑的界面：一台显示器，里面是浏览器（悠然服务器魔改，服主 11-14）。
 * <ul>
 * <li>工具栏：后退、前进、刷新、主页、锁（https 显示锁，点开看连接信息；http 显示“不安全”）、地址栏、电源开关、物品面板（能量单元卡槽、电量条、背包）。</li>
 * <li>页面：内核画好的图贴在中间，链接、按钮、输入框按位置点；输入框、复选框、下拉框、物品图标由界面画在图上。</li>
 * <li>操作者：在本地上网；旁观者：看服务端转来的页面（自己渲染），跟着操作者滚动，不能点；没人操作时可以“接管”。</li>
 * <li>关机或没电时整屏黑掉，只留电源键；地址栏、表单里填的东西都留着（存在 {@link Browser} 里）。</li>
 * </ul>
 */
public class GuiBrowser extends GuiContainer
{
    private static final int FOCUS_NONE = -2;
    private static final int FOCUS_ADDRESS = -1;

    private static final int BEZEL = 0xFF202326;
    private static final int TOOLBAR = 0xFFE8EAED;
    private static final int FIELD = 0xFFFFFFFF;
    private static final int FIELD_BORDER = 0xFF9AA0A6;
    private static final int FIELD_FOCUS = 0xFF1A73E8;
    private static final int TEXT = 0xFF202124;
    private static final int TEXT_DIM = 0xFF6B6F75;
    private static final int SELECTION = 0xFF9EC3FF;

    private final TileEntityComputer computer;
    private final ContainerComputer container;

    // 布局（界面坐标）
    private int sx, sy, sw, sh;
    private int toolbarY;
    private int vpX, vpY, vpW, vpH;
    private int statusY;
    private int addrX, addrW;
    private int lockX, lockW;
    private final List<ToolButton> tools = new ArrayList<>();
    /** 1 个 CSS 像素在界面上多大 */
    private double factor = 1.0;
    private double deviceScale = 2.0;

    private int focus = FOCUS_NONE;
    private boolean panel;
    private boolean info;
    private int infoScroll;
    private long caretStart = System.currentTimeMillis();

    // 当前显示的页面
    @Nullable
    private PageTextures textures;
    private int textureVersion = -1;
    // 旁观
    private int viewVersion = -1;
    @Nullable
    private RenderedPage spectatorPage;
    @Nullable
    private PageSnapshot spectatorSnapshot;
    private int spectatorRender;
    private double spectatorScroll;

    private String hoverText = "";

    public GuiBrowser(InventoryPlayer inventoryPlayer, TileEntityComputer computer)
    {
        super(new ContainerComputer(inventoryPlayer, computer));
        this.computer = computer;
        this.container = (ContainerComputer) this.inventorySlots;
        this.xSize = ContainerComputer.PANEL_WIDTH;
        this.ySize = ContainerComputer.PANEL_HEIGHT;
    }

    private final class ToolButton
    {
        final String id;
        String label;
        int x, y, w, h;
        boolean enabled = true;
        boolean visible = true;

        ToolButton(String id, String label)
        {
            this.id = id;
            this.label = label;
        }

        boolean over(int mx, int my)
        {
            return visible && mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    // ---- 角色 ----

    private boolean isOperator()
    {
        return mc.player != null && computer.getOperatorId() != null && computer.getOperatorId().equals(mc.player.getUniqueID());
    }

    private boolean screenOn()
    {
        return computer.isSwitchedOn() && computer.getAppliance().isRunning();
    }

    @Nullable
    private Browser browser()
    {
        return isOperator() ? ComputerViews.browser(computer.getPos()) : null;
    }

    // ---- 布局 ----

    @Override
    public void initGui()
    {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        layout();
    }

    private void layout()
    {
        int margin = 6;
        sx = margin;
        sy = margin;
        sw = width - margin * 2;
        sh = height - margin * 2;
        toolbarY = sy + 4;
        int tbH = 14;
        vpX = sx + 4;
        vpY = toolbarY + tbH + 3;
        vpW = sw - 8 - 5;
        statusY = sy + sh - 12;
        vpH = statusY - 2 - vpY;
        factor = vpW / (double) PageRenderer.VIEW_WIDTH;
        ScaledResolution sr = new ScaledResolution(mc);
        deviceScale = Math.max(1.0, Math.min(2.5, factor * sr.getScaleFactor()));

        tools.clear();
        int x = sx + 4;
        String[][] left = {{"back", "←"}, {"forward", "→"}, {"reload", "↻"}, {"home", "⌂"}};
        for(String[] b : left)
        {
            ToolButton t = new ToolButton(b[0], b[1]);
            t.x = x;
            t.y = toolbarY;
            t.w = 14;
            t.h = tbH;
            tools.add(t);
            x += 15;
        }
        String powerLabel = I18n.format("cfm.browser.power");
        String itemsLabel = I18n.format("cfm.browser.items");
        int powerW = fontRenderer.getStringWidth(powerLabel) + 8;
        int itemsW = fontRenderer.getStringWidth(itemsLabel) + 8;
        int right = sx + sw - 4;
        ToolButton items = new ToolButton("items", itemsLabel);
        items.w = itemsW;
        items.x = right - itemsW;
        items.y = toolbarY;
        items.h = tbH;
        ToolButton power = new ToolButton("power", powerLabel);
        power.w = powerW;
        power.x = items.x - 2 - powerW;
        power.y = toolbarY;
        power.h = tbH;
        tools.add(power);
        tools.add(items);
        lockX = x + 2;
        lockW = Math.max(14, fontRenderer.getStringWidth(I18n.format("cfm.browser.insecure")) + 6);
        addrX = lockX + lockW + 2;
        addrW = Math.max(40, power.x - 3 - addrX);

        // 物品面板放在右下角（盖在页面上）
        guiLeft = Math.max(0, Math.min(width - xSize, sx + sw - xSize - 6));
        guiTop = Math.max(0, Math.min(height - ySize, statusY - ySize - 2));
    }

    @Override
    public void onGuiClosed()
    {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        if(textures != null)
        {
            textures.dispose();
            textures = null;
        }
        Browser b = ComputerViews.browser(computer.getPos());
        b.releaseRender();
        spectatorPage = null;
    }

    @Override
    public void updateScreen()
    {
        super.updateScreen();
        Browser b = browser();
        if(b != null && screenOn())
        {
            b.setScale(deviceScale);
            b.ensureStarted();
            b.tick();
        }
        if(!isOperator())
        {
            ComputerViews.View v = ComputerViews.view(computer.getPos());
            if(v.version != viewVersion)
            {
                viewVersion = v.version;
                spectatorSnapshot = null;
                spectatorPage = null;
                int req = ++spectatorRender;
                if(v.page != null)
                {
                    ComputerViews.renderSnapshot(v.page, deviceScale, s -> spectatorSnapshot = s, p ->
                    {
                        if(req != spectatorRender)
                            return;
                        spectatorPage = p;
                        // 先显示没有图的页面，再用自己的玩家临时地址取图（服主 11-14），取到了重画
                        if(p != null && !p.document.images.isEmpty())
                            loadSpectatorImages(req, p.document.images);
                    });
                }
            }
            spectatorScroll = v.scroll;
        }
        else
        {
            viewVersion = -1;
        }
    }

    /** 旁观者用自己的玩家临时地址取页面里的图（服务器只放行这一页图片所在的主机），规则同操作者（{@link ImageFetcher}） */
    private void loadSpectatorImages(int req, List<String> urls)
    {
        ClientNic.Session s = ClientNic.player();
        PageSnapshot snap = spectatorSnapshot;
        if(s == null || s.isClosed() || snap == null)
            return;
        ImageFetcher.fetch(s, urls, () -> req == spectatorRender && mc.currentScreen == this, got ->
        {
            if(got.isEmpty())
                return;
            ComputerViews.render(snap.html, snap.mode, snap.url, got, deviceScale, p ->
            {
                if(req == spectatorRender && p != null)
                    spectatorPage = p;
            });
        });
    }

    // ---- 当前页面 ----

    @Nullable
    private RenderedPage page()
    {
        if(!screenOn())
            return null;
        Browser b = browser();
        if(b != null)
            return b.rendered();
        return spectatorPage;
    }

    @Nullable
    private PageSnapshot.Connection connection()
    {
        Browser b = browser();
        if(b != null)
        {
            Browser.Entry e = b.current();
            return e == null ? null : e.connection;
        }
        return spectatorSnapshot == null ? null : spectatorSnapshot.connection;
    }

    private String currentUrl()
    {
        Browser b = browser();
        if(b != null)
        {
            Browser.Entry e = b.current();
            return e == null ? "" : e.url;
        }
        return spectatorSnapshot == null ? "" : spectatorSnapshot.url;
    }

    /** 浏览器自己的页面（主页、错误页）：不显示锁，也不显示“不安全” */
    private boolean isBuiltinPage()
    {
        Browser b = browser();
        if(b != null)
            return b.current() == null || b.current().builtin;
        return spectatorSnapshot == null || spectatorSnapshot.url.startsWith("about:");
    }

    private double scroll()
    {
        Browser b = browser();
        if(b != null)
        {
            Browser.Entry e = b.current();
            return e == null ? 0 : e.scroll;
        }
        return spectatorScroll;
    }

    private double maxScroll(RenderedPage p)
    {
        return Math.max(0, p.height - vpH / factor);
    }

    private void scrollBy(double css)
    {
        Browser b = browser();
        RenderedPage p = page();
        if(b == null || p == null || b.current() == null)
            return;
        Browser.Entry e = b.current();
        e.scroll = (int) Math.max(0, Math.min(maxScroll(p), e.scroll + css));
        b.reportScroll(e.scroll);
    }

    // ---- 画 ----

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        this.drawDefaultBackground();
        layout();
        hoverText = "";
        // 显示器
        Gui.drawRect(sx, sy, sx + sw, sy + sh, BEZEL);
        boolean on = screenOn();
        RenderedPage p = page();
        if(textures != null && (p == null || textures.page != p))
        {
            textures.dispose();
            textures = null;
        }
        if(p != null && textures == null)
        {
            textures = new PageTextures(p);
        }
        if(on)
        {
            Gui.drawRect(sx + 2, toolbarY - 2, sx + sw - 2, toolbarY + 16, TOOLBAR);
            Gui.drawRect(vpX, vpY, vpX + vpW + 5, vpY + vpH, 0xFFFFFFFF);
            drawPage(p, mouseX, mouseY);
            drawToolbar(mouseX, mouseY);
            drawStatus(mouseX, mouseY);
        }
        else
        {
            Gui.drawRect(sx + 2, sy + 2, sx + sw - 2, sy + sh - 2, 0xFF000000);
            String msg = I18n.format(computer.isSwitchedOn() ? "cfm.browser.no_power" : "cfm.browser.off");
            drawCenteredString(fontRenderer, msg, sx + sw / 2, sy + sh / 2 - 4, 0xFF8A8A8A);
            for(ToolButton t : tools)
                t.visible = t.id.equals("power") || t.id.equals("items");
            drawButtons(mouseX, mouseY);
            drawRole(mouseX, mouseY);
        }
        if(panel)
        {
            super.drawScreen(mouseX, mouseY, partialTicks);
            this.renderHoveredToolTip(mouseX, mouseY);
            List<String> power = ApplianceGui.tooltip(guiLeft, guiTop, ContainerComputer.CARD_X, ContainerComputer.CARD_Y, container.getCardSlot(), computer.getAppliance(), mouseX, mouseY);
            if(power != null)
                drawHoveringText(power, mouseX, mouseY);
        }
        if(info && on)
        {
            drawInfo();
        }
        else if(on && p != null && !panel)
        {
            drawItemTooltip(p, mouseX, mouseY);
        }
    }

    private void drawToolbar(int mouseX, int mouseY)
    {
        Browser b = browser();
        boolean op = b != null;
        for(ToolButton t : tools)
        {
            t.visible = true;
            switch(t.id)
            {
                case "back": t.enabled = op && b.canBack(); break;
                case "forward": t.enabled = op && b.canForward(); break;
                case "reload": t.enabled = op && b.current() != null; t.label = op && b.status() == Browser.Status.LOADING ? "×" : "↻"; break;
                case "home": t.enabled = op; break;
                case "power": t.enabled = op; break;
                default: t.enabled = true;
            }
        }
        drawButtons(mouseX, mouseY);
        // 锁
        PageSnapshot.Connection conn = connection();
        String url = isBuiltinPage() ? "" : currentUrl();
        if(conn != null)
        {
            drawLock(lockX + (lockW - 9) / 2, toolbarY + 3, 0xFF188038);
        }
        else if(url.startsWith("http://"))
        {
            fontRenderer.drawString(I18n.format("cfm.browser.insecure"), lockX + 2, toolbarY + 3, 0xFFB3261E);
        }
        // 地址栏
        boolean focused = focus == FOCUS_ADDRESS && op;
        Gui.drawRect(addrX, toolbarY, addrX + addrW, toolbarY + 14, focused ? FIELD_FOCUS : FIELD_BORDER);
        Gui.drawRect(addrX + 1, toolbarY + 1, addrX + addrW - 1, toolbarY + 13, FIELD);
        if(op)
        {
            drawTextEdit(b.address, addrX + 3, toolbarY + 3, addrW - 6, focused, false, TEXT);
        }
        else
        {
            // 旁观：操作者正在地址栏里打的字（服主 11-14），没有就显示当前页面的网址
            TypingSync.Typed typed = ComputerViews.view(computer.getPos()).typed.get(TypingSync.ADDRESS);
            boolean typing = typed != null && !typed.text.isEmpty() && !typed.text.equals(UrlInput.display(url));
            String shown = fontRenderer.trimStringToWidth(typing ? typed.text : UrlInput.display(url), addrW - 6);
            fontRenderer.drawString(shown, addrX + 3, toolbarY + 3, typing ? TEXT : TEXT_DIM);
        }
    }

    private void drawButtons(int mouseX, int mouseY)
    {
        for(ToolButton t : tools)
        {
            if(!t.visible)
                continue;
            boolean hover = t.enabled && t.over(mouseX, mouseY);
            int bg = !t.enabled ? 0xFFD0D3D7 : hover ? 0xFFC9D7F2 : 0xFFF8F9FA;
            if(t.id.equals("power"))
                bg = computer.isSwitchedOn() ? (hover ? 0xFF5BB974 : 0xFF34A853) : (hover ? 0xFFE8817A : 0xFFD93025);
            if(t.id.equals("items") && panel)
                bg = 0xFFAECBFA;
            Gui.drawRect(t.x, t.y, t.x + t.w, t.y + t.h, 0xFF80868B);
            Gui.drawRect(t.x + 1, t.y + 1, t.x + t.w - 1, t.y + t.h - 1, bg);
            int color = t.enabled ? (t.id.equals("power") ? 0xFFFFFFFF : TEXT) : 0xFF9AA0A6;
            drawCenteredString0(t.label, t.x + t.w / 2, t.y + (t.h - 8) / 2, color);
        }
    }

    private void drawCenteredString0(String s, int cx, int y, int color)
    {
        fontRenderer.drawString(s, cx - fontRenderer.getStringWidth(s) / 2, y, color);
    }

    /** 一把小锁（9×9） */
    private void drawLock(int x, int y, int color)
    {
        Gui.drawRect(x + 2, y, x + 7, y + 1, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y + 4, color);
        Gui.drawRect(x + 7, y + 1, x + 8, y + 4, color);
        Gui.drawRect(x, y + 4, x + 9, y + 9, color);
        Gui.drawRect(x + 4, y + 5, x + 5, y + 7, 0xFFFFFFFF);
    }

    private void drawTextEdit(TextEdit t, int x, int y, int w, boolean focused, boolean masked, int color)
    {
        String text = masked ? mask(t.getText()) : t.getText();
        int cursor = t.getCursor();
        // 保证光标看得见
        if(t.viewStart > cursor)
            t.viewStart = cursor;
        if(t.viewStart > text.length())
            t.viewStart = text.length();
        while(t.viewStart < cursor && fontRenderer.getStringWidth(text.substring(t.viewStart, cursor)) > w - 2)
            t.viewStart++;
        String visible = fontRenderer.trimStringToWidth(text.substring(t.viewStart), w);
        int end = t.viewStart + visible.length();
        if(focused && t.hasSelection())
        {
            int s = Math.max(t.selectionStart(), t.viewStart);
            int e = Math.min(t.selectionEnd(), end);
            if(e > s)
            {
                int x1 = x + fontRenderer.getStringWidth(text.substring(t.viewStart, s));
                int x2 = x + fontRenderer.getStringWidth(text.substring(t.viewStart, e));
                Gui.drawRect(x1, y - 1, x2, y + 9, SELECTION);
            }
        }
        fontRenderer.drawString(visible, x, y, color);
        if(focused && ((System.currentTimeMillis() - caretStart) / 500) % 2 == 0 && cursor >= t.viewStart && cursor <= end)
        {
            int cx = x + fontRenderer.getStringWidth(text.substring(t.viewStart, cursor));
            Gui.drawRect(cx, y - 1, cx + 1, y + 9, 0xFF000000);
        }
    }

    private static String dots(int n)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < Math.min(n, TypingSync.MAX_LENGTH); i++)
            sb.append('•');
        return sb.toString();
    }

    private static String mask(String s)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < s.length(); i++)
            sb.append('•');
        return sb.toString();
    }

    private void drawStatus(int mouseX, int mouseY)
    {
        Browser b = browser();
        String left = hoverText;
        if(left.isEmpty() && b != null && b.status() != Browser.Status.IDLE)
            left = b.statusText();
        RenderedPage p = page();
        if(left.isEmpty() && p != null && p.truncated())
            left = I18n.format("cfm.browser.truncated", PageRenderer.MAX_HEIGHT);
        fontRenderer.drawString(fontRenderer.trimStringToWidth(left, sw / 2), sx + 5, statusY + 2, 0xFFBDC1C6);
        drawRole(mouseX, mouseY);
    }

    private int claimX, claimW;

    private void drawRole(int mouseX, int mouseY)
    {
        String role;
        claimW = 0;
        if(isOperator())
            role = I18n.format("cfm.browser.role.operator");
        else if(computer.getOperatorId() != null)
            role = I18n.format("cfm.browser.role.spectator", computer.getOperatorName());
        else
            role = I18n.format("cfm.browser.role.nobody");
        int rx = sx + sw - 5 - fontRenderer.getStringWidth(role);
        if(!isOperator() && computer.getOperatorId() == null)
        {
            String claim = I18n.format("cfm.browser.claim");
            claimW = fontRenderer.getStringWidth(claim) + 6;
            claimX = sx + sw - 5 - claimW;
            boolean hover = mouseX >= claimX && mouseX < claimX + claimW && mouseY >= statusY && mouseY < statusY + 11;
            Gui.drawRect(claimX, statusY, claimX + claimW, statusY + 11, hover ? 0xFF5B8DEF : 0xFF1A73E8);
            fontRenderer.drawString(claim, claimX + 3, statusY + 2, 0xFFFFFFFF);
            rx = claimX - 4 - fontRenderer.getStringWidth(role);
        }
        fontRenderer.drawString(role, rx, statusY + 2, 0xFFE8EAED);
    }

    private void drawPage(@Nullable RenderedPage p, int mouseX, int mouseY)
    {
        if(p == null || textures == null)
        {
            Browser b = browser();
            String msg;
            if(b != null)
                msg = b.status() == Browser.Status.IDLE && b.current() != null && b.rendered() == null ? I18n.format("cfm.browser.render_failed") : I18n.format("cfm.browser.status.rendering");
            else
                msg = ComputerViews.view(computer.getPos()).page == null ? I18n.format("cfm.browser.no_page") : I18n.format("cfm.browser.status.rendering");
            drawCenteredString(fontRenderer, msg, vpX + vpW / 2, vpY + vpH / 2 - 4, TEXT_DIM);
            return;
        }
        double scroll = Math.min(scroll(), maxScroll(p));
        startScissor(vpX, vpY, vpW, vpH);
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableTexture2D();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        for(int i = 0; i < textures.tiles.size(); i++)
        {
            int[] row = textures.rows.get(i);
            double top = vpY + (row[0] / p.scale - scroll) * factor;
            double h = row[1] / p.scale * factor;
            if(top > vpY + vpH || top + h < vpY)
                continue;
            GlStateManager.bindTexture(textures.tiles.get(i).getGlTextureId());
            double w = p.image.getWidth() / p.scale * factor;
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            buf.pos(vpX, top + h, zLevel).tex(0, 1).endVertex();
            buf.pos(vpX + w, top + h, zLevel).tex(1, 1).endVertex();
            buf.pos(vpX + w, top, zLevel).tex(1, 0).endVertex();
            buf.pos(vpX, top, zLevel).tex(0, 0).endVertex();
            tess.draw();
        }
        drawOverlays(p, scroll, mouseX, mouseY);
        endScissor();
        // 滚动条
        double max = maxScroll(p);
        if(max > 0)
        {
            int barX = vpX + vpW + 1;
            Gui.drawRect(barX, vpY, barX + 4, vpY + vpH, 0xFFE0E0E0);
            int thumbH = Math.max(10, (int) (vpH * (vpH / factor) / p.height));
            int thumbY = vpY + (int) ((vpH - thumbH) * (scroll / max));
            Gui.drawRect(barX, thumbY, barX + 4, thumbY + thumbH, 0xFF9AA0A6);
        }
    }

    private int gx(double cssX)
    {
        return (int) Math.round(vpX + cssX * factor);
    }

    private int gy(double cssY, double scroll)
    {
        return (int) Math.round(vpY + (cssY - scroll) * factor);
    }

    private void drawOverlays(RenderedPage p, double scroll, int mouseX, int mouseY)
    {
        Browser b = browser();
        FormState forms = b != null && b.current() != null ? b.current().forms : null;
        // 旁观：操作者正在框里打的字（密码框只有长度）
        Map<Integer, TypingSync.Typed> typed = b == null ? ComputerViews.view(computer.getPos()).typed : Collections.<Integer, TypingSync.Typed>emptyMap();
        PageDocument doc = p.document;
        for(PageDocument.Control c : doc.controls)
        {
            List<RenderedPage.Rect> rects = p.rects(c.n);
            if(rects.isEmpty())
                continue;
            RenderedPage.Rect r = rects.get(0);
            int x1 = gx(r.x), y1 = gy(r.y, scroll), x2 = gx(r.x + r.w), y2 = gy(r.y + r.h, scroll);
            if(y2 < vpY || y1 > vpY + vpH)
                continue;
            int ty = (y1 + y2) / 2 - 4;
            switch(c.kind)
            {
                case TEXT:
                case PASSWORD:
                case TEXTAREA:
                {
                    TextEdit t = forms == null ? null : forms.text(c.n);
                    boolean focused = forms != null && focus == c.n;
                    if(focused)
                        Gui.drawRect(x1, y1, x2, y2, FIELD_FOCUS);
                    Gui.drawRect(x1 + 1, y1 + 1, x2 - 1, y2 - 1, c.disabled ? 0xFFEFEFEF : FIELD);
                    TypingSync.Typed other = typed.get(c.n);
                    if(t != null)
                        drawTextEdit(t, x1 + 3, ty, x2 - x1 - 6, focused, c.kind == PageDocument.Kind.PASSWORD, TEXT);
                    else if(other != null)
                        fontRenderer.drawString(fontRenderer.trimStringToWidth(other.password || c.kind == PageDocument.Kind.PASSWORD
                                ? dots(other.length) : other.text, x2 - x1 - 6), x1 + 3, ty, TEXT);
                    else if(c.kind != PageDocument.Kind.PASSWORD)
                        fontRenderer.drawString(fontRenderer.trimStringToWidth(c.value, x2 - x1 - 6), x1 + 3, ty, TEXT);
                    break;
                }
                case CHECKBOX:
                case RADIO:
                {
                    boolean checked = forms != null ? forms.isChecked(c.n) : c.checked;
                    if(checked)
                        Gui.drawRect(x1 + 2, y1 + 2, Math.max(x1 + 3, x2 - 2), Math.max(y1 + 3, y2 - 2), 0xFF1A73E8);
                    break;
                }
                case SELECT:
                {
                    int sel = forms != null ? forms.selected(c.n) : c.selected;
                    String label = c.optionLabels.isEmpty() ? "" : c.optionLabels.get(Math.min(sel, c.optionLabels.size() - 1));
                    Gui.drawRect(x1 + 1, y1 + 1, x2 - 1, y2 - 1, FIELD);
                    fontRenderer.drawString(fontRenderer.trimStringToWidth(label, x2 - x1 - 14), x1 + 3, ty, TEXT);
                    fontRenderer.drawString("▾", x2 - 9, ty, TEXT_DIM);
                    break;
                }
                default:
                    break;
            }
        }
        // 物品图标
        RenderHelper.enableGUIStandardItemLighting();
        for(Map.Entry<Integer, String> e : doc.items.entrySet())
        {
            List<RenderedPage.Rect> rects = p.rects(e.getKey());
            if(rects.isEmpty())
                continue;
            RenderedPage.Rect r = rects.get(0);
            ItemStack stack = ComputerViews.itemStack(e.getValue());
            if(stack.isEmpty())
                continue;
            double size = Math.min(r.w, r.h) * factor;
            double x = vpX + r.x * factor + (r.w * factor - size) / 2;
            double y = vpY + (r.y - scroll) * factor + (r.h * factor - size) / 2;
            if(y + size < vpY || y > vpY + vpH)
                continue;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(size / 16.0, size / 16.0, 1);
            itemRender.renderItemAndEffectIntoGUI(stack, 0, 0);
            GlStateManager.popMatrix();
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableDepth();
        // 指着的链接
        if(inViewport(mouseX, mouseY) && !panel && !info)
        {
            int n = p.hit((mouseX - vpX) / factor, (mouseY - vpY) / factor + scroll);
            if(n >= 0 && doc.links.containsKey(n))
            {
                String target = doc.links.get(n);
                hoverText = target == null ? I18n.format("cfm.browser.link_unsupported") : UrlInput.display(target);
            }
        }
    }

    private void drawItemTooltip(RenderedPage p, int mouseX, int mouseY)
    {
        if(!inViewport(mouseX, mouseY))
            return;
        int n = p.hit((mouseX - vpX) / factor, (mouseY - vpY) / factor + Math.min(scroll(), maxScroll(p)));
        String id = p.document.items.get(n);
        if(id != null)
        {
            ItemStack stack = ComputerViews.itemStack(id);
            if(!stack.isEmpty())
                renderToolTip(stack, mouseX, mouseY);
        }
    }

    private boolean inViewport(int mx, int my)
    {
        return mx >= vpX && mx < vpX + vpW && my >= vpY && my < vpY + vpH;
    }

    private void startScissor(int x, int y, int w, int h)
    {
        ScaledResolution sr = new ScaledResolution(mc);
        int f = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, mc.displayHeight - (y + h) * f, Math.max(0, w * f), Math.max(0, h * f));
    }

    private void endScissor()
    {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ---- 连接信息 ----

    private List<String> infoLines(int width)
    {
        List<String> out = new ArrayList<>();
        PageSnapshot.Connection c = connection();
        String url = currentUrl();
        out.add("§l" + I18n.format("cfm.browser.info.title"));
        // 主机：国际化域名两种写法都写出来（显示用的 Unicode 与实际用的 A-label）
        String host = UrlInput.hostOf(url);
        if(host != null)
        {
            String unicode = UrlInput.unicodeHost(host);
            out.add(unicode.equals(host) ? I18n.format("cfm.browser.info.host", host)
                    : I18n.format("cfm.browser.info.host_idn", unicode, host));
        }
        if(c == null)
        {
            out.add(I18n.format(url.startsWith("http://") ? "cfm.browser.info.insecure" : "cfm.browser.info.local"));
            return wrap(out, width);
        }
        out.add(I18n.format("cfm.browser.info.secure"));
        out.add(I18n.format("cfm.browser.info.protocol", c.protocol));
        out.add(I18n.format("cfm.browser.info.cipher", c.cipherSuite));
        out.add(I18n.format("cfm.browser.info.remote", "[" + c.remoteAddress + "]:" + c.remotePort));
        if(!isOperator())
            out.add("§7" + I18n.format("cfm.browser.info.reported"));
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'");
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for(int i = 0; i < c.chain.size(); i++)
        {
            CertificateInfo ci = c.chain.get(i);
            out.add("");
            out.add("§l" + I18n.format("cfm.browser.info.cert", i + 1));
            out.add(I18n.format("cfm.browser.info.subject", ci.subject));
            out.add(I18n.format("cfm.browser.info.issuer", ci.issuer));
            out.add(I18n.format("cfm.browser.info.validity", f.format(ci.notBefore), f.format(ci.notAfter)));
            out.add(I18n.format("cfm.browser.info.sha256", ci.sha256));
            out.add(I18n.format("cfm.browser.info.san", ci.subjectAltNames.isEmpty() ? "-" : String.join(", ", UrlInput.certificateNames(ci.subjectAltNames))));
        }
        out.add("");
        out.add("§7" + I18n.format("cfm.browser.info.root"));
        return wrap(out, width);
    }

    private List<String> wrap(List<String> lines, int width)
    {
        List<String> out = new ArrayList<>();
        for(String l : lines)
        {
            if(l.isEmpty())
                out.add("");
            else
                out.addAll(fontRenderer.listFormattedStringToWidth(l, width));
        }
        return out;
    }

    private int infoX, infoY, infoW, infoH;

    private void drawInfo()
    {
        infoW = Math.min(vpW - 20, 300);
        infoX = Math.max(vpX + 4, Math.min(lockX, vpX + vpW - infoW - 4));
        infoY = vpY + 2;
        infoH = vpH - 8;
        List<String> lines = infoLines(infoW - 12);
        int maxLines = (infoH - 8) / 10;
        infoScroll = Math.max(0, Math.min(infoScroll, Math.max(0, lines.size() - maxLines)));
        GlStateManager.translate(0, 0, 300);
        Gui.drawRect(infoX - 1, infoY - 1, infoX + infoW + 1, infoY + infoH + 1, 0xFF5F6368);
        Gui.drawRect(infoX, infoY, infoX + infoW, infoY + infoH, 0xFFFFFFFF);
        int y = infoY + 5;
        for(int i = infoScroll; i < lines.size() && i < infoScroll + maxLines; i++)
        {
            fontRenderer.drawString(lines.get(i), infoX + 6, y, TEXT);
            y += 10;
        }
        GlStateManager.translate(0, 0, -300);
    }

    // ---- 输入 ----

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException
    {
        caretStart = System.currentTimeMillis();
        if(info)
        {
            if(!(mouseX >= infoX && mouseX < infoX + infoW && mouseY >= infoY && mouseY < infoY + infoH))
                info = false;
            return;
        }
        if(panel && mouseX >= guiLeft && mouseX < guiLeft + xSize && mouseY >= guiTop && mouseY < guiTop + ySize)
        {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }
        if(button != 0)
            return;
        for(ToolButton t : tools)
        {
            if(t.over(mouseX, mouseY))
            {
                if(t.enabled)
                    toolPressed(t.id);
                return;
            }
        }
        if(claimW > 0 && mouseX >= claimX && mouseX < claimX + claimW && mouseY >= statusY && mouseY < statusY + 11)
        {
            PacketHandler.INSTANCE.sendToServer(new MessageComputerAction(computer.getPos(), MessageComputerAction.CLAIM));
            return;
        }
        if(!screenOn())
            return;
        Browser b = browser();
        if(mouseX >= lockX && mouseX < lockX + lockW && mouseY >= toolbarY && mouseY < toolbarY + 14)
        {
            info = true;
            infoScroll = 0;
            return;
        }
        if(b != null && mouseX >= addrX && mouseX < addrX + addrW && mouseY >= toolbarY && mouseY < toolbarY + 14)
        {
            boolean wasFocused = focus == FOCUS_ADDRESS;
            focus = FOCUS_ADDRESS;
            if(!wasFocused)
                b.address.selectAll();
            else
                b.address.moveTo(charAt(b.address, false, mouseX - addrX - 3), isShiftKeyDown());
            return;
        }
        if(inViewport(mouseX, mouseY))
        {
            focus = FOCUS_NONE;
            RenderedPage p = page();
            if(b == null || p == null)
                return;
            double scroll = Math.min(scroll(), maxScroll(p));
            int n = p.hit((mouseX - vpX) / factor, (mouseY - vpY) / factor + scroll);
            if(n < 0)
                return;
            if(p.document.links.containsKey(n))
            {
                b.openLink(p.document.links.get(n));
                return;
            }
            PageDocument.Control c = p.document.control(n);
            Browser.Entry e = b.current();
            if(c == null || c.disabled || e == null)
                return;
            switch(c.kind)
            {
                case TEXT:
                case PASSWORD:
                case TEXTAREA:
                {
                    focus = c.n;
                    TextEdit t = e.forms.text(c.n);
                    RenderedPage.Rect r = p.rects(c.n).get(0);
                    if(t != null)
                        t.moveTo(charAt(t, c.kind == PageDocument.Kind.PASSWORD, mouseX - gx(r.x) - 3), isShiftKeyDown());
                    break;
                }
                case CHECKBOX:
                case RADIO:
                case SELECT:
                    e.forms.toggle(p.document, c);
                    break;
                case SUBMIT:
                    if(c.form >= 0)
                        b.submitForm(c.form, c.n);
                    break;
                case RESET:
                    if(c.form >= 0)
                        b.resetForm(c.form);
                    break;
                default:
                    break;
            }
            return;
        }
        focus = FOCUS_NONE;
    }

    /** 点在输入框里第几个字前面 */
    private int charAt(TextEdit t, boolean masked, int dx)
    {
        String text = masked ? mask(t.getText()) : t.getText();
        int i = t.viewStart;
        while(i < text.length() && fontRenderer.getStringWidth(text.substring(t.viewStart, i + 1)) <= dx)
            i++;
        return i;
    }

    private void toolPressed(String id)
    {
        Browser b = browser();
        switch(id)
        {
            case "power":
                PacketHandler.INSTANCE.sendToServer(new MessageComputerAction(computer.getPos(), MessageComputerAction.POWER));
                break;
            case "items":
                panel = !panel;
                break;
            case "back":
                if(b != null) b.back();
                break;
            case "forward":
                if(b != null) b.forward();
                break;
            case "reload":
                if(b != null)
                {
                    if(b.status() == Browser.Status.LOADING || b.status() == Browser.Status.WAITING_NETWORK)
                        b.stop();
                    else
                        b.reload();
                }
                break;
            case "home":
                if(b != null) b.home();
                break;
            default:
                break;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state)
    {
        if(panel)
            super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick)
    {
        if(panel)
            super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    public void handleMouseInput() throws IOException
    {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if(wheel != 0)
        {
            if(info)
                infoScroll += wheel > 0 ? -3 : 3;
            else
                scrollBy(wheel > 0 ? -60 : 60);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException
    {
        caretStart = System.currentTimeMillis();
        if(keyCode == Keyboard.KEY_ESCAPE)
        {
            if(info)
            {
                info = false;
                return;
            }
            // 关界面：东西都存在 Browser 里，下次打开照旧
            super.keyTyped(typedChar, keyCode);
            return;
        }
        Browser b = browser();
        if(b != null && screenOn())
        {
            if(focus == FOCUS_ADDRESS)
            {
                if(keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
                {
                    focus = FOCUS_NONE;
                    b.submitAddress();
                    return;
                }
                if(edit(b.address, typedChar, keyCode, false))
                {
                    b.addressEdited = true;
                    return;
                }
            }
            else if(focus >= 0)
            {
                RenderedPage p = b.rendered();
                Browser.Entry e = b.current();
                PageDocument.Control c = p == null ? null : p.document.control(focus);
                if(c != null && e != null)
                {
                    if(keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
                    {
                        // 回车提交所在的表单（用第一个提交按钮）
                        if(c.form >= 0)
                        {
                            int submitter = -1;
                            for(PageDocument.Control o : p.document.controls)
                            {
                                if(o.form == c.form && o.kind == PageDocument.Kind.SUBMIT)
                                {
                                    submitter = o.n;
                                    break;
                                }
                            }
                            focus = FOCUS_NONE;
                            b.submitForm(c.form, submitter);
                        }
                        return;
                    }
                    if(keyCode == Keyboard.KEY_TAB)
                    {
                        focus = nextTextControl(p.document, focus, isShiftKeyDown());
                        return;
                    }
                    TextEdit t = e.forms.text(c.n);
                    if(t != null && edit(t, typedChar, keyCode, c.kind == PageDocument.Kind.PASSWORD))
                        return;
                }
            }
            // 没在打字：快捷键
            if(keyCode == Keyboard.KEY_F5)
            {
                b.reload();
                return;
            }
            if(isCtrlKeyDown() && keyCode == Keyboard.KEY_L)
            {
                focus = FOCUS_ADDRESS;
                b.address.selectAll();
                return;
            }
            if(isAltKeyDown() && keyCode == Keyboard.KEY_LEFT)
            {
                b.back();
                return;
            }
            if(isAltKeyDown() && keyCode == Keyboard.KEY_RIGHT)
            {
                b.forward();
                return;
            }
            if(focus == FOCUS_NONE)
            {
                double page = vpH / factor - 40;
                switch(keyCode)
                {
                    case Keyboard.KEY_UP: scrollBy(-40); return;
                    case Keyboard.KEY_DOWN: scrollBy(40); return;
                    case Keyboard.KEY_PRIOR: scrollBy(-page); return;
                    case Keyboard.KEY_NEXT:
                    case Keyboard.KEY_SPACE: scrollBy(page); return;
                    case Keyboard.KEY_HOME: scrollBy(-1e9); return;
                    case Keyboard.KEY_END: scrollBy(1e9); return;
                    default: break;
                }
            }
            if(focus != FOCUS_NONE)
            {
                // 打字时不让背包键关界面
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    private int nextTextControl(PageDocument doc, int from, boolean back)
    {
        List<PageDocument.Control> list = new ArrayList<>();
        for(PageDocument.Control c : doc.controls)
        {
            if(c.isTextual() && !c.disabled)
                list.add(c);
        }
        if(back)
            Collections.reverse(list);
        boolean seen = false;
        for(PageDocument.Control c : list)
        {
            if(seen)
                return c.n;
            if(c.n == from)
                seen = true;
        }
        return list.isEmpty() ? FOCUS_NONE : list.get(0).n;
    }

    /** 输入框的按键；处理了返回 true */
    private boolean edit(TextEdit t, char c, int key, boolean password)
    {
        boolean ctrl = isCtrlKeyDown();
        boolean shift = isShiftKeyDown();
        if(isKeyComboCtrlA(key))
        {
            t.selectAll();
            return true;
        }
        if(isKeyComboCtrlC(key))
        {
            if(!password && t.hasSelection())
                setClipboardString(t.selected());
            return true;
        }
        if(isKeyComboCtrlX(key))
        {
            if(!password)
            {
                String s = t.cut();
                if(s != null)
                    setClipboardString(s);
            }
            return true;
        }
        if(isKeyComboCtrlV(key))
        {
            t.insert(getClipboardString());
            return true;
        }
        switch(key)
        {
            case Keyboard.KEY_BACK: t.backspace(ctrl); return true;
            case Keyboard.KEY_DELETE: t.delete(ctrl); return true;
            case Keyboard.KEY_LEFT: t.left(ctrl, shift); return true;
            case Keyboard.KEY_RIGHT: t.right(ctrl, shift); return true;
            case Keyboard.KEY_HOME: t.home(shift); return true;
            case Keyboard.KEY_END: t.end(shift); return true;
            default: break;
        }
        if(ChatAllowedCharacters.isAllowedCharacter(c))
        {
            t.insert(String.valueOf(c));
            return true;
        }
        return false;
    }

    // ---- 物品面板（服务端 Container 的槽位画在这里） ----

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
    {
        int l = guiLeft, t = guiTop;
        Gui.drawRect(l - 1, t - 1, l + xSize + 1, t + ySize + 1, 0xFF555555);
        Gui.drawRect(l, t, l + xSize, t + ySize, 0xFFC6C6C6);
        for(Slot s : inventorySlots.inventorySlots)
        {
            if(s == container.getCardSlot())
                continue;
            int x = l + s.xPos - 1, y = t + s.yPos - 1;
            Gui.drawRect(x, y, x + 18, y + 18, 0xFF373737);
            Gui.drawRect(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
            Gui.drawRect(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
        }
        ApplianceGui.draw(l, t, ContainerComputer.CARD_X, ContainerComputer.CARD_Y, container.getCardSlot(), computer.getAppliance());
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
    {
        fontRenderer.drawString(I18n.format("cfm.browser.panel.card"), ContainerComputer.CARD_X + 30, ContainerComputer.CARD_Y + 5, 0xFF404040);
    }

    @Override
    public boolean doesGuiPauseGame()
    {
        return false;
    }
}
