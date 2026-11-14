package com.mrcrayfish.furniture.web;

import com.mrcrayfish.furniture.client.browser.PageDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTest
{
    @Test
    void escapesAndDropsCharactersXmlCannotHold() throws Exception
    {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", Html.escape("<a href=\"x\">&'"));
        assertEquals("ab", Html.escape("a\u0000\u0007b"));
        assertEquals("ab", Html.escape("a\uFFFE\uFFFFb"));
        assertEquals("ab", Html.escape("a\uD800b"), "落单的高代理项");
        assertEquals("ab", Html.escape("a\uDC00b"), "落单的低代理项");
        assertEquals("a\uD83D\uDE00b", Html.escape("a\uD83D\uDE00b"), "成对的保留");
        assertEquals("", Html.escape(null));
        // 转义后的任意文字放进页面都能解析
        String all = "\uD800\uFFFE<>&\"'\u0001\uDBFF\uDFFF\uFFFF";
        byte[] page = ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p title=\"" + Html.escape(all) + "\">" + Html.escape(all) + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        PageDocument.parse(page, "http://a.yr/", id -> null, 600);
    }
}
