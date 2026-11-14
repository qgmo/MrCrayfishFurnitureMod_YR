package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.Ipv6Address;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 服务端从页面里解析旁观者要放行的主机：只认页面里同主机的图，主机名由服务端自己查 */
class PageFactsTest
{
    static final Ipv6Address MINEBAY = Ipv6Address.parse("fd00:1:2:1::5");
    static final Ipv6Address TEST = Ipv6Address.parse("fd00:1:2:1::2");

    static PageFacts facts(String url, String html) throws Exception
    {
        Map<String, Ipv6Address> names = new HashMap<>();
        names.put("minebay.yr", MINEBAY);
        names.put("test.yr", TEST);
        return PageFacts.of(new PageSnapshot(url, html.getBytes(StandardCharsets.UTF_8), PageParser.Mode.HTML, null), names::get);
    }

    @Test
    void hostsComeFromImagesOnThePage() throws Exception
    {
        // 同主机的图（相对、绝对、https）；别的主机的图、file: 不算
        PageFacts f = facts("http://minebay.yr/shop", "<p><img src=a.png><img src=https://minebay.yr/b.png><img src=http://test.yr/c.png><img src=file:///x>");
        assertEquals(Collections.singleton(MINEBAY), f.imageHosts);
        // 没有图：不放行任何主机（页面所在的网站也不放）
        assertTrue(facts("http://minebay.yr/", "<p>没有图</p>").imageHosts.isEmpty());
        // 查不到的名字不放行
        assertTrue(facts("http://nowhere.yr/", "<img src=/a.png>").imageHosts.isEmpty());
        // IPv6 字面量
        assertEquals(new HashSet<>(Arrays.asList(Ipv6Address.parse("fd00::99"))), facts("http://[fd00::99]/", "<img src=/a.png>").imageHosts);
        // 内置页
        assertTrue(facts("about:home", "<img src=/a.png>").imageHosts.isEmpty());
    }

    @Test
    void hostOf()
    {
        assertEquals("minebay.yr", UrlInput.hostOf("https://MineBay.yr:8443/a?b"));
        assertEquals("[fd00::1]", UrlInput.hostOf("http://[fd00::1]:80/"));
        assertNull(UrlInput.hostOf("about:home"));
        assertTrue(UrlInput.sameHost("http://a.yr/x", "https://a.yr:444/y"));
        assertFalse(UrlInput.sameHost("http://a.yr/x", "http://b.yr/x"));
        assertFalse(UrlInput.sameHost("about:home", "about:home"));
    }
}
