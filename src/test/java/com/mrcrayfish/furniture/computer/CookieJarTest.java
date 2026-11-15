package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 电脑的 Cookie：RFC 6265 的解析与匹配（Domain / Path / Secure / 过期 / SameSite）、上限、编码 */
class CookieJarTest
{
    static final long NOW = 1_800_000_000_000L;
    static final CookieJar.Context SAME = CookieJar.Context.USER;

    @Test
    void hostOnlyAndDomain()
    {
        CookieJar j = new CookieJar();
        assertTrue(j.set("sid=1", "https://portal.yr/login", NOW));
        assertTrue(j.set("wide=2; Domain=.portal.yr", "https://portal.yr/", NOW));
        assertEquals("sid=1; wide=2", j.header("https://portal.yr/", SAME, NOW));
        assertEquals("wide=2", j.header("https://a.portal.yr/", SAME, NOW), "没写 Domain 的只给原主机");
        assertNull(j.header("https://captcha.yr/", SAME, NOW));
        assertNull(j.header("https://evilportal.yr/", SAME, NOW));
        // 不能设给别的域名、顶级域（公共后缀）
        assertFalse(j.set("x=1; Domain=minebay.yr", "https://portal.yr/", NOW));
        assertFalse(j.set("x=1; Domain=yr", "https://portal.yr/", NOW));
        assertFalse(j.set("x=1; Domain=.yr", "https://portal.yr/", NOW));
        assertFalse(j.set("x=1; Domain=sub.portal.yr", "https://portal.yr/", NOW), "不能设给下级域");
        // 按 IP 访问：Domain 只能是这个地址
        CookieJar ip = new CookieJar();
        assertTrue(ip.set("a=1", "http://[fd00::1]/", NOW));
        assertFalse(ip.set("b=1; Domain=fd00::2", "http://[fd00::1]/", NOW));
        assertEquals("a=1", ip.header("http://[fd00::1]:8080/x", SAME, NOW));
        assertNull(ip.header("http://[fd00::2]/", SAME, NOW));
    }

    @Test
    void paths()
    {
        CookieJar j = new CookieJar();
        j.set("root=1; Path=/", "https://portal.yr/a/b", NOW);
        j.set("dflt=2", "https://portal.yr/a/b?x=1", NOW);
        j.set("deep=3; Path=/a/b", "https://portal.yr/", NOW);
        j.set("bad=4; Path=nope", "https://portal.yr/c/d", NOW);
        assertEquals("deep=3; dflt=2; root=1", j.header("https://portal.yr/a/b/c", SAME, NOW), "路径长的在前");
        assertEquals("dflt=2; root=1", j.header("https://portal.yr/a/x", SAME, NOW));
        assertEquals("root=1", j.header("https://portal.yr/ab", SAME, NOW), "/a 不匹配 /ab");
        assertEquals("bad=4; root=1", j.header("https://portal.yr/c/x", SAME, NOW), "Path 不以 / 开头时用默认路径");
    }

    @Test
    void secureOnlyOverHttps()
    {
        CookieJar j = new CookieJar();
        assertFalse(j.set("s=1; Secure", "http://portal.yr/", NOW), "http 不能设 Secure");
        assertTrue(j.set("s=1; Secure", "https://portal.yr/", NOW));
        assertTrue(j.set("p=2", "http://portal.yr/", NOW));
        assertEquals("p=2", j.header("http://portal.yr/", SAME, NOW));
        assertEquals("s=1; p=2", j.header("https://portal.yr/", SAME, NOW));
        assertFalse(j.set("s=evil", "http://portal.yr/", NOW), "http 不能覆盖 Secure 的");
        assertFalse(j.set("s=; Max-Age=0", "http://portal.yr/", NOW), "也不能删");
        assertTrue(j.header("https://portal.yr/", SAME, NOW).contains("s=1"));
        assertFalse(j.set("__Secure-a=1", "https://portal.yr/", NOW));
        assertTrue(j.set("__Secure-a=1; Secure", "https://portal.yr/", NOW));
        assertFalse(j.set("__Host-a=1; Secure; Domain=portal.yr", "https://portal.yr/", NOW));
        assertFalse(j.set("__Host-a=1; Secure; Path=/x", "https://portal.yr/", NOW));
        assertTrue(j.set("__Host-a=1; Secure; Path=/", "https://portal.yr/", NOW));
    }

    @Test
    void expiry()
    {
        CookieJar j = new CookieJar();
        j.set("a=1; Max-Age=60", "https://portal.yr/", NOW);
        j.set("b=1; Expires=Wed, 21 Oct 2037 07:28:00 GMT", "https://portal.yr/", NOW);
        j.set("c=1; Expires=Wed, 21 Oct 2037 07:28:00 GMT; Max-Age=10", "https://portal.yr/", NOW);
        j.set("d=1", "https://portal.yr/", NOW);
        j.set("e=1; Expires=Thu, 01 Jan 1970 00:00:00 GMT", "https://portal.yr/", NOW);
        assertEquals("a=1; b=1; c=1; d=1", j.header("https://portal.yr/", SAME, NOW));
        assertEquals("a=1; b=1; d=1", j.header("https://portal.yr/", SAME, NOW + 11_000), "Max-Age 优先于 Expires");
        assertEquals("b=1; d=1", j.header("https://portal.yr/", SAME, NOW + 61_000));
        // 删除：Max-Age=0 或过去的 Expires
        j.set("d=; Max-Age=0", "https://portal.yr/", NOW);
        j.set("b=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT", "https://portal.yr/", NOW);
        assertEquals("a=1; c=1", j.header("https://portal.yr/", SAME, NOW));
        // 最长 400 天
        CookieJar k = new CookieJar();
        k.set("long=1; Max-Age=999999999", "https://portal.yr/", NOW);
        assertEquals(NOW + CookieJar.MAX_AGE_MS, k.all().get(0).expiry);
        assertFalse(k.all().get(0).expiry == Long.MAX_VALUE);
        assertTrue(new CookieJar().set("s=1", "https://portal.yr/", NOW));
    }

    @Test
    void dates()
    {
        long t = 2139722880000L; // 2037-10-21 07:28:00 UTC
        assertEquals(t, (long) CookieDate.parse("Wed, 21 Oct 2037 07:28:00 GMT"));
        assertEquals(t, (long) CookieDate.parse("Wednesday, 21-Oct-37 07:28:00 GMT"));
        assertEquals(t, (long) CookieDate.parse("Wed Oct 21 07:28:00 2037"));
        assertNull(CookieDate.parse("Wed, 30 Feb 2037 07:28:00 GMT"));
        assertNull(CookieDate.parse("garbage"));
        assertNull(CookieDate.parse("Wed, 21 Oct 2037 25:28:00 GMT"));
    }

    @Test
    void sameSite()
    {
        CookieJar j = new CookieJar();
        j.set("lax=1", "https://portal.yr/", NOW);
        j.set("strict=1; SameSite=Strict", "https://portal.yr/", NOW);
        j.set("none=1; SameSite=None; Secure", "https://portal.yr/", NOW);
        assertFalse(j.set("none2=1; SameSite=None", "https://portal.yr/", NOW), "None 要带 Secure");
        String u = "https://portal.yr/oauth";
        assertEquals("lax=1; strict=1; none=1", j.header(u, SAME, NOW));
        assertEquals("lax=1; none=1", j.header(u, new CookieJar.Context(true, true, true), NOW), "跨站的顶层 GET：Lax 发，Strict 不发");
        assertEquals("none=1", j.header(u, new CookieJar.Context(true, false, true), NOW), "跨站的表单 POST：Lax 不发");
        assertEquals("none=1", j.header(u, new CookieJar.Context(false, true, true), NOW), "跨站的图片：Lax 不发");
        assertEquals("lax=1; strict=1; none=1", j.header(u, new CookieJar.Context(false, true, false), NOW), "同站的图片都发");
        assertTrue(CookieJar.sameSite("https://a.portal.yr/x", "http://portal.yr/"));
        assertFalse(CookieJar.sameSite("https://portal.yr/", "https://captcha.yr/"));
        assertEquals("portal.yr", CookieJar.site("x.y.portal.yr"));
        assertEquals("[fd00::1]", CookieJar.site("[fd00::1]"));
    }

    @Test
    void limits()
    {
        CookieJar j = new CookieJar();
        for(int i = 0; i < CookieJar.MAX_PER_DOMAIN + 5; i++)
            assertTrue(j.set("c" + i + "=v", "https://a.yr/", NOW + i));
        assertEquals(CookieJar.MAX_PER_DOMAIN, j.size(), "每个域名有上限");
        assertFalse(j.header("https://a.yr/", SAME, NOW + 100).contains("c0="), "最早的先丢");
        for(int d = 0; d < 5; d++)
        {
            for(int i = 0; i < CookieJar.MAX_PER_DOMAIN; i++)
                j.set("k" + i + "=v", "https://d" + d + ".yr/", NOW + 1000 + d * 100 + i);
        }
        assertEquals(CookieJar.MAX_COOKIES, j.size(), "整台电脑有上限");
        StringBuilder big = new StringBuilder();
        for(int i = 0; i < 4100; i++)
            big.append('x');
        assertFalse(j.set("big=" + big, "https://a.yr/", NOW), "一条太大");
        CookieJar bytes = new CookieJar();
        for(int i = 0; i < 10; i++)
            bytes.set("b" + i + "=" + big.substring(0, 2000), "https://s" + i + ".yr/", NOW + i);
        assertTrue(bytes.totalBytes() <= CookieJar.MAX_TOTAL_BYTES, "合计字节有上限：" + bytes.totalBytes());
        assertTrue(bytes.header("https://s9.yr/", SAME, NOW + 20) != null, "刚收的那条留着");
        assertFalse(new CookieJar().set("a\u0001=1", "https://a.yr/", NOW), "控制字符");
        assertFalse(new CookieJar().set("x=1", "ftp://a.yr/", NOW));
    }

    @Test
    void encodeKeepsRemainingLifetimeOnTheClient()
    {
        CookieJar j = new CookieJar();
        j.set("a=1; Max-Age=60; HttpOnly", "https://portal.yr/p/q", NOW);
        j.set("s=2; Secure; SameSite=Strict", "https://portal.yr/", NOW);
        // 服务端存档：绝对时刻
        CookieJar saved = CookieJar.decode(j.encode(true, NOW), true, NOW + 30_000);
        assertNotNull(saved);
        assertEquals(2, saved.size());
        assertEquals("s=2", CookieJar.decode(j.encode(true, NOW), true, NOW + 61_000).header("https://portal.yr/p/x", SAME, NOW + 61_000));
        // 发给客户端：还剩多久（客户端时钟差一天也照样剩 60 秒）
        long clientNow = NOW - 86_400_000L;
        CookieJar client = CookieJar.decode(j.encode(false, NOW), false, clientNow);
        assertEquals("a=1; s=2", client.header("https://portal.yr/p/x", SAME, clientNow + 59_000));
        assertEquals("s=2", client.header("https://portal.yr/p/x", SAME, clientNow + 61_000), "会话 Cookie 不过期");
        CookieJar.Cookie a = client.all().stream().filter(c -> c.name.equals("a")).findFirst().get();
        assertTrue(a.httpOnly);
        assertEquals("/p", a.path);
        assertEquals(CookieJar.SameSite.STRICT, client.all().stream().filter(c -> c.name.equals("s")).findFirst().get().sameSite);
        // 坏数据
        assertNull(CookieJar.decode(new byte[]{9, 9, 9}, true, NOW));
        byte[] e = j.encode(true, NOW);
        assertNull(CookieJar.decode(Arrays.copyOf(e, e.length - 3), true, NOW));
        assertEquals(1, j.siteCount());
    }
}
