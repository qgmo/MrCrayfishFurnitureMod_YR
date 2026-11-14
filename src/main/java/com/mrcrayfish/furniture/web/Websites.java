package com.mrcrayfish.furniture.web;

import moe.qingu.youran.api.network.HostContext;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.YouranNetworkApi;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 网站注册接口（悠然服务器魔改）：给其他模组注册一个“按 HTTP 请求返回页面”的游戏内网站，建在 YouranMod 的网络接口上。
 * <ul>
 * <li>{@link #register}：在 YouranMod 的机房里登记一台主机（一个 /128）和一个域名（{@code .yr}，或 {@code .com} 下的少数特殊名字），
 * 开服时 80 口开 HTTP；服务器配了中间 CA 时 443 口再开 HTTPS（证书按域名现签）。模组加载阶段（预初始化到初始化）调用。</li>
 * <li>{@link WebServer#serve}：在任意一个服务端协议栈上提供网站。以后挂在设备地址上的网站（玩家把电脑当网页服务器）用它：
 * 设备用 {@code DeviceHandle.attachEndpoint} 接一个服务端协议栈，TLS 用 {@code YouranNetworkApi.serverTls(名字)}。</li>
 * </ul>
 * 处理函数在网络线程上调用；扣物品、发货等要动游戏状态的事用 {@link WebContext#onMainThread}。
 */
public final class Websites
{
    private Websites() {}

    /**
     * @param hostKey 主机登记名，写成“模组 ID:名字”（如 {@code cfm:minebay}）；同一个名字以后拿到的地址不变
     * @param domain  域名（如 {@code minebay.yr}）；国际化域名可以直接写 Unicode（如 {@code 商店.悠然.yr}），YouranMod 登记时转成 A-label，
     *                转不了时抛出 {@link IllegalArgumentException}
     */
    public static void register(String hostKey, String domain, WebHandler handler)
    {
        YouranNetworkApi.registerHost(hostKey, YouranNetworkApi.YOURANMOD_DATACENTER, ctx -> WebServer.serve(ctx.stack(), handler, contextOf(ctx), ctx.tls()));
        YouranNetworkApi.registerDomainName(domain, hostKey);
    }

    static WebContext contextOf(HostContext host)
    {
        return new WebContext()
        {
            @Override
            public Ipv6Address address()
            {
                return host.address();
            }

            @Override
            public <T> CompletableFuture<T> onMainThread(Supplier<T> task)
            {
                return host.onMainThread(task);
            }
        };
    }
}
