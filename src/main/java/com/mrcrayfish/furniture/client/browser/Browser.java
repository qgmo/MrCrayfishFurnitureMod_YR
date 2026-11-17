package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.client.MediaDecoding;
import com.mrcrayfish.furniture.computer.BrowserHistory;
import com.mrcrayfish.furniture.computer.CanvasSync;
import com.mrcrayfish.furniture.computer.HomePage;
import com.mrcrayfish.furniture.computer.PixelCanvas;
import com.mrcrayfish.furniture.computer.CookieJar;
import com.mrcrayfish.furniture.computer.FormEncoding;
import com.mrcrayfish.furniture.computer.PageLimits;
import com.mrcrayfish.furniture.computer.PageParser;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.PageUploads;
import com.mrcrayfish.furniture.computer.TypingSync;
import com.mrcrayfish.furniture.computer.UrlInput;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerCanvas;
import com.mrcrayfish.furniture.network.message.MessageComputerCookies;
import com.mrcrayfish.furniture.network.message.MessageComputerHome;
import com.mrcrayfish.furniture.network.message.MessageComputerHistory;
import com.mrcrayfish.furniture.network.message.MessageComputerPage;
import com.mrcrayfish.furniture.network.message.MessageComputerScroll;
import com.mrcrayfish.furniture.network.message.MessageComputerTyping;
import moe.qingu.youran.api.network.ClientNic;
import moe.qingu.youran.api.network.location.ClientLocation;
import moe.qingu.youran.api.network.location.LocationResult;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetworkException;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 一台电脑上的浏览器（操作者的客户端）：地址栏、历史（前进后退）、取页面（经 YouranMod 的客户端网卡，协议栈在本地）、
 * 表单提交、图片、渲染、把当前页面上传给服务端给旁观者看。
 * <p>
 * 状态（地址栏、历史、表单里填的东西、滚动位置）按电脑存在 {@link ComputerViews} 里，关界面、断电、断线、服务器拒绝都不丢；
 * 只有表单提交成功后才清空那个表单。所有方法都在客户端主线程上调用；网络回调转回主线程。
 */
public final class Browser
{
    static final int MAX_HISTORY = 20;
    static final long PAGE_TIMEOUT_MS = 20_000;
    static final long IMAGE_TIMEOUT_MS = 10_000;
    static final long NETWORK_WAIT_MS = 10_000;
    static final int MAX_REDIRECTS = 5;
    static final int MAX_IMAGE_BYTES = 256 * 1024;
    static final int MAX_IMAGE_SIDE = 1024;

    public enum Status
    {
        IDLE, WAITING_NETWORK, LOADING, RENDERING
    }

    /** 一条历史记录 */
    public static final class Entry
    {
        public final String url;
        /** 页面原文；会话恢复出来、还没加载的条目为 null（点到时再加载） */
        @Nullable
        public final byte[] html;
        public final PageParser.Mode mode;
        @Nullable
        public final PageSnapshot.Connection connection;
        public final Map<String, byte[]> images;
        public final boolean builtin;
        public final FormState forms = new FormState();
        public int scroll;
        /**
         * 这台电脑排版完这一页的时刻（毫秒；0 为还没排过）与多等的总时长（服主 11-16：处理速度按 CPU 实际频率 ÷ 2.0 GHz）。
         * 第一次显示时定下来，之后重开界面、换界面大小重画、前进后退回来都不再等
         */
        long layoutReadyAt;
        long layoutExtra;

        /** 会话恢复出来的条目：只有网址 */
        static Entry placeholder(String url)
        {
            return new Entry(url, null, PageParser.Mode.XHTML, null, Collections.<String, byte[]>emptyMap(), false);
        }

        public boolean isLoaded()
        {
            return html != null;
        }

        Entry(String url, @Nullable byte[] html, PageParser.Mode mode, @Nullable PageSnapshot.Connection connection, Map<String, byte[]> images, boolean builtin)
        {
            this.url = url;
            this.html = html;
            this.mode = mode;
            this.connection = connection;
            this.images = images;
            this.builtin = builtin;
        }
    }

    /** 一次要打开的请求 */
    static final class Request
    {
        final String url;
        final String method;
        @Nullable
        final byte[] body;
        /** 提交这个表单的那条历史记录与表单序号（成功后清空它）；不是表单为 null */
        @Nullable
        final Entry fromEntry;
        final int fromForm;
        /** 发起这次导航的页面所在的站（点链接、提交表单）；地址栏、刷新、前进后退等用户自己发起的为 null（SameSite 用） */
        @Nullable
        final String initiator;
        /** 跳转链上前面已经跨过站 */
        final boolean crossSite;
        /** 打开主页：连不上时显示离线主页 about:home（服主 11-15） */
        boolean homeFallback;

        Request(String url, String method, @Nullable byte[] body, @Nullable Entry fromEntry, int fromForm)
        {
            this(url, method, body, fromEntry, fromForm, null, false);
        }

        Request(String url, String method, @Nullable byte[] body, @Nullable Entry fromEntry, int fromForm, @Nullable String initiator, boolean crossSite)
        {
            this.url = url;
            this.method = method;
            this.body = body;
            this.fromEntry = fromEntry;
            this.fromForm = fromForm;
            this.initiator = initiator;
            this.crossSite = crossSite;
        }

        /** 这一站是不是跨站请求：跳转链上已经跨过站，或者目标和发起的页面不是一个站 */
        boolean isCrossSite()
        {
            return crossSite || (initiator != null && !initiator.equals(CookieJar.site(UrlInput.hostOf(url))));
        }
    }

    final BlockPos pos;
    final List<Entry> history = new ArrayList<>();
    int index = -1;
    public final TextEdit address = new TextEdit(UrlInput.MAX_LENGTH);
    /** 玩家改过地址栏还没打开（这时不拿页面网址覆盖它） */
    public boolean addressEdited;
    private Status status = Status.IDLE;
    private String statusText = "";
    /** 每次导航加一，过时的回调丢掉 */
    private int generation;
    @Nullable
    private Request waiting;
    private long waitingSince;

    // 渲染
    @Nullable
    private RenderedPage rendered;
    @Nullable
    private Entry renderedEntry;
    private int renderVersion;
    private int renderRequest;
    /** 已经交给渲染线程、还没回来（或回来了、电脑还在排版：{@link #layoutPage}） */
    private boolean renderPending;
    /** 画好了、但这台电脑的 CPU 还没“排版”完的一页（CPU 比标准频率慢时）：到 {@link Entry#layoutReadyAt} 才显示 */
    @Nullable
    private RenderedPage layoutPage;
    @Nullable
    private Entry layoutEntry;
    private int layoutRequest;
    private double lastScale = 2.0;
    @Nullable
    private Entry uploadedEntry;
    private int uploadId;
    private int lastSentScroll = -1;
    private long lastScrollSent;
    /** 打字同步（只读本地的内容，不改它）：发件箱、对得上的那次上传（表单的框按它对上页面；没上传成功为 -1） */
    private final TypingSync.Outbox typing = new TypingSync.Outbox();
    private int typingUpload = -1;
    /** 正在加载的会话恢复条目：加载完（或出错）换掉它，而不是新加一条 */
    @Nullable
    private Entry replacing;
    /** 刚建好时自动开的主页（用户还没动过时可以被会话恢复换掉） */
    @Nullable
    private Entry initialHome;
    /** 当前这条是什么时候显示出来的（自动刷新从这时算） */
    private long shownAt = System.currentTimeMillis();
    /** 这条已经按自动刷新处理过了（刷了或者因为填了表单没刷），不再管它 */
    @Nullable
    private Entry refreshDone;
    /** 闲着时状态栏上的提示（如“有未提交的内容，没有自动刷新”）；换页就清掉 */
    private String notice = "";
    /** 报给服务端的会话（变了才报，最多每 500 毫秒一次） */
    @Nullable
    private BrowserHistory reportedHistory;
    private long historySentAt;
    /** 画板同步给旁观者（服主 11-15） */
    private final CanvasSync.Outbox canvasOut = new CanvasSync.Outbox();
    /** 正在自动打开主页（开机、刚建好）：打开后记成 {@link #initialHome}，会话恢复可以换掉它 */
    private boolean autoHomeLoading;
    /** 等服务端发主页设置，从什么时候开始等的（0 为没在等） */
    private long homeWaitSince;
    /** 最多等主页设置多久，等不到就开离线主页 */
    static final long HOME_WAIT_MS = 1500;

    Browser(BlockPos pos)
    {
        this.pos = pos;
    }

    // ---- 给界面看的 ----

    public Status status()
    {
        return status;
    }

    public String statusText()
    {
        return statusText;
    }

    /** 闲着时状态栏上的提示；没有为空串 */
    public String notice()
    {
        return notice;
    }

    @Nullable
    public Entry current()
    {
        return index >= 0 && index < history.size() ? history.get(index) : null;
    }

    @Nullable
    public RenderedPage rendered()
    {
        return rendered != null && renderedEntry == current() ? rendered : null;
    }

    /** 渲染结果每变一次加一（界面据此重建贴图） */
    public int renderVersion()
    {
        return renderVersion;
    }

    public boolean canBack()
    {
        return index > 0;
    }

    public boolean canForward()
    {
        return index < history.size() - 1;
    }

    // ---- 操作 ----

    /**
     * 刚建好、或重开界面：服务端存着会话、而这边还没有这台电脑的状态（或者只有自动开的主页）时，按会话恢复并重新加载当前地址
     * （服主 11-14）；没有页面时开主页；有页面时重新渲染。这边已经有状态时不按服务端的换（表单里填的东西不能被自动丢掉）。
     */
    public void ensureStarted()
    {
        ComputerViews.View v = ComputerViews.view(pos);
        BrowserHistory saved = v.savedHistory;
        if(saved != null)
        {
            v.savedHistory = null;
            if(!saved.isEmpty() && (history.isEmpty() || isPristine()))
            {
                restore(saved);
                return;
            }
        }
        Entry cur = current();
        if(cur == null)
        {
            if(status != Status.IDLE || waiting != null)
                return;
            // 开机进主页（服主 11-15：服务器配置的默认主页，或这台电脑设的）；还没收到主页设置时等一会
            String home = v.effectiveHome();
            if(home == null)
            {
                long now = System.currentTimeMillis();
                if(homeWaitSince == 0)
                    homeWaitSince = now;
                if(now - homeWaitSince < HOME_WAIT_MS)
                    return;
                home = UrlInput.HOME;
            }
            homeWaitSince = 0;
            openHome(home, true);
        }
        else if(!cur.isLoaded())
        {
            if(status == Status.IDLE && waiting == null)
                load(cur);
        }
        else if(rendered() == null && !renderPending)
        {
            requestRender();
        }
    }

    /** 只有自动开的主页，用户什么都没动过 */
    private boolean isPristine()
    {
        return history.size() == 1 && history.get(0) == initialHome && !addressEdited;
    }

    /** 这台电脑现在的主页（还没收到设置时为 about:home） */
    public String homeUrl()
    {
        String h = ComputerViews.view(pos).effectiveHome();
        return h == null ? UrlInput.HOME : h;
    }

    /** 打开主页；{@code auto} 为开机自动打开（之后会话恢复可以换掉它） */
    private void openHome(String url, boolean auto)
    {
        replacing = null;
        addressEdited = false;
        address.setText(UrlInput.display(url));
        Request r = new Request(url, "GET", null, null, -1);
        r.homeFallback = true;
        navigate(r);
        if(auto)
        {
            if(url.equals(UrlInput.HOME))
                initialHome = current();
            else
                autoHomeLoading = true;
        }
    }

    /** 当前页能不能设成主页：正常打开的 http / https 页，或者 about:home */
    public boolean canSetHome()
    {
        Entry e = current();
        if(e == null)
            return false;
        return e.builtin ? e.url.equals(UrlInput.HOME) : HomePage.isValid(e.url);
    }

    /** 把当前页设为这台电脑的主页（存在服务端，跟着电脑走） */
    public void setHomeToCurrent()
    {
        if(!canSetHome())
            return;
        String url = current().url;
        ComputerViews.view(pos).home = url;
        PacketHandler.INSTANCE.sendToServer(new MessageComputerHome(pos, url));
    }

    /** 恢复服务器默认主页 */
    public void resetHome()
    {
        ComputerViews.view(pos).home = null;
        PacketHandler.INSTANCE.sendToServer(new MessageComputerHome(pos, ""));
    }

    /** 打开离线主页 about:home */
    public void openOfflineHome()
    {
        openHome(UrlInput.HOME, false);
    }

    /** 按会话恢复：前进后退的条目只有网址，当前那条马上重新加载 */
    void restore(BrowserHistory h)
    {
        history.clear();
        for(String u : h.urls)
            history.add(Entry.placeholder(u));
        index = Math.max(0, Math.min(h.index, history.size() - 1));
        initialHome = null;
        reportedHistory = h;
        Entry cur = current();
        addressEdited = false;
        address.setText(UrlInput.display(cur.url));
        load(cur);
    }

    /** 加载会话恢复出来的条目（GET 它的网址，结果换掉它） */
    private void load(Entry placeholder)
    {
        navigate(new Request(placeholder.url, "GET", null, null, -1), placeholder);
    }

    /** 地址栏回车 */
    public void submitAddress()
    {
        // 用户自己开新页面：不再替换正在加载的会话恢复条目
        replacing = null;
        UrlInput.Result r = UrlInput.fromAddressBar(address.getText());
        if(!r.isOk())
        {
            if(r.error == UrlInput.Error.EMPTY)
                return;
            BuiltinPages.Error e = r.error == UrlInput.Error.UNSUPPORTED_SCHEME ? BuiltinPages.Error.UNSUPPORTED_SCHEME
                    : r.error == UrlInput.Error.TOO_LONG ? BuiltinPages.Error.URL_TOO_LONG : BuiltinPages.Error.BAD_URL;
            // 地址栏里敲的东西原样留着
            showError(address.getText(), e, r.detail, true);
            return;
        }
        addressEdited = true;
        navigate(new Request(r.url, "GET", null, null, -1));
    }

    /** 点链接 */
    public void openLink(@Nullable String url)
    {
        replacing = null;
        if(url == null)
        {
            showError("", BuiltinPages.Error.UNSUPPORTED_SCHEME, "", true);
            return;
        }
        addressEdited = false;
        address.setText(UrlInput.display(url));
        navigate(new Request(url, "GET", null, null, -1, initiatorOf(current()), false));
    }

    /** 页面所在的站（内置页面没有：从主页点的链接算用户自己发起的） */
    @Nullable
    private static String initiatorOf(@Nullable Entry e)
    {
        if(e == null || e.builtin)
            return null;
        String host = UrlInput.hostOf(e.url);
        return host == null ? null : CookieJar.site(host);
    }

    public void back()
    {
        if(canBack())
            go(index - 1);
    }

    public void forward()
    {
        if(canForward())
            go(index + 1);
    }

    public void home()
    {
        openHome(homeUrl(), false);
    }

    /** 刷新：重新取当前网址（GET；表单提交的结果页也只重取网址，不重新提交） */
    public void reload()
    {
        Entry e = current();
        if(e == null)
            return;
        addressEdited = false;
        address.setText(UrlInput.display(e.url));
        navigate(new Request(e.url, "GET", null, null, -1));
    }

    /** 停止正在打开的页面 */
    public void stop()
    {
        generation++;
        waiting = null;
        setStatus(Status.IDLE, "");
    }

    private void go(int i)
    {
        generation++;
        waiting = null;
        Entry old = current();
        index = i;
        shownAt = System.currentTimeMillis();
        notice = "";
        refreshDone = null;
        if(!addressEdited || old != null)
        {
            addressEdited = false;
            address.setText(UrlInput.display(current().url));
        }
        setStatus(Status.IDLE, "");
        reportHistorySoon();
        Entry now = current();
        if(!now.isLoaded())
            load(now);
        else
            requestRender();
    }

    /**
     * 提交表单（{@code submitter} 是按下的按钮编号；回车提交时为第一个提交按钮或 -1）。
     * 表单里有位置字段（{@code <input type="x-yr-location">}）时，先请服务器给这台电脑签位置（服务器先弹系统确认框问操作者），
     * 把证明全文填进那个字段（拿不到时填 {@code error:代号}，如 {@code error:no_locator}、{@code error:denied}），再提交。
     * 位置签给表单要提交到的那个网站（action 的主机）。
     */
    public void submitForm(int form, int submitter)
    {
        replacing = null;
        Entry e = current();
        RenderedPage page = rendered();
        if(e == null || page == null || form < 0 || form >= page.document.forms.size())
            return;
        PageDocument.FormInfo info = page.document.forms.get(form);
        if(info.action == null)
        {
            showError("", BuiltinPages.Error.UNSUPPORTED_SCHEME, "", false);
            return;
        }
        List<PageDocument.Control> location = new ArrayList<>();
        for(PageDocument.Control c : page.document.controls)
        {
            if(c.form == form && c.location && !c.disabled && !c.name.isEmpty())
                location.add(c);
        }
        if(location.isEmpty())
        {
            doSubmit(e, page, info, form, submitter, Collections.<Integer, String>emptyMap());
            return;
        }
        String site = UrlInput.hostOf(info.action);
        int gen = ++generation;
        waiting = null;
        setStatus(Status.LOADING, ComputerViews.TR.t("cfm.browser.status.location"));
        Map<Integer, CompletableFuture<LocationResult>> asks = new LinkedHashMap<>();
        for(PageDocument.Control c : location)
            asks.put(c.n, ClientLocation.request(site == null ? "" : site, c.value));
        CompletableFuture.allOf(asks.values().toArray(new CompletableFuture<?>[0])).whenComplete((v, err) -> Minecraft.getMinecraft().addScheduledTask(() ->
        {
            if(gen != generation || current() != e || rendered() != page)
                return;
            Map<Integer, String> values = new HashMap<>();
            for(Map.Entry<Integer, CompletableFuture<LocationResult>> a : asks.entrySet())
            {
                LocationResult r = a.getValue().getNow(LocationResult.of(LocationResult.Status.UNAVAILABLE));
                values.put(a.getKey(), r.ok() ? r.proof : "error:" + r.status.code);
            }
            setStatus(Status.IDLE, "");
            doSubmit(e, page, info, form, submitter, values);
        }));
    }

    private void doSubmit(Entry e, RenderedPage page, PageDocument.FormInfo info, int form, int submitter, Map<Integer, String> overrides)
    {
        String encoded = FormEncoding.encode(e.forms.fields(page.document, form, submitter, overrides));
        addressEdited = false;
        if(info.post)
        {
            address.setText(UrlInput.display(info.action));
            navigate(new Request(info.action, "POST", encoded.getBytes(StandardCharsets.US_ASCII), e, form, initiatorOf(e), false));
        }
        else
        {
            String base = info.action;
            int q = base.indexOf('?');
            String url = (q < 0 ? base : base.substring(0, q)) + (encoded.isEmpty() ? "" : "?" + encoded);
            UrlInput.Result r = UrlInput.normalize(url);
            if(!r.isOk())
            {
                showError(url, BuiltinPages.Error.URL_TOO_LONG, null, false);
                return;
            }
            address.setText(UrlInput.display(r.url));
            navigate(new Request(r.url, "GET", null, e, form, initiatorOf(e), false));
        }
    }

    public void resetForm(int form)
    {
        Entry e = current();
        RenderedPage page = rendered();
        if(e != null && page != null)
            e.forms.reset(page.document, form);
    }

    // ---- 导航 ----

    private void navigate(Request req)
    {
        navigate(req, null);
    }

    private void navigate(Request req, @Nullable Entry replace)
    {
        generation++;
        waiting = null;
        replacing = replace;
        autoHomeLoading = false;
        if(req.url.equals(UrlInput.HOME))
        {
            showBuiltin(UrlInput.HOME, BuiltinPages.home(ComputerViews.TR), true);
            return;
        }
        ClientNic.Session s = ClientNic.current();
        if(s == null || s.isClosed())
        {
            waiting = req;
            waitingSince = System.currentTimeMillis();
            setStatus(Status.WAITING_NETWORK, ComputerViews.TR.t("cfm.browser.status.waiting_network"));
            return;
        }
        fetch(s, req, 0, generation);
    }

    /** 每帧调：等网络的请求，接上了就发，等太久就报错；把正在打的字同步给旁观者；到时间就自动刷新；排版完了就显示 */
    public void tick()
    {
        finishLayout(System.currentTimeMillis());
        flushScroll(System.currentTimeMillis());
        syncTyping();
        syncCanvases();
        reportHistory();
        autoRefresh(System.currentTimeMillis());
        if(waiting != null)
        {
            ClientNic.Session s = ClientNic.current();
            if(s != null && !s.isClosed())
            {
                Request r = waiting;
                waiting = null;
                fetch(s, r, 0, generation);
            }
            else if(System.currentTimeMillis() - waitingSince > NETWORK_WAIT_MS)
            {
                Request r = waiting;
                waiting = null;
                showError(r.url, BuiltinPages.Error.NO_NETWORK, null, false);
            }
        }
    }

    /**
     * 自动刷新（{@code <meta http-equiv="refresh">}，服主 11-15）：页面显示满 N 秒、浏览器闲着时，GET 页面写的网址（同站），结果换掉这一条
     * （不多出历史）。玩家在这页的表单里填了东西、或者正在改地址栏时不刷（用户输入不能被自动丢掉），状态栏提示一句；之后这一页不再刷。
     */
    void autoRefresh(long now)
    {
        Entry e = current();
        RenderedPage page = rendered();
        if(e == null || e.builtin || page == null || page.document.refresh == null || e == refreshDone || status != Status.IDLE || waiting != null)
            return;
        PageDocument.Refresh r = page.document.refresh;
        if(now - shownAt < r.seconds * 1000L)
            return;
        refreshDone = e;
        if(addressEdited || e.forms.isDirty(page.document))
        {
            notice = ComputerViews.TR.t("cfm.browser.status.refresh_skipped");
            return;
        }
        replacing = null;
        if(!r.url.equals(e.url))
            address.setText(UrlInput.display(r.url));
        navigate(new Request(r.url, "GET", null, null, -1, initiatorOf(e), false), e);
    }

    private void fetch(ClientNic.Session s, Request req, int redirects, int gen)
    {
        HttpClient.Url u = HttpClient.Url.parse(req.url);
        if(u == null)
        {
            showError(req.url, BuiltinPages.Error.BAD_URL, null, false);
            return;
        }
        setStatus(Status.LOADING, ComputerViews.TR.t("cfm.browser.status.loading", u.host));
        Map<String, String> headers = new LinkedHashMap<>();
        if(req.body != null)
            headers.put("Content-Type", "application/x-www-form-urlencoded");
        // Cookie：这台电脑的（服务端交给操作者的那份），按 SameSite 规则（顶层导航）
        String cookie = cookieHeader(req.url, new CookieJar.Context(true, req.method.equals("GET") || req.method.equals("HEAD"), req.isCrossSite()));
        if(cookie != null)
            headers.put("Cookie", cookie);
        CompletableFuture<HttpClient.Response> f = HttpClient.send(s.stack(), s.dns(), u, req.method, headers, req.body, PAGE_TIMEOUT_MS, null);
        f.whenComplete((resp, err) -> Minecraft.getMinecraft().addScheduledTask(() ->
        {
            if(gen != generation)
                return;
            if(err != null)
            {
                fail(req, unwrap(err));
                return;
            }
            onResponse(s, req, resp, redirects, gen);
        }));
    }

    private void onResponse(ClientNic.Session s, Request req, HttpClient.Response resp, int redirects, int gen)
    {
        // 响应里的新 Cookie（顶层导航的响应都收，含跳转的每一站）
        receiveCookies(req.url, resp.headerValues("set-cookie"));
        if(resp.status / 100 == 3 && resp.header("location") != null)
        {
            if(redirects >= MAX_REDIRECTS)
            {
                showError(req.url, BuiltinPages.Error.TOO_MANY_REDIRECTS, null, false);
                return;
            }
            String next = UrlInput.resolve(req.url, resp.header("location"));
            if(next == null || next.equals(UrlInput.HOME))
            {
                showError(req.url, BuiltinPages.Error.UNSUPPORTED_SCHEME, resp.header("location"), false);
                return;
            }
            boolean keep = resp.status == 307 || resp.status == 308;
            // 表单已经被服务器收下（跳转就是“处理完了”）：清空它
            formAccepted(req);
            // SameSite：跳转后发起者仍是原来的页面（没有时算上一站），跨过站就一直算跨站
            String initiator = req.initiator != null ? req.initiator : CookieJar.site(UrlInput.hostOf(req.url));
            Request r = new Request(next, keep ? req.method : "GET", keep ? req.body : null, keep ? req.fromEntry : null, keep ? req.fromForm : -1,
                    initiator, req.isCrossSite());
            r.homeFallback = req.homeFallback;
            if(!addressEdited || req.fromEntry != null)
                address.setText(UrlInput.display(next));
            fetch(s, r, redirects + 1, gen);
            return;
        }
        String type = resp.header("content-type");
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        byte[] html;
        PageParser.Mode mode = PageParser.Mode.XHTML;
        Map<String, byte[]> images = new HashMap<>();
        List<String> imageUrls;
        if(t.startsWith("application/xhtml+xml") || t.startsWith("text/html") || t.startsWith("application/xml") || t.startsWith("text/xml") || t.isEmpty())
        {
            html = resp.body;
            mode = PageParser.Mode.forContentType(type);
        }
        else if(t.startsWith("text/plain"))
        {
            html = BuiltinPages.plainText(req.url, new String(resp.body, 0, Math.min(resp.body.length, PageLimits.MAX_BYTES / 2), StandardCharsets.UTF_8));
        }
        else if(t.startsWith("image/"))
        {
            byte[] png = checkedImage(resp.body);
            if(png == null)
            {
                showError(req.url, BuiltinPages.Error.UNSUPPORTED_TYPE, type, false);
                return;
            }
            images.put(req.url, png);
            html = BuiltinPages.image(req.url);
        }
        else
        {
            showError(req.url, BuiltinPages.Error.UNSUPPORTED_TYPE, type, false);
            return;
        }
        PageLimits.Problem p = PageLimits.check(html, mode);
        if(p != null)
        {
            showError(req.url, problemError(p), null, false);
            return;
        }
        PageSnapshot.Connection conn = resp.tls == null ? null : PageSnapshot.Connection.of(resp.tls);
        // 只有成功（2xx）才算网站收下了表单；4xx / 5xx 是提交失败，原页面表单里填的保留
        if(resp.status / 100 == 2)
            formAccepted(req);
        // 图片：先把页面列的图取回来（只走游戏内网络，大小、尺寸有上限），再渲染
        try
        {
            imageUrls = PageDocument.parse(html, mode, req.url, id -> null, PageRenderer.VIEW_WIDTH).images;
        }
        catch(PageDocument.Invalid e)
        {
            showError(req.url, problemError(e.problem == null ? PageLimits.Problem.MALFORMED : e.problem), e.getMessage(), false);
            return;
        }
        byte[] page = html;
        PageParser.Mode pageMode = mode;
        if(imageUrls.isEmpty() || !images.isEmpty())
        {
            loaded(req, page, pageMode, conn, images);
            return;
        }
        setStatus(Status.LOADING, ComputerViews.TR.t("cfm.browser.status.images", imageUrls.size()));
        ImageFetcher.fetch(s, imageUrls, () -> gen == generation, imageCookies(req.url), got -> loaded(req, page, pageMode, conn, got));
    }

    /** 图片：先看文件头的尺寸，不超限才解码，再存成 PNG 交给内核；不合格返回 null */
    @Nullable
    static byte[] checkedImage(byte[] data)
    {
        if(data.length > MAX_IMAGE_BYTES)
            return null;
        try
        {
            BufferedImage img = MediaDecoding.decodeImage(data, MAX_IMAGE_SIDE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        }
        catch(MediaDecoding.Rejected | IOException | RuntimeException e)
        {
            return null;
        }
    }

    // ---- Cookie（服主 11-15：存在服务端、跟着电脑走；操作者打开时服务端交给他，新收到的交回服务端） ----

    /** 这台电脑的 Cookie；还没收到（或者不是操作者）为 null */
    @Nullable
    private CookieJar jar()
    {
        return ComputerViews.view(pos).cookies;
    }

    @Nullable
    private String cookieHeader(String url, CookieJar.Context ctx)
    {
        CookieJar jar = jar();
        return jar == null ? null : jar.header(url, ctx, System.currentTimeMillis());
    }

    /** 收到 Set-Cookie：先存进本地这份，再原样交给服务端（服务端按同样的规则存） */
    void receiveCookies(String url, List<String> setCookies)
    {
        CookieJar jar = jar();
        if(jar == null || setCookies.isEmpty())
            return;
        long now = System.currentTimeMillis();
        List<String> accepted = new ArrayList<>();
        for(String sc : setCookies)
        {
            if(jar.set(sc, url, now))
                accepted.add(sc);
        }
        if(accepted.isEmpty())
            return;
        for(int from = 0; from < accepted.size(); from += MessageComputerCookies.MAX_LINES)
        {
            List<String> part = accepted.subList(from, Math.min(accepted.size(), from + MessageComputerCookies.MAX_LINES));
            PacketHandler.INSTANCE.sendToServer(new MessageComputerCookies(pos, url, part));
        }
    }

    /** 页面里的图片（子资源）：同站的带 Cookie、收 Cookie；跨站的（如 captcha.yr 的验证码）只带 SameSite=None 的、不收 */
    private ImageFetcher.Cookies imageCookies(String pageUrl)
    {
        return new ImageFetcher.Cookies()
        {
            @Nullable
            @Override
            public String header(String url)
            {
                return cookieHeader(url, new CookieJar.Context(false, true, !CookieJar.sameSite(pageUrl, url)));
            }

            @Override
            public void received(String url, List<String> setCookies)
            {
                if(CookieJar.sameSite(pageUrl, url))
                    receiveCookies(url, setCookies);
            }
        };
    }

    private void formAccepted(Request req)
    {
        if(req.fromEntry != null && req.fromForm >= 0 && req.fromEntry == current() && rendered() != null)
            req.fromEntry.forms.reset(rendered().document, req.fromForm);
    }

    private void loaded(Request req, byte[] html, PageParser.Mode mode, @Nullable PageSnapshot.Connection conn, Map<String, byte[]> images)
    {
        boolean auto = autoHomeLoading;
        push(new Entry(req.url, html, mode, conn, images, false));
        if(auto)
            initialHome = current();
        // 打开成功：地址栏换成实际的网址（失败时才保留敲的原样）
        address.setText(UrlInput.display(req.url));
        addressEdited = false;
        setStatus(Status.IDLE, "");
    }

    private void fail(Request req, Throwable err)
    {
        if(req.homeFallback && !req.url.equals(UrlInput.HOME))
        {
            // 主页打不开（没网、网站关了）：显示离线主页 about:home（服主 11-15），状态栏说一句
            boolean auto = autoHomeLoading;
            showBuiltin(UrlInput.HOME, BuiltinPages.home(ComputerViews.TR), true);
            if(auto)
                initialHome = current();
            notice = ComputerViews.TR.t("cfm.browser.status.home_offline", UrlInput.display(req.url));
            return;
        }
        BuiltinPages.Error e = BuiltinPages.Error.OTHER;
        String detail = err.getMessage();
        if(err instanceof NetworkException)
        {
            NetworkException n = (NetworkException) err;
            switch(n.reason)
            {
                case NAME_NOT_FOUND: e = BuiltinPages.Error.NAME_NOT_FOUND; break;
                case DNS_FAILED: e = BuiltinPages.Error.DNS_FAILED; break;
                case UNREACHABLE: e = BuiltinPages.Error.UNREACHABLE; break;
                case REFUSED: e = BuiltinPages.Error.REFUSED; break;
                case TIMEOUT: e = BuiltinPages.Error.TIMEOUT; break;
                case RESET: e = BuiltinPages.Error.RESET; break;
                case CERTIFICATE:
                    e = BuiltinPages.Error.CERTIFICATE;
                    detail = n.certificateProblem == null ? detail : ComputerViews.TR.t("cfm.browser.cert." + n.certificateProblem.name().toLowerCase(Locale.ROOT));
                    break;
                case TLS: e = BuiltinPages.Error.TLS; break;
                case BAD_RESPONSE: e = BuiltinPages.Error.BAD_RESPONSE; break;
                case LIMIT: e = BuiltinPages.Error.TOO_LARGE; break;
                case CLOSED: e = BuiltinPages.Error.NO_NETWORK; break;
                default: break;
            }
        }
        showError(req.url, e, detail, false);
    }

    static Throwable unwrap(Throwable t)
    {
        Throwable c = t;
        while(c instanceof CompletionException && c.getCause() != null)
            c = c.getCause();
        return c;
    }

    static BuiltinPages.Error problemError(PageLimits.Problem p)
    {
        switch(p)
        {
            case TOO_LARGE: return BuiltinPages.Error.TOO_LARGE;
            case TOO_MANY_ELEMENTS: return BuiltinPages.Error.TOO_MANY_ELEMENTS;
            case TOO_DEEP: return BuiltinPages.Error.TOO_DEEP;
            default: return BuiltinPages.Error.MALFORMED;
        }
    }

    /** 错误页：进历史（后退能回到原来的页面，表单里填的还在）；{@code keepAddress} 时地址栏不动 */
    private void showError(String url, BuiltinPages.Error e, @Nullable String detail, boolean keepAddress)
    {
        generation++;
        waiting = null;
        push(new Entry(url, BuiltinPages.error(ComputerViews.TR, e, url, detail), PageParser.Mode.XHTML, null, Collections.<String, byte[]>emptyMap(), true));
        if(!keepAddress && !addressEdited && !url.isEmpty())
            address.setText(UrlInput.display(url));
        setStatus(Status.IDLE, "");
    }

    private void showBuiltin(String url, byte[] html, boolean setAddress)
    {
        push(new Entry(url, html, PageParser.Mode.XHTML, null, Collections.<String, byte[]>emptyMap(), true));
        if(setAddress)
        {
            address.setText(UrlInput.display(url));
            addressEdited = false;
        }
        setStatus(Status.IDLE, "");
    }

    private void push(Entry e)
    {
        shownAt = System.currentTimeMillis();
        notice = "";
        Entry r = replacing;
        replacing = null;
        int at = r == null ? -1 : history.indexOf(r);
        if(at >= 0)
        {
            // 会话恢复出来的条目加载好了（或出错了）：换掉它，前进后退的其他条目不动
            history.set(at, e);
            index = at;
            reportHistorySoon();
            requestRender();
            return;
        }
        while(history.size() > index + 1)
            history.remove(history.size() - 1);
        history.add(e);
        while(history.size() > MAX_HISTORY)
            history.remove(0);
        index = history.size() - 1;
        reportHistorySoon();
        requestRender();
    }

    /** 会话变了：下次 tick 时报给服务端 */
    private void reportHistorySoon()
    {
        historyDirty = true;
    }

    private boolean historyDirty;

    /** 把会话（网址与前进后退，不含内容）报给服务端，变了才报，最多每 500 毫秒一次 */
    private void reportHistory()
    {
        long now = System.currentTimeMillis();
        if(!historyDirty || now - historySentAt < 500)
            return;
        historyDirty = false;
        List<String> urls = new ArrayList<>();
        for(Entry e : history)
            urls.add(e.url);
        BrowserHistory h = BrowserHistory.of(urls, index);
        if(h.isEmpty() || h.equals(reportedHistory))
            return;
        reportedHistory = h;
        historySentAt = now;
        PacketHandler.INSTANCE.sendToServer(new MessageComputerHistory(pos, h));
    }

    private void setStatus(Status s, String text)
    {
        this.status = s;
        this.statusText = text;
    }

    // ---- 渲染 ----

    /** 界面大小变了（贴图倍数变了）时重渲染 */
    public void setScale(double scale)
    {
        if(Math.abs(scale - lastScale) > 0.01)
        {
            lastScale = scale;
            requestRender();
        }
    }

    void requestRender()
    {
        Entry e = current();
        if(e == null || !e.isLoaded())
            return;
        int req = ++renderRequest;
        renderPending = true;
        layoutPage = null;
        layoutEntry = null;
        if(status == Status.IDLE)
            setStatus(Status.RENDERING, ComputerViews.TR.t("cfm.browser.status.rendering"));
        double scale = lastScale;
        ComputerViews.render(e.html, e.mode, e.url, e.images, scale, page ->
        {
            if(req != renderRequest)
                return;
            if(page != null)
            {
                // 这台电脑的 CPU 慢时多等一会儿再显示（第一次显示这一页时定下要等多久）
                long now = System.currentTimeMillis();
                if(e.layoutReadyAt == 0)
                {
                    e.layoutExtra = ComputerViews.extraLayoutMillis(pos, e.html.length);
                    e.layoutReadyAt = now + e.layoutExtra;
                    // 运行错误（服主 11-17）：排版出错——这一页换成“排版出错”的错误页（刷新重来）；死机——服务端记下，界面黑屏写“死机了”，
                    // 地址栏、别的页面里填的东西都留着，关机再开接着用。错误页、内置页不掷
                    int fault = e.builtin ? 0 : ComputerViews.layoutFault(pos, e.html.length);
                    if(fault == 1)
                    {
                        int at = history.indexOf(e);
                        if(at >= 0)
                        {
                            history.set(at, new Entry(e.url, BuiltinPages.error(ComputerViews.TR, BuiltinPages.Error.LAYOUT_FAULT, e.url, null), PageParser.Mode.XHTML, null,
                                    Collections.<String, byte[]>emptyMap(), true));
                            requestRender();
                            return;
                        }
                    }
                }
                if(now < e.layoutReadyAt)
                {
                    layoutPage = page;
                    layoutEntry = e;
                    layoutRequest = req;
                    return;
                }
                show(e, page);
                return;
            }
            renderPending = false;
            if(status == Status.RENDERING)
                setStatus(Status.IDLE, "");
            if(page == null)
            {
                if(!e.builtin)
                {
                    // 渲染失败：换成错误页（错误页本身也失败时界面上直接写字）
                    int at = history.indexOf(e);
                    if(at >= 0)
                    {
                        history.set(at, new Entry(e.url, BuiltinPages.error(ComputerViews.TR, BuiltinPages.Error.RENDER_FAILED, e.url, null), PageParser.Mode.XHTML, null,
                                Collections.<String, byte[]>emptyMap(), true));
                        requestRender();
                        return;
                    }
                }
                rendered = null;
                renderedEntry = e;
                renderVersion++;
            }
        });
    }

    /** 画好、排版完的一页：显示出来，传给旁观者 */
    private void show(Entry e, RenderedPage page)
    {
        renderPending = false;
        layoutPage = null;
        layoutEntry = null;
        if(status == Status.RENDERING)
            setStatus(Status.IDLE, "");
        e.forms.init(page.document);
        rendered = page;
        renderedEntry = e;
        renderVersion++;
        upload(e);
    }

    /** 排版的时间到了：显示（换了页面、重画过的丢掉） */
    void finishLayout(long now)
    {
        if(layoutPage == null)
            return;
        if(layoutRequest != renderRequest || layoutEntry != current())
        {
            layoutPage = null;
            layoutEntry = null;
            return;
        }
        if(now >= layoutEntry.layoutReadyAt)
            show(layoutEntry, layoutPage);
    }

    /** 页面交给渲染线程还没回来、或电脑还在排版 */
    public boolean isRenderPending()
    {
        return renderPending;
    }

    /** 正在排版的进度（0–100）；没在等 CPU 时为 -1 */
    public int layoutProgress()
    {
        Entry e = layoutEntry;
        if(layoutPage == null || e == null || e != current() || e.layoutExtra <= 0)
            return -1;
        long left = e.layoutReadyAt - System.currentTimeMillis();
        return (int) Math.max(0, Math.min(99, 100 - left * 100 / e.layoutExtra));
    }

    /** 关界面时释放渲染结果（再打开时重渲染），状态都留着 */
    public void releaseRender()
    {
        rendered = null;
        renderedEntry = null;
        renderRequest++;
        renderPending = false;
        layoutPage = null;
        layoutEntry = null;
        renderVersion++;
        uploadedEntry = null;
    }

    // ---- 旁观同步 ----

    /** 把当前页面上传给服务端（分块；服务端只看大小） */
    private void upload(Entry e)
    {
        if(uploadedEntry == e)
            return;
        uploadedEntry = e;
        byte[] data = new PageSnapshot(e.url, e.html, e.mode, e.connection).encode();
        // 换页面了：旁观者那边的打字记录会清空，从页面上的初始值重新比
        typing.reset();
        canvasOut.reset();
        typingUpload = -1;
        typing.baseline(TypingSync.outgoing(TypingSync.ADDRESS, false, UrlInput.display(e.url)));
        if(data.length > PageSnapshot.MAX_COMPRESSED)
            return;
        int id = ++uploadId;
        typingUpload = id;
        int count = Math.max(1, (data.length + PageUploads.CHUNK - 1) / PageUploads.CHUNK);
        for(int i = 0; i < count; i++)
        {
            int from = i * PageUploads.CHUNK;
            int to = Math.min(data.length, from + PageUploads.CHUNK);
            byte[] part = new byte[to - from];
            System.arraycopy(data, from, part, 0, part.length);
            PacketHandler.INSTANCE.sendToServer(new MessageComputerPage(pos, id, i, count, part));
        }
        lastSentScroll = 0;
    }

    /**
     * 打字同步（服主 11-14）：地址栏和当前页面文本框里的内容变了就排队，每 250 毫秒最多发一条，每条最多 256 个字符；
     * 密码框只发长度。只读本地的内容（关界面、断电等时本地内容照旧留着，见 {@link ComputerViews}）。
     */
    private void syncTyping()
    {
        typing.offer(TypingSync.outgoing(TypingSync.ADDRESS, false, address.getText()));
        Entry e = current();
        RenderedPage page = rendered();
        if(e != null && page != null && e == uploadedEntry && typingUpload >= 0)
        {
            for(PageDocument.Control c : page.document.controls)
            {
                if(!c.isTextual())
                    continue;
                boolean pw = c.kind == PageDocument.Kind.PASSWORD;
                typing.baseline(TypingSync.outgoing(c.n, pw, c.value));
                TextEdit t = e.forms.text(c.n);
                if(t != null)
                    typing.offer(TypingSync.outgoing(c.n, pw, t.getText()));
            }
        }
        TypingSync.Typed t = typing.poll(System.currentTimeMillis());
        if(t != null)
            PacketHandler.INSTANCE.sendToServer(new MessageComputerTyping(pos, typingUpload, t));
    }

    /**
     * 画板同步（服主 11-15，同打字同步）：画板内容变了就整张压缩发给服务端，每 {@value CanvasSync#INTERVAL_MS} 毫秒最多一张；
     * 没画过（和页面给的初始图一样）的不发。只读本地内容。
     */
    private void syncCanvases()
    {
        Entry e = current();
        RenderedPage page = rendered();
        if(e == null || page == null || e != uploadedEntry || typingUpload < 0)
            return;
        long now = System.currentTimeMillis();
        for(PageDocument.Control c : page.document.controls)
        {
            if(c.kind != PageDocument.Kind.CANVAS)
                continue;
            PixelCanvas p = e.forms.canvas(c.n);
            if(p != null && canvasOut.due(c.n, p, now))
            {
                canvasOut.sent(c.n, p, now);
                PacketHandler.INSTANCE.sendToServer(new MessageComputerCanvas(pos, typingUpload, c.n, p));
                return;
            }
        }
    }

    /**
     * 停下来时最后的滚动位置也要报上去（{@link #reportScroll} 只在滚的时候报，250 毫秒内的最后一下会漏掉）；
     * 只报已经传给服务端的那一页的。
     */
    private void flushScroll(long now)
    {
        Entry e = current();
        if(e != null && e == uploadedEntry && rendered() != null && e.scroll != lastSentScroll && now - lastScrollSent >= 250)
        {
            lastSentScroll = e.scroll;
            lastScrollSent = now;
            PacketHandler.INSTANCE.sendToServer(new MessageComputerScroll(pos, e.scroll));
        }
    }

    /**
     * 服务端把页面清掉了（关机、没电后又开机，界面一直开着）：当前这一页重新传上去，旁观者和世界里的屏幕才看得到。
     * 只重传，不重新加载、不动地址栏和表单。
     */
    public void resync()
    {
        uploadedEntry = null;
        Entry e = current();
        // 还没画好的，画好显示时（show）会传
        if(e != null && rendered() != null)
            upload(e);
    }

    /** 当前显示的这一页在服务端的上传编号（屏幕缩略图用）；还没传上去、太大没传时为 -1 */
    public int syncedPageId()
    {
        Entry e = current();
        return e != null && e == uploadedEntry && rendered() != null ? typingUpload : -1;
    }

    /** 服务端知道的滚动位置（和本地一样时）；还没报上去时为 -1 */
    public int syncedScroll()
    {
        Entry e = current();
        return e != null && e == uploadedEntry && e.scroll == lastSentScroll ? e.scroll : -1;
    }

    /** 滚动位置（最多每 250 毫秒报一次） */
    public void reportScroll(int scroll)
    {
        long now = System.currentTimeMillis();
        if(scroll != lastSentScroll && now - lastScrollSent >= 250)
        {
            lastSentScroll = scroll;
            lastScrollSent = now;
            PacketHandler.INSTANCE.sendToServer(new MessageComputerScroll(pos, scroll));
        }
    }
}
