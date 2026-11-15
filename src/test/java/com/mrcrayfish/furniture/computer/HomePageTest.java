package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 主页设置：服务器默认 + 这台电脑的，about:home 兜底 */
class HomePageTest
{
    @Test
    void serverDefault()
    {
        String[] p = new String[1];
        assertEquals("http://180.yr/", HomePage.serverDefault("http://180.yr", p));
        assertNull(p[0]);
        assertEquals("http://180.yr/", HomePage.serverDefault("180.yr", p), "可以只写域名");
        assertEquals("https://portal.yr/a", HomePage.serverDefault(" https://portal.yr/a ", p));
        assertEquals(UrlInput.HOME, HomePage.serverDefault("", p));
        assertEquals(UrlInput.HOME, HomePage.serverDefault("about:home", p));
        assertNull(p[0]);
        assertEquals(UrlInput.HOME, HomePage.serverDefault("ftp://x.yr/", p), "写得不对用 about:home");
        assertNotNull(p[0]);
        assertEquals(HomePage.DEFAULT, HomePage.serverDefault(com.mrcrayfish.furniture.handler.ConfigurationHandler.computerHomePage, new String[1]));
    }

    @Test
    void effectiveAndCodec()
    {
        assertEquals("http://180.yr/", HomePage.effective(null, "http://180.yr/"));
        assertEquals("http://wood.yr/", HomePage.effective("http://wood.yr/", "http://180.yr/"));
        String[] d = HomePage.decode(HomePage.encode("http://180.yr/", null));
        assertEquals("http://180.yr/", d[0]);
        assertNull(d[1]);
        d = HomePage.decode(HomePage.encode("about:home", "https://wood.yr/shop"));
        assertEquals("https://wood.yr/shop", d[1]);
        assertNull(HomePage.decode("no newline".getBytes()));
        assertNull(HomePage.decode("http://180.yr/\njavascript:x".getBytes()));
        assertTrue(HomePage.isValid("about:home"));
        assertTrue(HomePage.isValid("http://180.yr/"));
        assertFalse(HomePage.isValid("http://180.yr"), "要规范化过的写法");
        assertFalse(HomePage.isValid("file:///etc/passwd"));
        assertFalse(HomePage.isValid(null));
    }
}
