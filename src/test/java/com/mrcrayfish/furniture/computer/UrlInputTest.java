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
}
