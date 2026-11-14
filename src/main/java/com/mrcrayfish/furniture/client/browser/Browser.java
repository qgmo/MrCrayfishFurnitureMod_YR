package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.client.MediaDecoding;
import com.mrcrayfish.furniture.computer.FormEncoding;
import com.mrcrayfish.furniture.computer.PageLimits;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.PageUploads;
import com.mrcrayfish.furniture.computer.UrlInput;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerPage;
import com.mrcrayfish.furniture.network.message.MessageComputerScroll;
import moe.qingu.youran.api.network.ClientNic;
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
        public final byte[] html;
        @Nullable
        public final PageSnapshot.Connection connection;
        public final Map<String, byte[]> images;
        public final boolean builtin;
        public final FormState forms = new FormState();
        public int scroll;

        Entry(String url, byte[] html, @Nullable PageSnapshot.Connection connection, Map<String, byte[]> images, boolean builtin)
        {
            this.url = url;
            this.html = html;
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

        Request(String url, String method, @Nullable byte[] body, @Nullable Entry fromEntry, int fromForm)
        {
            this.url = url;
            this.method = method;
            this.body = body;
            this.fromEntry = fromEntry;
            this.fromForm = fromForm;
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
    /** 已经交给渲染线程、还没回来 */
    private boolean renderPending;
    private double lastScale = 2.0;
    @Nullable
    private Entry uploadedEntry;
    private int uploadId;
    private int lastSentScroll = -1;
    private long lastScrollSent;

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

    /** 刚建好、或重开界面：没有页面时开主页；有页面时重新渲染 */
    public void ensureStarted()
    {
        if(current() == null)
        {
            showBuiltin(UrlInput.HOME, BuiltinPages.home(ComputerViews.TR), true);
        }
        else if(rendered() == null && !renderPending)
        {
            requestRender();
        }
    }

    /** 地址栏回车 */
    public void submitAddress()
    {
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
        if(url == null)
        {
            showError("", BuiltinPages.Error.UNSUPPORTED_SCHEME, "", true);
            return;
        }
        addressEdited = false;
        address.setText(url);
        navigate(new Request(url, "GET", null, null, -1));
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
        addressEdited = false;
        address.setText(UrlInput.HOME);
        navigate(new Request(UrlInput.HOME, "GET", null, null, -1));
    }

    /** 刷新：重新取当前网址（GET；表单提交的结果页也只重取网址，不重新提交） */
    public void reload()
    {
        Entry e = current();
        if(e == null)
            return;
        addressEdited = false;
        address.setText(e.url);
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
        if(!addressEdited || old != null)
        {
            addressEdited = false;
            address.setText(current().url);
        }
        setStatus(Status.IDLE, "");
        requestRender();
    }

    /** 提交表单（{@code submitter} 是按下的按钮编号；回车提交时为第一个提交按钮或 -1） */
    public void submitForm(int form, int submitter)
    {
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
        String encoded = FormEncoding.encode(e.forms.fields(page.document, form, submitter));
        addressEdited = false;
        if(info.post)
        {
            address.setText(info.action);
            navigate(new Request(info.action, "POST", encoded.getBytes(StandardCharsets.US_ASCII), e, form));
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
            address.setText(r.url);
            navigate(new Request(r.url, "GET", null, e, form));
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
        generation++;
        waiting = null;
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

    /** 每帧调：等网络的请求，接上了就发，等太久就报错 */
    public void tick()
    {
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
            Request r = new Request(next, keep ? req.method : "GET", keep ? req.body : null, keep ? req.fromEntry : null, keep ? req.fromForm : -1);
            if(!addressEdited || req.fromEntry != null)
                address.setText(next);
            fetch(s, r, redirects + 1, gen);
            return;
        }
        String type = resp.header("content-type");
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        byte[] html;
        Map<String, byte[]> images = new HashMap<>();
        List<String> imageUrls;
        if(t.startsWith("application/xhtml+xml") || t.startsWith("text/html") || t.startsWith("application/xml") || t.startsWith("text/xml") || t.isEmpty())
        {
            html = resp.body;
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
        PageLimits.Problem p = PageLimits.check(html);
        if(p != null)
        {
            showError(req.url, problemError(p), null, false);
            return;
        }
        PageSnapshot.Connection conn = resp.tls == null ? null : PageSnapshot.Connection.of(resp.tls);
        formAccepted(req);
        // 图片：先把页面列的图取回来（只走游戏内网络，大小、尺寸有上限），再渲染
        try
        {
            imageUrls = PageDocument.parse(html, req.url, id -> null, PageRenderer.VIEW_WIDTH).images;
        }
        catch(PageDocument.Invalid e)
        {
            showError(req.url, problemError(e.problem == null ? PageLimits.Problem.MALFORMED : e.problem), e.getMessage(), false);
            return;
        }
        byte[] page = html;
        if(imageUrls.isEmpty() || !images.isEmpty())
        {
            loaded(req, page, conn, images);
            return;
        }
        setStatus(Status.LOADING, ComputerViews.TR.t("cfm.browser.status.images", imageUrls.size()));
        loadImages(s, imageUrls, 0, images, gen, () -> loaded(req, page, conn, images));
    }

    private void loadImages(ClientNic.Session s, List<String> urls, int i, Map<String, byte[]> out, int gen, Runnable done)
    {
        if(gen != generation)
            return;
        if(i >= urls.size() || s.isClosed())
        {
            done.run();
            return;
        }
        HttpClient.Url u = HttpClient.Url.parse(urls.get(i));
        if(u == null)
        {
            loadImages(s, urls, i + 1, out, gen, done);
            return;
        }
        HttpClient.send(s.stack(), s.dns(), u, "GET", Collections.singletonMap("Accept", "image/png, image/gif, image/jpeg"), null, IMAGE_TIMEOUT_MS, null)
                .whenComplete((resp, err) -> Minecraft.getMinecraft().addScheduledTask(() ->
                {
                    if(gen != generation)
                        return;
                    if(err == null && resp.status == 200 && resp.body.length <= MAX_IMAGE_BYTES)
                    {
                        byte[] png = checkedImage(resp.body);
                        if(png != null)
                            out.put(urls.get(i), png);
                    }
                    loadImages(s, urls, i + 1, out, gen, done);
                }));
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

    private void formAccepted(Request req)
    {
        if(req.fromEntry != null && req.fromForm >= 0 && req.fromEntry == current() && rendered() != null)
            req.fromEntry.forms.reset(rendered().document, req.fromForm);
    }

    private void loaded(Request req, byte[] html, @Nullable PageSnapshot.Connection conn, Map<String, byte[]> images)
    {
        push(new Entry(req.url, html, conn, images, false));
        // 打开成功：地址栏换成实际的网址（失败时才保留敲的原样）
        address.setText(req.url);
        addressEdited = false;
        setStatus(Status.IDLE, "");
    }

    private void fail(Request req, Throwable err)
    {
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
        push(new Entry(url, BuiltinPages.error(ComputerViews.TR, e, url, detail), null, Collections.<String, byte[]>emptyMap(), true));
        if(!keepAddress && !addressEdited && !url.isEmpty())
            address.setText(url);
        setStatus(Status.IDLE, "");
    }

    private void showBuiltin(String url, byte[] html, boolean setAddress)
    {
        push(new Entry(url, html, null, Collections.<String, byte[]>emptyMap(), true));
        if(setAddress)
        {
            address.setText(url);
            addressEdited = false;
        }
        setStatus(Status.IDLE, "");
    }

    private void push(Entry e)
    {
        while(history.size() > index + 1)
            history.remove(history.size() - 1);
        history.add(e);
        while(history.size() > MAX_HISTORY)
            history.remove(0);
        index = history.size() - 1;
        requestRender();
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
        if(e == null)
            return;
        int req = ++renderRequest;
        renderPending = true;
        if(status == Status.IDLE)
            setStatus(Status.RENDERING, ComputerViews.TR.t("cfm.browser.status.rendering"));
        double scale = lastScale;
        ComputerViews.render(e.html, e.url, e.images, scale, page ->
        {
            if(req != renderRequest)
                return;
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
                        history.set(at, new Entry(e.url, BuiltinPages.error(ComputerViews.TR, BuiltinPages.Error.RENDER_FAILED, e.url, null), null,
                                Collections.<String, byte[]>emptyMap(), true));
                        requestRender();
                        return;
                    }
                }
                rendered = null;
                renderedEntry = e;
                renderVersion++;
                return;
            }
            e.forms.init(page.document);
            rendered = page;
            renderedEntry = e;
            renderVersion++;
            upload(e);
        });
    }

    /** 关界面时释放渲染结果（再打开时重渲染），状态都留着 */
    public void releaseRender()
    {
        rendered = null;
        renderedEntry = null;
        renderRequest++;
        renderPending = false;
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
        byte[] data = new PageSnapshot(e.url, e.html, e.connection).encode();
        if(data.length > PageSnapshot.MAX_COMPRESSED)
            return;
        int id = ++uploadId;
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
