package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PageLimitsTest
{
    static byte[] page(String body)
    {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    static String repeat(String s, int n)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < n; i++)
            sb.append(s);
        return sb.toString();
    }

    @Test
    void numbersFromSpec()
    {
        assertEquals(65536, PageLimits.MAX_BYTES);
        assertEquals(2000, PageLimits.MAX_ELEMENTS);
        assertEquals(64, PageLimits.MAX_DEPTH);
    }

    @Test
    void acceptsNormalPage()
    {
        assertNull(PageLimits.check(page("<p>你好 &amp; <a href=\"/x\">链接</a></p>")));
        // 带 DOCTYPE 与命名实体也不出错（检查时不处理 DTD）
        assertNull(PageLimits.check(("<?xml version=\"1.0\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"
                + "<html><body><p>a&nbsp;b</p></body></html>").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void size()
    {
        byte[] ok = page(repeat("x", PageLimits.MAX_BYTES - page("").length));
        assertEquals(PageLimits.MAX_BYTES, ok.length);
        assertNull(PageLimits.check(ok));
        byte[] big = page(repeat("x", PageLimits.MAX_BYTES - page("").length + 1));
        assertEquals(PageLimits.Problem.TOO_LARGE, PageLimits.check(big));
    }

    @Test
    void elements()
    {
        // html + body + 1998 个 = 2000
        assertNull(PageLimits.check(page(repeat("<br/>", 1998))));
        assertEquals(PageLimits.Problem.TOO_MANY_ELEMENTS, PageLimits.check(page(repeat("<br/>", 1999))));
    }

    @Test
    void depth()
    {
        // html、body 两层 + 62 层 div = 64
        assertNull(PageLimits.check(page(repeat("<div>", 62) + repeat("</div>", 62))));
        assertEquals(PageLimits.Problem.TOO_DEEP, PageLimits.check(page(repeat("<div>", 63) + repeat("</div>", 63))));
    }

    @Test
    void malformedAndEntityBombs()
    {
        // 第二轮（服主 11-14）起宽松解析：没关上、不是 XML 的也收（以前是 MALFORMED），只有空页面还算 MALFORMED
        assertNull(PageLimits.check("<html><body>".getBytes(StandardCharsets.UTF_8)));
        assertNull(PageLimits.check("not xml".getBytes(StandardCharsets.UTF_8)));
        assertEquals(PageLimits.Problem.MALFORMED, PageLimits.check(new byte[0]));
        // 内部实体不展开：十亿笑声在这里不会炸
        String lol = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">]><html><body>&lol3;</body></html>";
        PageLimits.Problem p = PageLimits.check(lol.getBytes(StandardCharsets.UTF_8));
        assertTrue(p == null || p == PageLimits.Problem.MALFORMED);
        assertNull(PageLimits.check(lol.getBytes(StandardCharsets.UTF_8), PageParser.Mode.HTML));
    }

    @Test
    void htmlModeCountsTheSameLimits()
    {
        // HTML 解析器会补上 head：html + head + body + 1997 个 = 2000
        byte[] ok = ("<html><body>" + repeat("<br>", 1997) + "</body></html>").getBytes(StandardCharsets.UTF_8);
        assertNull(PageLimits.check(ok, PageParser.Mode.HTML));
        byte[] over = ("<html><body>" + repeat("<br>", 1998) + "</body></html>").getBytes(StandardCharsets.UTF_8);
        assertEquals(PageLimits.Problem.TOO_MANY_ELEMENTS, PageLimits.check(over, PageParser.Mode.HTML));
        // 不闭合的嵌套：html、body 两层 + 62 层 = 64
        assertNull(PageLimits.check(repeat("<div>", 62).getBytes(StandardCharsets.UTF_8), PageParser.Mode.HTML));
        assertEquals(PageLimits.Problem.TOO_DEEP, PageLimits.check(repeat("<div>", 63).getBytes(StandardCharsets.UTF_8), PageParser.Mode.HTML));
        assertEquals(PageParser.Mode.XHTML, PageParser.Mode.forContentType("application/xhtml+xml; charset=utf-8"));
        assertEquals(PageParser.Mode.HTML, PageParser.Mode.forContentType("text/html"));
        assertEquals(PageParser.Mode.HTML, PageParser.Mode.forContentType(null));
    }
}
