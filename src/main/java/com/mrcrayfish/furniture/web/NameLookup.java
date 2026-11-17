package com.mrcrayfish.furniture.web;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 服务端查名字（悠然服务器魔改）：用家具模组网站主机的协议栈，经 YouranMod 的递归解析器发真的 DNS 查询（AAAA）。
 * 一批名字一起查：去重，最多 {@value #MAX_NAMES} 个，到时限还没回答的、查不到的都不算。
 */
public final class NameLookup
{
    public static final int MAX_NAMES = 16;
    /** 一批名字最多等多久（毫秒） */
    public static final long TIMEOUT_MS = 5_000;

    private static volatile NetStack stack;

    private NameLookup() {}

    /** 网站主机起来时记下它的协议栈（网络线程） */
    static void attach(NetStack s)
    {
        stack = s;
    }

    /** 经递归解析器查这些名字，结果是查到的全部地址（任意线程；future 在网络线程上完成）。网络没在运行时以空集结束 */
    public static CompletableFuture<Set<Ipv6Address>> resolveAll(Collection<String> names)
    {
        NetStack s = stack;
        Ipv6Address resolver = YouranNetworkApi.resolverAddress();
        if(s == null || s.isClosed() || resolver == null || names.isEmpty())
            return CompletableFuture.completedFuture(Collections.<Ipv6Address>emptySet());
        CompletableFuture<Set<Ipv6Address>> out = new CompletableFuture<>();
        s.loop().execute(() -> {
            DnsResolver d = new DnsResolver(s, resolver);
            resolveAll(names, n -> d.resolveAaaa(n).thenApply(r -> r.rcode == DnsMessage.RCODE_OK ? r.addresses : Collections.<Ipv6Address>emptyList()),
                    s.loop(), TIMEOUT_MS).whenComplete((v, e) -> out.complete(v == null ? Collections.<Ipv6Address>emptySet() : v));
        });
        return out;
    }

    /**
     * 一批名字一起查（纯逻辑，测试用）：每个名字问一次 {@code lookup}，全部回答了、或者到了 {@code timeoutMs}（按 {@code loop} 的时钟）时结束，
     * 结果是已经回答了的地址；失败的、没回答的不算。在 {@code loop} 的线程上调用
     */
    static CompletableFuture<Set<Ipv6Address>> resolveAll(Collection<String> names, Function<String, CompletableFuture<List<Ipv6Address>>> lookup,
                                                         EventLoop loop, long timeoutMs)
    {
        Set<String> unique = new LinkedHashSet<>();
        for(String n : names)
        {
            if(unique.size() >= MAX_NAMES)
                break;
            unique.add(n.toLowerCase(java.util.Locale.ROOT));
        }
        CompletableFuture<Set<Ipv6Address>> out = new CompletableFuture<>();
        Set<Ipv6Address> found = new LinkedHashSet<>();
        if(unique.isEmpty())
        {
            out.complete(found);
            return out;
        }
        AtomicInteger left = new AtomicInteger(unique.size());
        EventLoop.Timer timer = loop.schedule(() -> finish(out, found), timeoutMs);
        for(String n : unique)
        {
            CompletableFuture<List<Ipv6Address>> f;
            try
            {
                f = lookup.apply(n);
            }
            catch(RuntimeException e)
            {
                f = new CompletableFuture<>();
                f.completeExceptionally(e);
            }
            f.whenComplete((addrs, err) -> {
                synchronized(found)
                {
                    if(!out.isDone() && err == null && addrs != null)
                        found.addAll(addrs);
                }
                if(left.decrementAndGet() == 0)
                {
                    timer.cancel();
                    finish(out, found);
                }
            });
        }
        return out;
    }

    private static void finish(CompletableFuture<Set<Ipv6Address>> out, Set<Ipv6Address> found)
    {
        synchronized(found)
        {
            out.complete(Collections.unmodifiableSet(new LinkedHashSet<>(found)));
        }
    }
}
