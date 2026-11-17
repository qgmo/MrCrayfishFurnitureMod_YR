package com.mrcrayfish.furniture.web;

import com.mrcrayfish.furniture.Reference;
import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.HostContext;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.ca.CertificateClient;
import moe.qingu.youran.api.network.ca.TxtRecords;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.dns.Registrar;
import moe.qingu.youran.api.network.dns.Registration;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 网站注册接口（悠然服务器魔改）：给其他模组注册一个“按 HTTP 请求返回页面”的游戏内网站，建在 YouranMod 的网络接口上。
 * <ul>
 * <li>{@link #register}：在 YouranMod 的机房里登记一台主机（一个 /128）。同一台主机上跑这个域名的 DNS（YouranMod 的默认 DNS 服务），
 * 以家具模组的身份（{@value Reference#MOD_ID}）向顶级域的注册局注册域名所在的二级域名；注册不成（名字被别人占了、注册局要等）只记日志。
 * 开服时 80 口开 HTTP；YouranMod 的证书签发服务开着（服务器配了中间 CA）时 443 口再开 HTTPS，证书由这台主机自己申请
 * （DNS 认证，用自己的 DNS 加 TXT；剩 30 天时自动续），存在世界存档的 {@code cfm/certs/<主机>/}（见 {@link WebCertificates}），
 * 开服时还能用就直接用。模组加载阶段（预初始化到初始化）调用。</li>
 * <li>{@link WebServer#serve}：在任意一个服务端协议栈上提供网站。以后挂在设备地址上的网站（玩家把电脑当网页服务器）用它：
 * 设备用 {@code DeviceHandle.attachEndpoint} 接一个服务端协议栈，证书用 {@code CertificateClient.start(协议栈, 名字, null)} 自己申请
 * （HTTP 认证，{@link WebServer} 的 80 口自动回）。</li>
 * </ul>
 * 处理函数在网络线程上调用；扣物品、发货等要动游戏状态的事用 {@link WebContext#onMainThread}。
 */
public final class Websites
{
    private static final Logger LOG = LogManager.getLogger("cfm");

    private Websites() {}

    /**
     * @param hostKey 主机登记名，写成“模组 ID:名字”（如 {@code cfm:minebay}）；同一个名字以后拿到的地址不变
     * @param domain  域名（如 {@code minebay.yr}）；国际化域名可以直接写 Unicode（如 {@code 商店.悠然.yr}），转成 A-label，
     *                转不了时抛出 {@link IllegalArgumentException}
     */
    public static void register(String hostKey, String domain, WebHandler handler)
    {
        String name = DomainNames.toAscii(domain);
        if (name.indexOf('.') < 0)
        {
            throw new IllegalArgumentException("域名要在某个顶级域下面：" + domain);
        }
        YouranNetworkApi.registerHost(hostKey, YouranNetworkApi.YOURANMOD_DATACENTER, ctx -> {
            DnsServer dns = startDns(ctx, name);
            // 证书自己向证书签发服务申请：自己跑着这个区的 DNS，用 DNS 认证（加删 _acme-challenge 的 TXT），到期前自动续；
            // 证书与私钥存在世界存档的 cfm/certs/<主机>/，开服时还能用就直接用
            TlsServerContext tls = CertificateClient.start(ctx.stack(), Collections.singletonList(name), TxtRecords.of(dns),
                    WebCertificates.store(hostKey)).tls();
            WebContext context = contextOf(ctx, tls != null);
            WebServer.serve(ctx.stack(), handler, context, tls);
            // 服务端要查名字时（如旁观者看的页面里图片在哪）用网站主机的协议栈经递归解析器查
            NameLookup.attach(ctx.stack());
            handler.started(context);
        });
    }

    /** 网络线程：在这台主机上跑域名的 DNS，注册二级域名。注册不成只记日志。返回开起来的 DNS 服务 */
    static DnsServer startDns(HostContext ctx, String name)
    {
        String zone = secondLevel(name);
        String tld = zone.substring(zone.indexOf('.') + 1);
        DnsServer dns = DnsServer.start(ctx.stack());
        dns.addZone(zone);
        dns.setAddresses(name, Collections.singletonList(ctx.address()));
        Registrar registrar = DnsRegistry.registrar(tld);
        if (registrar == null)
        {
            LOG.warn("游戏内网站 {}：顶级域 .{} 没有注册局，注册不了", name, tld);
            return dns;
        }
        report(name, zone, registrar.register(zone, Reference.MOD_ID, Collections.singletonList(dns.address())));
        return dns;
    }

    /** 注册的结果写日志：失败、等待都不抛 */
    static void report(String name, String zone, Registration r)
    {
        switch (r.status())
        {
            case SUCCESS:
                LOG.info("游戏内网站 {}：已注册 {}，DNS 在 {}", name, zone, r.whois().servers);
                break;
            case FAILURE:
                LOG.warn("游戏内网站 {}：注册 {} 失败：{}", name, zone, r.reason());
                break;
            default:
                LOG.info("游戏内网站 {}：注册 {} 在等注册局处理", name, zone);
                r.whenDone(x -> LOG.info("游戏内网站 {}：注册 {} 有结果了：{}", name, zone, x));
                break;
        }
    }

    /** {@code a.b.yr} 所在的二级域名 {@code b.yr} */
    static String secondLevel(String name)
    {
        int last = name.lastIndexOf('.');
        int prev = name.lastIndexOf('.', last - 1);
        return name.substring(prev + 1);
    }

    static WebContext contextOf(HostContext host, boolean tls)
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

            @Override
            public moe.qingu.youran.api.network.stack.NetStack stack()
            {
                return host.stack();
            }

            @Override
            public boolean hasTls()
            {
                return tls;
            }
        };
    }
}
