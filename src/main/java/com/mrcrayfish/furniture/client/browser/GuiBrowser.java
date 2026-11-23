package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.CookieJar;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.PixelCanvas;
import com.mrcrayfish.furniture.computer.TypingSync;
import com.mrcrayfish.furniture.computer.UrlInput;
import com.mrcrayfish.furniture.gui.ApplianceGui;
import com.mrcrayfish.furniture.gui.containers.ContainerComputer;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerAction;
import com.mrcrayfish.furniture.network.message.MessageComputerCpu;
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
import java.util.HashMap;
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
    /** Cookie 菜单：0 关着，1 开着，2 问“确定清除吗” */
    private int cookieMenu;
    private int menuX, menuY, menuW, menuH;
    /** 菜单里的按钮：[x, y, w, h]；没有时 w 为 0 */
    private final int[] clearBtn = new int[4], confirmBtn = new int[4], cancelBtn = new int[4];
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
    /** 屏幕缩略图：把看到的页面画小了传给服务端（服主 11-16） */
    @Nullable
    private ThumbUploader thumbs;

    private String hoverText = "";
    /** 画板编辑器（操作者点了网页里的画板时开着；服主 11-15） */
    @Nullable
    private CanvasEditor editor;
    @Nullable
    private Browser.Entry editorEntry;
    /** 页面上每张画板的贴图（按控件编号） */
    private final Map<Integer, CanvasTexture> canvasTextures = new HashMap<>();
    /** 旁观者：页面给的初始图（按控件编号；换页面重建） */
    private final Map<Integer, PixelCanvas> spectatorCanvases = new HashMap<>();
    @Nullable
    private RenderedPage spectatorCanvasPage;
    /** 主页菜单开着 */
    private boolean homeMenu;
    private final int[] setHomeBtn = new int[4], resetHomeBtn = new int[4], offlineBtn = new int[4];

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
        return computer.isSwitchedOn() && computer.getAppliance().isRunning() && !computer.isCpuDead() && !computer.isCrashed();
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

    /** 地址栏最窄这么宽；一行放不下时右边的按钮挪到第二行（窗口窄、界面倍数大时） */
    static final int MIN_ADDRESS_WIDTH = 100;
    /** 工具栏有几行（1 或 2） */
    private int toolbarRows = 1;

    private void layout()
    {
        int margin = 6;
        sx = margin;
        sy = margin;
        sw = width - margin * 2;
        sh = height - margin * 2;
        toolbarY = sy + 4;
        int tbH = 14;

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
        lockX = x + 2;
        lockW = Math.max(14, fontRenderer.getStringWidth(I18n.format("cfm.browser.insecure")) + 6);
        addrX = lockX + lockW + 2;
        // 右边的按钮（从右往左）：物品、电源、调频旋钮（服主 11-17：电源开关旁边，写实际频率与 CPU 温度）、Cookie、菜单
        String[][] right = {{"items", I18n.format("cfm.browser.items")}, {"power", I18n.format("cfm.browser.power")}, {"clock", ""},
                {"cookies", I18n.format("cfm.browser.cookies")}, {"menu", I18n.format("cfm.browser.menu")}};
        int rightW = 0;
        int[] widths = new int[right.length];
        for(int i = 0; i < right.length; i++)
        {
            widths[i] = right[i][0].equals("clock") ? fontRenderer.getStringWidth("2.60 GHz 100.0 °C") + 18 : fontRenderer.getStringWidth(right[i][1]) + 8;
            rightW += widths[i] + (i > 0 ? 2 : 0);
        }
        int rightEdge = sx + sw - 4;
        // 一行放不下（地址栏会比 MIN_ADDRESS_WIDTH 窄、甚至盖住按钮）：右边的按钮放到第二行，地址栏占满第一行
        toolbarRows = rightEdge - rightW - 3 - addrX < MIN_ADDRESS_WIDTH ? 2 : 1;
        int rowY = toolbarRows == 2 ? toolbarY + tbH + 2 : toolbarY;
        int rx = rightEdge;
        ToolButton leftmost = null;
        List<ToolButton> rightTools = new ArrayList<>();
        for(int i = 0; i < right.length; i++)
        {
            ToolButton t = new ToolButton(right[i][0], right[i][1]);
            t.w = widths[i];
            t.x = rx - t.w;
            t.y = rowY;
            t.h = tbH;
            rx = t.x - 2;
            rightTools.add(0, t);
            leftmost = t;
        }
        tools.addAll(rightTools);
        addrW = toolbarRows == 2 ? rightEdge - addrX : Math.max(40, leftmost.x - 3 - addrX);

        vpX = sx + 4;
        vpY = toolbarY + toolbarRows * (tbH + 2) + 1;
        vpW = sw - 8 - 5;
        statusY = sy + sh - 12;
        vpH = statusY - 2 - vpY;
        factor = vpW / (double) PageRenderer.VIEW_WIDTH;
        ScaledResolution sr = new ScaledResolution(mc);
        deviceScale = Math.max(1.0, Math.min(2.5, factor * sr.getScaleFactor()));

        // 物品面板放在右下角（盖在页面上）
        guiLeft = Math.max(0, Math.min(width - xSize, sx + sw - xSize - 6));
        guiTop = Math.max(0, Math.min(height - ySize, statusY - ySize - 2));
    }

    /** 这一秒里浏览器忙的 tick 数、过了几个 tick（报负载用） */
    private int busyTicks;
    private int loadTicks;

    // ---- 调频旋钮（服主 11-17） ----

    @Nullable
    private ToolButton clockButton()
    {
        for(ToolButton t : tools)
        {
            if(t.id.equals("clock"))
                return t;
        }
        return null;
    }

    private void turnClock(int delta)
    {
        PacketHandler.INSTANCE.sendToServer(new MessageComputerCpu(computer.getPos(), delta > 0 ? MessageComputerCpu.UP : MessageComputerCpu.DOWN, 0));
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(net.minecraft.init.SoundEvents.BLOCK_LEVER_CLICK, 1.6f));
    }

    /** 旋钮按钮：左头一个小旋钮（指针按第几档转），右边实际频率、CPU 温度 */
    private void drawClock(ToolButton t)
    {
        net.minecraft.nbt.NBTTagCompound st = computer.getCpuStatus();
        int cx = t.x + 7, cy = t.y + t.h / 2;
        int r = 5;
        for(int dy = -r; dy <= r; dy++)
        {
            int w = (int) Math.round(Math.sqrt(r * r - dy * dy));
            Gui.drawRect(cx - w, cy + dy, cx + w + 1, cy + dy + 1, 0xFF3C4043);
        }
        if(st == null)
            return;
        int n = Math.max(1, st.getInteger("Cnt"));
        double f = n <= 1 ? 0.5 : st.getInteger("Idx") / (double) (n - 1);
        double a = Math.toRadians(-135 + 270 * f);
        for(int i = 1; i <= r - 1; i++)
        {
            int px = cx + (int) Math.round(Math.sin(a) * i), py = cy - (int) Math.round(Math.cos(a) * i);
            Gui.drawRect(px, py, px + 1, py + 1, 0xFFFFFFFF);
        }
        String text;
        int color;
        if(st.getBoolean("Dead"))
        {
            text = I18n.format("cfm.browser.cpu.dead");
            color = 0xFFB3261E;
        }
        else
        {
            text = com.mrcrayfish.furniture.computer.ComputerCpu.formatFreq(st.getInteger("Act")) + " "
                    + String.format(java.util.Locale.ROOT, "%.1f °C", st.getDouble("Temp"));
            double heat = st.getDouble("Heat");
            color = heat >= 0.9 ? 0xFFB3261E : heat >= 0.75 || st.getBoolean("Capped") ? 0xFFB06000 : TEXT;
        }
        fontRenderer.drawString(text, t.x + 15, t.y + (t.h - 8) / 2, t.enabled ? color : 0xFF9AA0A6);
        // 底下一道：最近 1 分钟的热量
        double heat = Math.max(0, Math.min(1, st.getDouble("Heat")));
        int bw = t.w - 16;
        Gui.drawRect(t.x + 14, t.y + t.h - 2, t.x + 14 + (int) (bw * heat), t.y + t.h - 1, heat >= 0.9 ? 0xFFD93025 : heat >= 0.75 ? 0xFFF29900 : 0xFF34A853);
    }

    private List<String> clockTooltip()
    {
        List<String> l = new ArrayList<>();
        net.minecraft.nbt.NBTTagCompound st = computer.getCpuStatus();
        l.add(I18n.format("cfm.browser.cpu.title"));
        if(st == null)
            return l;
        java.util.function.IntFunction<String> f = com.mrcrayfish.furniture.computer.ComputerCpu::formatFreq;
        l.add(I18n.format("cfm.browser.cpu.setting", f.apply(st.getInteger("Set")), f.apply(st.getInteger("Std")), f.apply(st.getInteger("Min")), f.apply(st.getInteger("Max"))));
        if(st.getBoolean("Dead"))
            l.add("\u00a7c" + I18n.format("cfm.browser.cpu.dead.tip"));
        else
        {
            l.add(I18n.format("cfm.browser.cpu.actual", f.apply(st.getInteger("Act"))));
            if(st.getBoolean("Capped"))
                l.add("\u00a76" + I18n.format(st.getBoolean("Nominal") ? "cfm.browser.cpu.nominal" : "cfm.browser.cpu.capped", f.apply(st.getInteger("Act"))));
        }
        l.add(I18n.format("cfm.browser.cpu.temp", String.format(java.util.Locale.ROOT, "%.1f", st.getDouble("Temp"))));
        l.add(I18n.format("cfm.browser.cpu.heat", Math.round(st.getDouble("Heat") * 100)));
        if(st.getLong("Life") >= 0 && !st.getBoolean("Dead"))
            l.add(I18n.format("cfm.browser.cpu.life", st.getLong("Life")));
        if(st.getDouble("Rate") > 0)
            l.add("\u00a76" + I18n.format("cfm.browser.cpu.faults"));
        if(st.getBoolean("Crashed"))
            l.add("\u00a7c" + I18n.format("cfm.browser.cpu.crashed"));
        byte trip = st.getByte("Trip");
        if(trip != 0)
            l.add("\u00a7c" + I18n.format("cfm.browser.cpu.trip." + trip));
        l.add("\u00a77" + I18n.format(isOperator() ? "cfm.browser.cpu.howto" : "cfm.browser.cpu.not_operator"));
        return l;
    }

    /** 上一帧是不是操作者（判断“被换下来”） */
    private boolean wasOperator;
    /** 上一 tick 屏幕亮不亮（关机、没电后再亮时把页面重新传给服务端） */
    private boolean wasOn = true;

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
        // 不操作了：手上这份 Cookie 丢掉（下次成为操作者时服务端再发；服主 11-15）
        ComputerViews.view(computer.getPos()).cookies = null;
        cookieMenu = 0;
        homeMenu = false;
        if(thumbs != null)
        {
            thumbs.close();
            thumbs = null;
        }
        // 画板：只关编辑器、丢贴图，画的东西在表单里留着
        closeEditor();
        for(CanvasTexture t : canvasTextures.values())
            t.dispose();
        canvasTextures.clear();
    }

    private void closeEditor()
    {
        if(editor != null)
            editor.dispose();
        editor = null;
        editorEntry = null;
    }

    /** 编辑器还该不该开着：还是操作者、开着机、还在那一页、那张画板还在 */
    private boolean editorValid()
    {
        if(editor == null || editor.closed)
            return false;
        Browser b = browser();
        if(b == null || !screenOn() || b.current() != editorEntry || b.rendered() == null)
            return false;
        return editorEntry.forms.canvas(editor.control) == editor.canvas;
    }

    @Override
    public void updateScreen()
    {
        super.updateScreen();
        Browser b = browser();
        boolean on = screenOn();
        if(b != null && on && !wasOn)
        {
            // 关机、没电时服务端清掉了页面（界面一直开着，本地还是原来那页）：重新传上去
            b.resync();
        }
        wasOn = on;
        if(b != null && on)
        {
            b.setScale(deviceScale);
            b.ensureStarted();
            b.tick();
        }
        // 负载（服主 11-17）：浏览器这一秒里有几成时间在忙（取页面、渲染、排版），每秒报给服务端算发热
        if(b != null && on)
        {
            if(b.status() != Browser.Status.IDLE || b.isRenderPending() || b.layoutProgress() >= 0)
                busyTicks++;
            if(++loadTicks >= 20)
            {
                PacketHandler.INSTANCE.sendToServer(new MessageComputerCpu(computer.getPos(), MessageComputerCpu.LOAD, busyTicks * 1000 / loadTicks));
                busyTicks = 0;
                loadTicks = 0;
            }
        }
        else
        {
            busyTicks = 0;
            loadTicks = 0;
        }
        boolean operator = isOperator();
        if(!operator && wasOperator)
        {
            // 旁观者拿不到 Cookie：原来是操作者、被换下来时手上那份丢掉。
            // 只在“从操作者变成不是”时丢：服务端交 Cookie 的包可能比方块实体里“操作者是你”的同步先到，那时还不能丢
            ComputerViews.view(computer.getPos()).cookies = null;
        }
        wasOperator = operator;
        if(!operator)
        {
            cookieMenu = 0;
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
        updateThumbnail(b, operator);
    }

    /** 屏幕缩略图（服主 11-16）：操作者按自己传上去的那一页和报上去的滚动位置，旁观者按服务端发来的 */
    private void updateThumbnail(@Nullable Browser b, boolean operator)
    {
        if(thumbs == null)
            thumbs = new ThumbUploader(computer.getPos());
        RenderedPage p = page();
        int pageId, scroll;
        if(operator)
        {
            pageId = b == null ? -1 : b.syncedPageId();
            scroll = b == null ? -1 : b.syncedScroll();
        }
        else
        {
            ComputerViews.View v = ComputerViews.view(computer.getPos());
            pageId = v.page == null ? -1 : v.pageId;
            scroll = v.scroll;
        }
        thumbs.tick(p, pageId, scroll, computer.getScreenSpec(), System.currentTimeMillis());
    }

    /** 旁观者用自己的玩家临时地址取页面里的图（服务器只放行这一页图片所在的主机），规则同操作者（{@link ImageFetcher}） */
    private void loadSpectatorImages(int req, List<String> urls)
    {
        ClientNic.Session s = ClientNic.player();
        PageSnapshot snap = spectatorSnapshot;
        if(s == null || s.isClosed() || snap == null)
            return;
        // 旁观者没有 Cookie（服主 11-15）
        ImageFetcher.fetch(s, urls, () -> req == spectatorRender && mc.currentScreen == this, null, got ->
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
        statusOverflow = false;
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
            Gui.drawRect(sx + 2, toolbarY - 2, sx + sw - 2, toolbarY + toolbarRows * 16, TOOLBAR);
            Gui.drawRect(vpX, vpY, vpX + vpW + 5, vpY + vpH, 0xFFFFFFFF);
            drawPage(p, mouseX, mouseY);
            if(editor != null && !editorValid())
                closeEditor();
            if(editor != null)
                editor.draw(vpX, vpY, vpW + 5, vpH, mouseX, mouseY, zLevel);
            drawToolbar(mouseX, mouseY);
            drawStatus(mouseX, mouseY);
        }
        else
        {
            Gui.drawRect(sx + 2, sy + 2, sx + sw - 2, sy + sh - 2, 0xFF000000);
            // CPU 报废了：有电也没有反应（服主 11-16 的家具电脑主板）
            // 过热断电、烧坏、寿命用完、死机（服主 11-17）各说一句
            byte trip = computer.getCpuTrip();
            String msg = I18n.format(!computer.isSwitchedOn() ? (trip == com.mrcrayfish.furniture.computer.CpuRuntime.TRIP_OVERHEAT ? "cfm.browser.overheat" : "cfm.browser.off")
                    : !computer.getAppliance().isRunning() ? "cfm.browser.no_power"
                    : computer.isCpuDead() ? (trip == com.mrcrayfish.furniture.computer.CpuRuntime.TRIP_BURNT ? "cfm.browser.cpu_burnt"
                    : trip == com.mrcrayfish.furniture.computer.CpuRuntime.TRIP_WORN_OUT ? "cfm.browser.cpu_worn_out" : "cfm.browser.no_response")
                    : computer.isCrashed() ? "cfm.browser.crashed" : "cfm.browser.no_response");
            drawCenteredString(fontRenderer, msg, sx + sw / 2, sy + sh / 2 - 4, computer.isCrashed() && computer.isSwitchedOn() ? 0xFF6EA8FF : 0xFF8A8A8A);
            for(ToolButton t : tools)
            {
                t.visible = t.id.equals("power") || t.id.equals("items") || t.id.equals("clock");
                if(t.id.equals("clock"))
                    t.enabled = isOperator() && computer.getCpuStatus() != null;
            }
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
            else if(!drawCpuHint(mouseX, mouseY))
                drawPeripheralHint(mouseX, mouseY);
        }
        // 状态栏放不下的链接网址、下拉框选项：悬停提示里写全（折行）
        if(on && statusOverflow && !panel && !homeMenu && cookieMenu == 0 && !info && editor == null)
            drawHoveringText(fontRenderer.listFormattedStringToWidth(hoverText, Math.max(80, sw / 2)), mouseX, mouseY);
        ToolButton clockTip = clockButton();
        if(clockTip != null && clockTip.visible && clockTip.over(mouseX, mouseY) && !panel && !homeMenu && cookieMenu == 0 && !info)
            drawHoveringText(clockTooltip(), mouseX, mouseY);
        if(homeMenu && on && browser() != null)
        {
            drawHomeMenu(mouseX, mouseY);
        }
        else if(cookieMenu > 0 && on)
        {
            drawCookieMenu(mouseX, mouseY);
        }
        else if(info && on)
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
                case "clock": t.enabled = op && computer.getCpuStatus() != null; break;
                case "cookies": t.visible = op; t.enabled = op; break;
                case "menu": t.visible = op; t.enabled = op; break;
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
        // 正在排版（CPU 比标准频率慢，服主 11-16）：地址栏右头写“正在排版……”，底下一道进度
        int layout = op ? b.layoutProgress() : -1;
        String layoutLabel = layout >= 0 ? I18n.format("cfm.browser.status.layout") : "";
        int layoutW = layout >= 0 ? fontRenderer.getStringWidth(layoutLabel) + 6 : 0;
        if(op)
        {
            drawTextEdit(b.address, addrX + 3, toolbarY + 3, addrW - 6 - layoutW, focused, false, TEXT);
        }
        else
        {
            // 旁观：操作者正在地址栏里打的字（服主 11-14），没有就显示当前页面的网址
            TypingSync.Typed typed = ComputerViews.view(computer.getPos()).typed.get(TypingSync.ADDRESS);
            boolean typing = typed != null && !typed.text.isEmpty() && !typed.text.equals(UrlInput.display(url));
            String shown = TextFit.fit(typing ? typed.text : UrlInput.display(url), addrW - 6, fontRenderer::getStringWidth);
            fontRenderer.drawString(shown, addrX + 3, toolbarY + 3, typing ? TEXT : TEXT_DIM);
        }
        if(layout >= 0 && layoutW < addrW - 20)
        {
            int lx = addrX + addrW - 1 - layoutW;
            Gui.drawRect(lx, toolbarY + 1, addrX + addrW - 1, toolbarY + 13, 0xFFE8F0FE);
            fontRenderer.drawString(layoutLabel, lx + 3, toolbarY + 3, 0xFF1A73E8);
            Gui.drawRect(addrX + 1, toolbarY + 12, addrX + 1 + (addrW - 2) * layout / 100, toolbarY + 13, 0xFF1A73E8);
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
            if((t.id.equals("items") && panel) || (t.id.equals("cookies") && cookieMenu > 0) || (t.id.equals("menu") && homeMenu))
                bg = 0xFFAECBFA;
            Gui.drawRect(t.x, t.y, t.x + t.w, t.y + t.h, 0xFF80868B);
            Gui.drawRect(t.x + 1, t.y + 1, t.x + t.w - 1, t.y + t.h - 1, bg);
            if(t.id.equals("clock"))
            {
                drawClock(t);
                continue;
            }
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
        t.viewStart = TextScroll.viewStart(text, cursor, t.viewStart, w - 2, fontRenderer::getStringWidth);
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
        if(left.isEmpty() && b != null && b.layoutProgress() >= 0)
            left = I18n.format("cfm.browser.status.layout");
        if(left.isEmpty() && b != null && b.status() != Browser.Status.IDLE)
            left = b.statusText();
        if(left.isEmpty() && b != null && b.status() == Browser.Status.IDLE)
            left = b.notice();
        RenderedPage p = page();
        if(left.isEmpty() && p != null && p.truncated())
            left = I18n.format("cfm.browser.truncated", PageRenderer.MAX_HEIGHT);
        int roleLeft = drawRole(mouseX, mouseY);
        // 状态栏左边：放到右边“你在操作”等字的左边为止，放不下加省略号；指着的链接、下拉框放不下时另有悬停提示（见 drawScreen）
        int room = Math.max(0, roleLeft - 8 - (sx + 5));
        statusOverflow = !hoverText.isEmpty() && left.equals(hoverText) && TextFit.overflows(left, room, fontRenderer::getStringWidth);
        fontRenderer.drawString(TextFit.fit(left, room, fontRenderer::getStringWidth), sx + 5, statusY + 2, 0xFFBDC1C6);
    }

    /** 状态栏里指着的东西（链接、下拉框的选项）没写全 */
    private boolean statusOverflow;

    private int claimX, claimW;

    /** @return 角色文字（及“接管”按钮）的左边 */
    private int drawRole(int mouseX, int mouseY)
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
        // 操作者名字很长、窗口很窄时：角色文字最多占状态栏的一半
        int maxRole = Math.max(40, sw / 2);
        if(fontRenderer.getStringWidth(role) > maxRole)
        {
            int right = rx + fontRenderer.getStringWidth(role);
            role = TextFit.fit(role, maxRole, fontRenderer::getStringWidth);
            rx = right - fontRenderer.getStringWidth(role);
        }
        fontRenderer.drawString(role, rx, statusY + 2, 0xFFE8EAED);
        return rx;
    }

    private void drawPage(@Nullable RenderedPage p, int mouseX, int mouseY)
    {
        if(p == null || textures == null)
        {
            Browser b = browser();
            String msg;
            if(b != null && b.layoutProgress() >= 0)
                msg = I18n.format("cfm.browser.status.layout");
            else if(b != null)
                msg = b.status() == Browser.Status.IDLE && b.current() != null && b.rendered() == null && !b.isRenderPending()
                        ? I18n.format("cfm.browser.render_failed") : I18n.format("cfm.browser.status.rendering");
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
                case CANVAS:
                {
                    drawCanvasPreview(p, c, forms, x1 + 1, y1 + 1, x2 - 1, y2 - 1);
                    if(b != null && !c.disabled && mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2 && editor == null && inViewport(mouseX, mouseY))
                        hoverText = I18n.format("cfm.browser.canvas.hint", c.canvasWidth, c.canvasHeight);
                    break;
                }
                case SELECT:
                {
                    int sel = forms != null ? forms.selected(c.n) : c.selected;
                    String label = c.optionLabels.isEmpty() ? "" : c.optionLabels.get(Math.min(sel, c.optionLabels.size() - 1));
                    Gui.drawRect(x1 + 1, y1 + 1, x2 - 1, y2 - 1, FIELD);
                    // 选项太长：截断加省略号，指着时状态栏（放不下时悬停提示）里显示全文
                    fontRenderer.drawString(TextFit.fit(label, x2 - x1 - 14, fontRenderer::getStringWidth), x1 + 3, ty, TEXT);
                    fontRenderer.drawString("▾", x2 - 9, ty, TEXT_DIM);
                    if(TextFit.overflows(label, x2 - x1 - 14, fontRenderer::getStringWidth) && mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2
                            && inViewport(mouseX, mouseY))
                        hoverText = label;
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

    /** 画板在页面上的样子：操作者看自己画的，旁观者看操作者同步来的（没有就是页面给的初始图） */
    private void drawCanvasPreview(RenderedPage p, PageDocument.Control c, @Nullable FormState forms, int x1, int y1, int x2, int y2)
    {
        if(x2 <= x1 || y2 <= y1)
            return;
        CanvasTexture tex = canvasTextures.computeIfAbsent(c.n, k -> new CanvasTexture());
        if(forms != null)
        {
            PixelCanvas pc = forms.canvas(c.n);
            if(pc == null)
                return;
            tex.update(pc, pc.width, pc.height, pc.version(), pc.pixels());
        }
        else
        {
            ComputerViews.CanvasView v = ComputerViews.view(computer.getPos()).canvases.get(c.n);
            if(v != null && v.width == c.canvasWidth && v.height == c.canvasHeight)
            {
                tex.update(v, v.width, v.height, v.version, v.pixels);
            }
            else
            {
                if(spectatorCanvasPage != p)
                {
                    spectatorCanvasPage = p;
                    spectatorCanvases.clear();
                }
                PixelCanvas init = spectatorCanvases.computeIfAbsent(c.n, k -> PixelCanvas.fromValue(c.canvasWidth, c.canvasHeight, c.value));
                tex.update(init, init.width, init.height, init.version(), init.pixels());
            }
        }
        tex.draw(x1, y1, x2 - x1, y2 - y1, zLevel);
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

    // ---- Cookie 菜单（服主 11-15：浏览器里清除这台电脑的 Cookie） ----

    private List<String> cookieMenuLines(int width)
    {
        List<String> out = new ArrayList<>();
        out.add("§l" + I18n.format("cfm.browser.cookies.title"));
        CookieJar jar = ComputerViews.view(computer.getPos()).cookies;
        if(jar == null)
            out.add(I18n.format("cfm.browser.cookies.loading"));
        else
        {
            jar.purge(System.currentTimeMillis());
            out.add(jar.isEmpty() ? I18n.format("cfm.browser.cookies.none") : I18n.format("cfm.browser.cookies.count", jar.size(), jar.siteCount()));
        }
        out.add("");
        out.add("§7" + I18n.format("cfm.browser.cookies.note"));
        if(cookieMenu == 2)
        {
            out.add("");
            out.add("§c" + I18n.format("cfm.browser.cookies.confirm"));
        }
        return wrap(out, width);
    }

    private void drawCookieMenu(int mouseX, int mouseY)
    {
        menuW = Math.min(vpW - 20, 240);
        menuX = Math.max(vpX + 4, Math.min(sx + sw - 4 - menuW, vpX + vpW - menuW - 4));
        menuY = vpY + 2;
        List<String> lines = cookieMenuLines(menuW - 12);
        menuH = 8 + lines.size() * 10 + 6 + 14 + 6;
        GlStateManager.translate(0, 0, 300);
        Gui.drawRect(menuX - 1, menuY - 1, menuX + menuW + 1, menuY + menuH + 1, 0xFF5F6368);
        Gui.drawRect(menuX, menuY, menuX + menuW, menuY + menuH, 0xFFFFFFFF);
        int y = menuY + 5;
        for(String l : lines)
        {
            fontRenderer.drawString(l, menuX + 6, y, TEXT);
            y += 10;
        }
        y += 6;
        clearBtn[2] = confirmBtn[2] = cancelBtn[2] = 0;
        if(cookieMenu == 1)
        {
            boolean empty = ComputerViews.view(computer.getPos()).cookies == null || ComputerViews.view(computer.getPos()).cookies.isEmpty();
            String label = I18n.format("cfm.browser.cookies.clear");
            setRect(clearBtn, menuX + 6, y, fontRenderer.getStringWidth(label) + 10, 14);
            drawMenuButton(clearBtn, label, !empty, false, mouseX, mouseY);
        }
        else
        {
            String yes = I18n.format("cfm.browser.cookies.clear_yes");
            String no = I18n.format("cfm.browser.cookies.clear_no");
            setRect(confirmBtn, menuX + 6, y, fontRenderer.getStringWidth(yes) + 10, 14);
            setRect(cancelBtn, confirmBtn[0] + confirmBtn[2] + 6, y, fontRenderer.getStringWidth(no) + 10, 14);
            drawMenuButton(confirmBtn, yes, true, true, mouseX, mouseY);
            drawMenuButton(cancelBtn, no, true, false, mouseX, mouseY);
        }
        GlStateManager.translate(0, 0, -300);
    }

    private static void setRect(int[] r, int x, int y, int w, int h)
    {
        r[0] = x;
        r[1] = y;
        r[2] = w;
        r[3] = h;
    }

    private static boolean inRect(int[] r, int mx, int my)
    {
        return r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void drawMenuButton(int[] r, String label, boolean enabled, boolean danger, int mouseX, int mouseY)
    {
        boolean hover = enabled && inRect(r, mouseX, mouseY);
        int bg = !enabled ? 0xFFD0D3D7 : danger ? (hover ? 0xFFE8817A : 0xFFD93025) : (hover ? 0xFFC9D7F2 : 0xFFF8F9FA);
        Gui.drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF80868B);
        Gui.drawRect(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, bg);
        fontRenderer.drawString(label, r[0] + 5, r[1] + 3, !enabled ? 0xFF9AA0A6 : danger ? 0xFFFFFFFF : TEXT);
    }

    /** 菜单开着时的点击：点在按钮上照做，点在菜单外关掉菜单 */
    private void cookieMenuClicked(int mouseX, int mouseY)
    {
        CookieJar jar = ComputerViews.view(computer.getPos()).cookies;
        if(cookieMenu == 1 && inRect(clearBtn, mouseX, mouseY))
        {
            if(jar != null && !jar.isEmpty())
                cookieMenu = 2;
            return;
        }
        if(cookieMenu == 2 && inRect(confirmBtn, mouseX, mouseY))
        {
            // 玩家确认过了才清（用户的数据不能自动丢掉）
            if(jar != null)
                jar.clear();
            PacketHandler.INSTANCE.sendToServer(new MessageComputerAction(computer.getPos(), MessageComputerAction.CLEAR_COOKIES));
            cookieMenu = 1;
            return;
        }
        if(cookieMenu == 2 && inRect(cancelBtn, mouseX, mouseY))
        {
            cookieMenu = 1;
            return;
        }
        if(!(mouseX >= menuX && mouseX < menuX + menuW && mouseY >= menuY && mouseY < menuY + menuH))
            cookieMenu = 0;
    }

    // ---- 主页菜单（服主 11-15：设为这台电脑的主页、恢复默认、离线主页） ----

    private void drawHomeMenu(int mouseX, int mouseY)
    {
        Browser b = browser();
        ComputerViews.View v = ComputerViews.view(computer.getPos());
        menuW = Math.min(vpW - 20, 260);
        menuX = Math.max(vpX + 4, vpX + vpW - menuW - 4);
        menuY = vpY + 2;
        List<String> lines = new ArrayList<>();
        lines.add("§l" + I18n.format("cfm.browser.home_menu.title"));
        lines.add(I18n.format("cfm.browser.home_menu.current", UrlInput.display(b.homeUrl())));
        lines.add("§7" + I18n.format(v.home != null ? "cfm.browser.home_menu.computer" : "cfm.browser.home_menu.default"));
        lines = wrap(lines, menuW - 12);
        menuH = 8 + lines.size() * 10 + 6 + 3 * 16 + 4;
        GlStateManager.translate(0, 0, 300);
        Gui.drawRect(menuX - 1, menuY - 1, menuX + menuW + 1, menuY + menuH + 1, 0xFF5F6368);
        Gui.drawRect(menuX, menuY, menuX + menuW, menuY + menuH, 0xFFFFFFFF);
        int y = menuY + 5;
        for(String l : lines)
        {
            fontRenderer.drawString(l, menuX + 6, y, TEXT);
            y += 10;
        }
        y += 6;
        String set = I18n.format("cfm.browser.home_menu.set");
        String reset = I18n.format("cfm.browser.home_menu.reset");
        String offline = I18n.format("cfm.browser.home_menu.offline");
        setRect(setHomeBtn, menuX + 6, y, fontRenderer.getStringWidth(set) + 10, 14);
        drawMenuButton(setHomeBtn, set, b.canSetHome(), false, mouseX, mouseY);
        setRect(resetHomeBtn, menuX + 6, y + 16, fontRenderer.getStringWidth(reset) + 10, 14);
        drawMenuButton(resetHomeBtn, reset, v.home != null, false, mouseX, mouseY);
        setRect(offlineBtn, menuX + 6, y + 32, fontRenderer.getStringWidth(offline) + 10, 14);
        drawMenuButton(offlineBtn, offline, true, false, mouseX, mouseY);
        GlStateManager.translate(0, 0, -300);
    }

    private void homeMenuClicked(int mouseX, int mouseY)
    {
        Browser b = browser();
        if(b == null)
        {
            homeMenu = false;
            return;
        }
        if(inRect(setHomeBtn, mouseX, mouseY))
        {
            if(b.canSetHome())
                b.setHomeToCurrent();
            return;
        }
        if(inRect(resetHomeBtn, mouseX, mouseY))
        {
            if(ComputerViews.view(computer.getPos()).home != null)
                b.resetHome();
            return;
        }
        if(inRect(offlineBtn, mouseX, mouseY))
        {
            homeMenu = false;
            b.openOfflineHome();
            return;
        }
        if(!(mouseX >= menuX && mouseX < menuX + menuW && mouseY >= menuY && mouseY < menuY + menuH))
            homeMenu = false;
    }

    // ---- 输入 ----

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException
    {
        caretStart = System.currentTimeMillis();
        ToolButton clock = clockButton();
        if(clock != null && clock.visible && clock.over(mouseX, mouseY) && (button == 0 || button == 1) && !(panel && mouseX >= guiLeft
                && mouseX < guiLeft + xSize && mouseY >= guiTop && mouseY < guiTop + ySize))
        {
            if(clock.enabled)
                turnClock(button == 0 ? 1 : -1);
            return;
        }
        if(editor != null && editorValid() && inViewport(mouseX, mouseY))
        {
            editor.mouseClicked(mouseX, mouseY, button);
            if(editor.closed)
                closeEditor();
            return;
        }
        if(homeMenu)
        {
            boolean onButton = false;
            for(ToolButton t : tools)
                onButton |= t.id.equals("menu") && t.over(mouseX, mouseY);
            if(onButton)
                homeMenu = false;
            else
                homeMenuClicked(mouseX, mouseY);
            return;
        }
        if(cookieMenu > 0)
        {
            boolean onButton = false;
            for(ToolButton t : tools)
                onButton |= t.id.equals("cookies") && t.over(mouseX, mouseY);
            if(onButton)
                cookieMenu = 0;
            else
                cookieMenuClicked(mouseX, mouseY);
            return;
        }
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
                case CANVAS:
                {
                    // 点画板：打开编辑器（盖在页面上，放大编辑）
                    PixelCanvas pc = e.forms.canvas(c.n);
                    if(pc != null)
                    {
                        closeEditor();
                        editor = new CanvasEditor(c.n, pc, fontRenderer, vpW + 5, vpH);
                        editorEntry = e;
                        homeMenu = false;
                        cookieMenu = 0;
                    }
                    break;
                }
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
            case "cookies":
                cookieMenu = cookieMenu == 0 ? 1 : 0;
                homeMenu = false;
                info = false;
                break;
            case "menu":
                homeMenu = !homeMenu;
                cookieMenu = 0;
                info = false;
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
        if(editor != null && editorValid())
        {
            editor.mouseReleased(mouseX, mouseY);
            return;
        }
        if(panel)
            super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick)
    {
        if(editor != null && editorValid())
        {
            if(clickedMouseButton == 0)
                editor.mouseDragged(mouseX, mouseY);
            return;
        }
        if(panel)
            super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    public void handleMouseInput() throws IOException
    {
        int wheel = Mouse.getEventDWheel();
        ToolButton clock = clockButton();
        if(wheel != 0 && clock != null && clock.visible && clock.enabled)
        {
            int mx = Mouse.getEventX() * width / mc.displayWidth;
            int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if(clock.over(mx, my))
            {
                turnClock(wheel > 0 ? 1 : -1);
                return;
            }
        }
        super.handleMouseInput();
        if(wheel != 0)
        {
            if(editor != null && editorValid())
                editor.wheel(wheel > 0 ? 1 : -1);
            else if(info)
                infoScroll += wheel > 0 ? -3 : 3;
            else
                scrollBy(wheel > 0 ? -60 : 60);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException
    {
        caretStart = System.currentTimeMillis();
        if(editor != null && editorValid() && editor.keyTyped(keyCode))
        {
            if(editor.closed)
                closeEditor();
            return;
        }
        if(keyCode == Keyboard.KEY_ESCAPE)
        {
            if(homeMenu)
            {
                homeMenu = false;
                return;
            }
            if(cookieMenu > 0)
            {
                cookieMenu = 0;
                return;
            }
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
        fontRenderer.drawString(I18n.format("cfm.browser.panel.peripheral"), ContainerComputer.PERIPHERAL_X + 20, ContainerComputer.PERIPHERAL_Y + 5, 0xFF404040);
        // 这台电脑的 CPU（服主 11-16）
        fontRenderer.drawString(fontRenderer.trimStringToWidth(com.mrcrayfish.furniture.blocks.BlockComputer.cpuLine(computer.getBoard(), true, computer.getCpu().clockKHz), xSize - 16),
                8, ContainerComputer.CPU_Y, 0xFF404040);
    }

    /** 指着 CPU 那一行：完整的一句（被最大频率封顶时写明标准频率） */
    private boolean drawCpuHint(int mouseX, int mouseY)
    {
        int y = guiTop + ContainerComputer.CPU_Y - 1;
        if(mouseX < guiLeft + 8 || mouseX >= guiLeft + xSize - 8 || mouseY < y || mouseY >= y + 10)
            return false;
        drawHoveringText(java.util.Collections.singletonList(com.mrcrayfish.furniture.blocks.BlockComputer.cpuLine(computer.getBoard(), false, computer.getCpu().clockKHz)), mouseX, mouseY);
        return true;
    }

    /** 外设槽里插着用不上的外设（家具电脑只用得上定位模块）：提示里说一句 */
    @Override
    public List<String> getItemToolTip(ItemStack stack)
    {
        List<String> lines = super.getItemToolTip(stack);
        if(getSlotUnderMouse() == container.getPeripheralSlot() && !moe.qingu.youran.api.peripheral.Peripherals.hasCapability(
                java.util.Collections.singletonList(stack), moe.qingu.youran.api.peripheral.Peripherals.CAP_LOCATION))
            lines.add(net.minecraft.util.text.TextFormatting.GRAY + I18n.format("cfm.browser.panel.peripheral.unused"));
        return lines;
    }

    /** 指着空的外设槽：说明放什么、谁能放 */
    private void drawPeripheralHint(int mouseX, int mouseY)
    {
        int x = guiLeft + ContainerComputer.PERIPHERAL_X - 1, y = guiTop + ContainerComputer.PERIPHERAL_Y - 1;
        if(container.getPeripheralSlot().getHasStack() || mouseX < x || mouseX >= x + 18 || mouseY < y || mouseY >= y + 18)
            return;
        List<String> lines = new ArrayList<>();
        lines.add(I18n.format("cfm.browser.panel.peripheral.hint"));
        lines.add(net.minecraft.util.text.TextFormatting.GRAY + I18n.format(computer.isOperator(mc.player)
                ? "cfm.browser.panel.peripheral.operator" : "cfm.browser.panel.peripheral.not_operator"));
        drawHoveringText(lines, mouseX, mouseY);
    }

    @Override
    public boolean doesGuiPauseGame()
    {
        return false;
    }
}
