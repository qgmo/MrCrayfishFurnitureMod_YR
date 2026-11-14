package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.client.browser.PageDocument;
import moe.qingu.youran.api.network.Ipv6Address;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * 服务端从操作者上传的页面里自己解析出来的东西（不信客户端报的）：页面里的图片在哪些主机上，用来给旁观者的玩家临时地址放行
 * （服主 11-14：旁观者自己下载图片）。解析与浏览器完全一样（{@link PageDocument}：同主机的图、每页最多 16 张）。
 * <p>
 * 主机名换成地址：IPv6 字面量直接用；域名只查 YouranMod 自己登记的 {@code .yr} / {@code .com} 名字（服务端直接查表，不发 DNS），
 * 查不到的不放行（旁观者就看不到那些图）。放行的主机最后还要过 YouranMod 的检查（只收服务器内网里、不是玩家地址的）。
 */
public final class PageFacts
{
    public static final PageFacts NONE = new PageFacts(Collections.<Ipv6Address>emptySet());

    /** 图片所在的主机 */
    public final Set<Ipv6Address> imageHosts;

    private PageFacts(Set<Ipv6Address> imageHosts)
    {
        this.imageHosts = Collections.unmodifiableSet(imageHosts);
    }

    /**
     * @param resolve 域名（A-label）→ 地址；查不到返回 null
     */
    public static PageFacts of(PageSnapshot s, Function<String, Ipv6Address> resolve) throws PageDocument.Invalid
    {
        PageDocument d = PageDocument.parse(s.html, s.mode, s.url, id -> null, 600);
        Set<Ipv6Address> hosts = new LinkedHashSet<>();
        for(String img : d.images)
        {
            Ipv6Address a = addressOf(UrlInput.hostOf(img), resolve);
            if(a != null)
                hosts.add(a);
        }
        return new PageFacts(hosts);
    }

    @Nullable
    static Ipv6Address addressOf(@Nullable String host, Function<String, Ipv6Address> resolve)
    {
        if(host == null || host.isEmpty())
            return null;
        if(host.startsWith("["))
            return host.endsWith("]") ? Ipv6Address.tryParse(host.substring(1, host.length() - 1)) : null;
        return resolve.apply(host);
    }
}
