package com.mrcrayfish.furniture.web;

import moe.qingu.youran.api.network.HostContext;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsRegistry;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.dns.Registrar;
import moe.qingu.youran.api.network.dns.Registration;
import moe.qingu.youran.api.network.dns.Whois;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.network.DnsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 网站在自己主机上跑 DNS、以家具模组的身份注册；被占、要等时只记日志 */
class WebsitesDnsTest
{
    static final Ipv6Address TLD = Ipv6Address.parse("fd00::2:53");
    static final Ipv6Address SITE = Ipv6Address.parse("fd00::1:7");

    @TempDir
    File dir;
    final ManualLoop loop = new ManualLoop();
    DefaultRegistrar yr;

    static NetStack stack(ManualLoop loop, Ipv6Address a)
    {
        NetStack s = new NetStack(loop, p -> {}, new Random(1));
        s.addAddress(a);
        return s;
    }

    static HostContext host(NetStack s)
    {
        return new HostContext()
        {
            @Override
            public String key()
            {
                return "cfm:test";
            }

            @Override
            public Ipv6Address address()
            {
                return s.primaryAddress();
            }

            @Override
            public NetStack stack()
            {
                return s;
            }

            @Override
            public <T> CompletableFuture<T> onMainThread(Supplier<T> task)
            {
                return CompletableFuture.completedFuture(task.get());
            }
        };
    }

    @BeforeEach
    void setUp()
    {
        DnsState.reset();
        DnsState.bindRoot(DnsServer.start(stack(loop, Ipv6Address.parse("fd00::1:53"))));
        DnsServer tld = DnsServer.start(stack(loop, TLD));
        yr = new DefaultRegistrar("youranmod", "yr", tld, new File(dir, "yr.json"));
        DnsRegistry.register("yr", yr, Collections.singletonList(TLD));
    }

    @Test
    void registersSecondLevelAndAnswersItself()
    {
        DnsServer dns = Websites.startDns(host(stack(loop, SITE)), "minebay.yr");
        loop.advance(0);
        Whois w = DnsRegistry.whois("minebay.yr");
        assertEquals("cfm", w.owner);
        assertEquals(Collections.singletonList(SITE), w.servers);
        // 网站自己的 DNS 回答 minebay.yr
        DnsMessage m = dns.answer(DnsMessage.query(1, "minebay.yr", DnsMessage.TYPE_AAAA, false));
        assertEquals(SITE, m.answers.get(0).address);
        assertEquals("minebay.yr", Websites.secondLevel("shop.minebay.yr"));
    }

    @Test
    void takenNameOnlyLogs()
    {
        yr.register("minebay.yr", "othermod", Collections.singletonList(TLD));
        Websites.startDns(host(stack(loop, SITE)), "minebay.yr");
        assertEquals("othermod", DnsRegistry.whois("minebay.yr").owner);
    }

    @Test
    void missingTldOnlyLogs()
    {
        Websites.startDns(host(stack(loop, SITE)), "minebay.nothing");
        assertNull(DnsRegistry.whois("minebay.nothing"));
    }

    @Test
    void pendingIsLoggedWhenDone()
    {
        Registration[] last = new Registration[1];
        DnsRegistry.register("wait", new Registrar()
        {
            @Override
            public String modId()
            {
                return "waiter";
            }

            @Override
            public Registration register(String name, Registrar registrar, Collection<Ipv6Address> servers)
            {
                return register(name, registrar.modId(), servers);
            }

            @Override
            public Registration register(String name, String modId, Collection<Ipv6Address> servers)
            {
                last[0] = Registration.pending(null);
                return last[0];
            }

            @Override
            public Whois whois(String name)
            {
                return null;
            }
        }, Collections.singletonList(TLD));
        Websites.startDns(host(stack(loop, SITE)), "shop.wait");
        assertNotNull(last[0]);
        assertEquals(Registration.Status.PENDING, last[0].status());
        last[0].fail("审核没过");
        assertEquals(Registration.Status.FAILURE, last[0].status());
    }
}
