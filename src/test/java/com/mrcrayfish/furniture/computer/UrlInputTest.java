package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlInputTest
{
    static String url(String typed)
    {
        UrlInput.Result r = UrlInput.fromAddressBar(typed);
        assertTrue(r.isOk(), typed + " → " + r.error);
        return r.url;
    }

    static UrlInput.Error error(String typed)
    {
        UrlInput.Result r = UrlInput.fromAddressBar(typed);
        assertFalse(r.isOk(), typed + " 应该不行");
        return r.error;
    }

    @Test
    void bareDomainsGetHttp()
    {
        assertEquals("http://minebay.yr/", url("minebay.yr"));
        assertEquals("http://test.yr/abc", url("test.yr/abc"));
        assertEquals("http://test.yr/?q=1", url("  test.yr?q=1 "));
        assertEquals("http://test.yr:8080/x", url("test.yr:8080/x"));
        assertEquals("https://minebay.yr/", url("HTTPS://MineBay.YR"));
        assertEquals("http://test.yr/", url("http://test.yr:80/"));
        assertEquals("https://test.yr:8443/", url("https://test.yr:8443"));
        assertEquals(UrlInput.HOME, url("about:home"));
    }

    @Test
    void ipv6()
    {
        assertEquals("http://[fd00::2]/", url("http://[fd00::2]/"));
        assertEquals("http://[fd00::2]:8080/a", url("[FD00:0::2]:8080/a"));
        assertEquals("http://[fd45:b72b:6dcb:1::53]/", url("fd45:b72b:6dcb:1::53"));
        assertEquals("http://[fd3b:91b5:ceac:1::2]/", url("[fd3b:91b5:ceac:1::2]"), "只写带方括号的地址");
        assertEquals("https://[fd00::2]/", url("https://[fd00::2]:443"));
        assertEquals(UrlInput.Error.MALFORMED, error("http://[fd00::zz]/"));
        assertEquals(UrlInput.Error.MALFORMED, error("http://[fd00::2/"));
    }

    @Test
    void unsupportedSchemesAreNamed()
    {
        for(String s : new String[]{"file:///etc/passwd", "ftp://x.yr/", "javascript:alert(1)", "data:text/html,hi", "mailto:a@b.yr", "jar:file:/x!/y"})
        {
            UrlInput.Result r = UrlInput.fromAddressBar(s);
            assertEquals(UrlInput.Error.UNSUPPORTED_SCHEME, r.error, s);
            assertEquals(s.substring(0, s.indexOf(':')).toLowerCase(), r.detail);
        }
    }

    @Test
    void malformedAndLimits()
    {
        assertEquals(UrlInput.Error.EMPTY, error("   "));
        assertEquals(UrlInput.Error.MALFORMED, error("http://user@test.yr/"));
        assertEquals(UrlInput.Error.MALFORMED, error("http://te st.yr/"));
        assertEquals(UrlInput.Error.MALFORMED, error("test.yr:99999"));
        assertEquals(UrlInput.Error.MALFORMED, error("http://"));
        StringBuilder sb = new StringBuilder("test.yr/");
        while(sb.length() <= UrlInput.MAX_LENGTH)
            sb.append('a');
        assertEquals(UrlInput.Error.TOO_LONG, error(sb.toString()));
    }

    @Test
    void encodesPathAndHost()
    {
        assertEquals("http://test.yr/%E4%BD%A0%20%E5%A5%BD?q=%E4%B8%AD", url("test.yr/你 好?q=中"));
        assertEquals("http://test.yr/a%2Fb", url("test.yr/a%2Fb"), "已有的 %XX 不重复编码");
        assertEquals("http://test.yr/x", url("test.yr/x#frag"), "去掉片段");
        assertEquals("http://xn--fiq228c.yr/", url("中文.yr"));
    }

    @Test
    void resolveRelative()
    {
        String base = "https://minebay.yr/shop/list?p=1";
        assertEquals("https://minebay.yr/buy", UrlInput.resolve(base, "/buy"));
        assertEquals("https://minebay.yr/shop/item", UrlInput.resolve(base, "item"));
        assertEquals("https://minebay.yr/shop/list?p=2", UrlInput.resolve(base, "?p=2"));
        assertEquals("https://minebay.yr/", UrlInput.resolve(base, "../"));
        assertEquals("http://test.yr/", UrlInput.resolve(base, "http://test.yr"));
        assertEquals("https://other.yr/x", UrlInput.resolve(base, "//other.yr/x"));
        assertEquals(base, UrlInput.resolve(base, ""));
        assertNull(UrlInput.resolve(base, "file:///etc/passwd"));
        assertNull(UrlInput.resolve(base, "javascript:alert(1)"));
        assertNull(UrlInput.resolve(UrlInput.HOME, "relative"));
        assertEquals("https://minebay.yr/", UrlInput.resolve(UrlInput.HOME, "https://minebay.yr/"));
    }

    @Test
    void internationalizedDomainNames()
    {
        // 地址栏能输入 Unicode 域名，请求用 A-label；显示时转回 Unicode
        assertEquals("http://xn--0zwm56d.yr/", UrlInput.fromAddressBar("测试.yr").url);
        assertEquals("https://xn--0zwm56d.yr/a?b=1", UrlInput.fromAddressBar("https://测试.yr/a?b=1").url);
        assertEquals("https://测试.yr/a?b=1", UrlInput.display("https://xn--0zwm56d.yr/a?b=1"));
        assertEquals("http://商店.悠然.yr:8080/", UrlInput.display(UrlInput.fromAddressBar("商店.悠然.yr:8080").url));
        // 混用文字系统（拉丁混西里尔）：显示 xn-- 形式
        String spoof = UrlInput.fromAddressBar("http://p\u0430ypal.yr/").url;
        assertTrue(spoof.startsWith("http://xn--"), spoof);
        assertEquals(spoof, UrlInput.display(spoof));
        // 页面里的链接写成 Unicode 域名也转
        assertEquals("http://xn--0zwm56d.yr/x", UrlInput.resolve("http://test.yr/", "http://测试.yr/x"));
        // 转不了的名字：格式不对
        assertEquals(UrlInput.Error.MALFORMED, UrlInput.fromAddressBar("http://a_b.yr/").error);
        assertEquals("http://[fd00::1]/", UrlInput.display("http://[fd00::1]/"));
        assertEquals("about:home", UrlInput.display("about:home"));
    }

    @Test
    void certificateNamesShowBothForms()
    {
        assertEquals(java.util.Arrays.asList("DNS:xn--0zwm56d.yr（测试.yr）", "DNS:test.yr"),
                UrlInput.certificateNames(java.util.Arrays.asList("DNS:xn--0zwm56d.yr", "DNS:test.yr")));
        assertEquals("测试.yr", UrlInput.unicodeHost("xn--0zwm56d.yr"));
        assertEquals("[fd00::1]", UrlInput.unicodeHost("[fd00::1]"));
    }
}
