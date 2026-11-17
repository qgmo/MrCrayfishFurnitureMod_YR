package com.mrcrayfish.furniture.web;

import com.mrcrayfish.furniture.computer.PageFacts;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.PageParser;
import moe.qingu.youran.api.network.Ipv6Address;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旁观者放行的图片主机：页面里的域名经解析器异步查，回答来了才放行；查无此名、超时的不放行；去重、最多 16 个（假时钟）
 */
class NameLookupTest
{
    static final Ipv6Address MINEBAY = Ipv6Address.parse("fd00:1:2:1::7");
    static final Ipv6Address TEST = Ipv6Address.parse("fd00:1:2:1::2");

    final ManualLoop loop = new ManualLoop();
    /** 假的解析器：每个名字一个还没回答的查询 */
    final Map<String, CompletableFuture<List<Ipv6Address>>> pending = new HashMap<>();
    final List<String> asked = new ArrayList<>();

    CompletableFuture<List<Ipv6Address>> lookup(String name)
    {
        asked.add(name);
        CompletableFuture<List<Ipv6Address>> f = new CompletableFuture<>();
        pending.put(name, f);
        return f;
    }

    static PageFacts page(String url, String html) throws Exception
    {
        return PageFacts.of(new PageSnapshot(url, html.getBytes(StandardCharsets.UTF_8), PageParser.Mode.HTML, null));
    }

    @Test
    void allowanceAppearsAfterTheAnswer() throws Exception
    {
        PageFacts f = page("http://minebay.yr/", "<img src=a.png><img src=/b.png>");
        assertTrue(f.imageHosts.isEmpty(), "回答来之前不放行");
        CompletableFuture<Set<Ipv6Address>> r = NameLookup.resolveAll(f.imageNames, this::lookup, loop, NameLookup.TIMEOUT_MS);
        assertEquals(Collections.singletonList("minebay.yr"), asked, "去重，只问一次");
        assertFalse(r.isDone());
        pending.get("minebay.yr").complete(Collections.singletonList(MINEBAY));
        assertTrue(r.isDone());
        assertEquals(Collections.singleton(MINEBAY), f.withImageHosts(r.get()).imageHosts);
    }

    @Test
    void nxdomainAndTimeoutAreNotAllowed() throws Exception
    {
        CompletableFuture<Set<Ipv6Address>> r = NameLookup.resolveAll(Arrays.asList("minebay.yr", "nowhere.yr", "slow.yr"), this::lookup, loop, NameLookup.TIMEOUT_MS);
        pending.get("minebay.yr").complete(Collections.singletonList(MINEBAY));
        // 查无此名：解析器给空表
        pending.get("nowhere.yr").complete(Collections.<Ipv6Address>emptyList());
        loop.advance(NameLookup.TIMEOUT_MS - 1);
        assertFalse(r.isDone(), "还在等 slow.yr");
        loop.advance(1);
        assertTrue(r.isDone());
        assertEquals(Collections.singleton(MINEBAY), r.get());
        // 超时以后才回答的不算
        pending.get("slow.yr").complete(Collections.singletonList(TEST));
        assertEquals(Collections.singleton(MINEBAY), r.get());
    }

    @Test
    void failedQueryIsNotAllowed() throws Exception
    {
        CompletableFuture<Set<Ipv6Address>> r = NameLookup.resolveAll(Collections.singletonList("test.yr"), this::lookup, loop, NameLookup.TIMEOUT_MS);
        CompletableFuture<List<Ipv6Address>> q = pending.get("test.yr");
        q.completeExceptionally(new RuntimeException("超时"));
        assertTrue(r.isDone());
        assertTrue(r.get().isEmpty());
    }

    @Test
    void boundedAndEmpty() throws Exception
    {
        List<String> many = new ArrayList<>();
        for(int i = 0; i < 40; i++)
            many.add("site" + i + ".yr");
        many.add("SITE0.yr");
        NameLookup.resolveAll(many, this::lookup, loop, NameLookup.TIMEOUT_MS);
        assertEquals(NameLookup.MAX_NAMES, asked.size());
        assertTrue(NameLookup.resolveAll(Collections.<String>emptyList(), this::lookup, loop, 1000).get().isEmpty());
        // 网络没在运行（没有网站主机）：空集
        assertTrue(NameLookup.resolveAll(Collections.singletonList("a.yr")).get().isEmpty());
    }
}
