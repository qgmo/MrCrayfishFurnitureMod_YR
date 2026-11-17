package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.client.browser.PageDocument;
import moe.qingu.youran.api.network.Ipv6Address;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 服务端从操作者上传的页面里自己解析出来的东西（不信客户端报的）：页面里的图片在哪些主机上，用来给旁观者的玩家临时地址放行
 * （服主 11-14：旁观者自己下载图片）；哪些是文本框、哪些是密码框（打字同步时密码框只转长度）。解析与浏览器完全一样（{@link PageDocument}：同主机的图、每页最多 16 张）。
 * <p>
 * 主机名换成地址：IPv6 字面量直接用（{@link #imageHosts}）；域名记在 {@link #imageNames}，由服务端经递归解析器真的查一遍
 * （{@link com.mrcrayfish.furniture.web.NameLookup}，异步），回答来了再用 {@link #withImageHosts} 加进来放行；查不到、超时的不放行
 * （旁观者就看不到那些图）。放行的主机最后还要过 YouranMod 的检查（只收服务器内网里、不是玩家地址的）。
 */
public final class PageFacts
{
    public static final PageFacts NONE = new PageFacts(Collections.<Ipv6Address>emptySet(), Collections.<String>emptySet(),
            Collections.<Integer, Boolean>emptyMap(), Collections.<Integer, int[]>emptyMap());

    /** 图片所在的主机（地址已知的：IPv6 字面量，以及域名查到以后加进来的） */
    public final Set<Ipv6Address> imageHosts;
    /** 图片所在主机的域名（A-label，去重；要经 DNS 查成地址才放行） */
    public final Set<String> imageNames;
    /** 文本框（单行、多行、密码）的编号 → 是不是密码框（同步打字时用，见 {@link TypingSync}） */
    public final Map<Integer, Boolean> textFields;
    /** 画板的编号 → {宽, 高}（同步画板内容时用，见 {@link CanvasSync}） */
    public final Map<Integer, int[]> canvases;

    private PageFacts(Set<Ipv6Address> imageHosts, Set<String> imageNames, Map<Integer, Boolean> textFields, Map<Integer, int[]> canvases)
    {
        this.imageHosts = Collections.unmodifiableSet(imageHosts);
        this.imageNames = Collections.unmodifiableSet(imageNames);
        this.textFields = Collections.unmodifiableMap(textFields);
        this.canvases = Collections.unmodifiableMap(canvases);
    }

    /** 解析页面（主机名先不查：域名记在 {@link #imageNames}） */
    public static PageFacts of(PageSnapshot s) throws PageDocument.Invalid
    {
        PageDocument d = PageDocument.parse(s.html, s.mode, s.url, id -> null, 600);
        Set<Ipv6Address> hosts = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for(String img : d.images)
        {
            String host = UrlInput.hostOf(img);
            if(host == null || host.isEmpty())
                continue;
            if(host.startsWith("["))
            {
                Ipv6Address a = host.endsWith("]") ? Ipv6Address.tryParse(host.substring(1, host.length() - 1)) : null;
                if(a != null)
                    hosts.add(a);
            }
            else
            {
                names.add(host.toLowerCase(java.util.Locale.ROOT));
            }
        }
        Map<Integer, Boolean> fields = new HashMap<>();
        Map<Integer, int[]> canvases = new HashMap<>();
        for(PageDocument.Control c : d.controls)
        {
            if(c.isTextual())
                fields.put(c.n, c.kind == PageDocument.Kind.PASSWORD);
            else if(c.kind == PageDocument.Kind.CANVAS)
                canvases.put(c.n, new int[]{c.canvasWidth, c.canvasHeight});
        }
        return new PageFacts(hosts, names, fields, canvases);
    }

    /** 加上域名查到的地址（其余不变）；没有新的时返回自己 */
    public PageFacts withImageHosts(Collection<Ipv6Address> resolved)
    {
        Set<Ipv6Address> hosts = new LinkedHashSet<>(imageHosts);
        if(!hosts.addAll(resolved))
            return this;
        return new PageFacts(hosts, imageNames, textFields, canvases);
    }
}
