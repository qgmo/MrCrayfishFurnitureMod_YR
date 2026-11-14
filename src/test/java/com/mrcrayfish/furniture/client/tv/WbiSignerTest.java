package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WbiSignerTest
{
    /** 公开文档里的例子 */
    @Test
    void documentedExample()
    {
        String img = WbiSigner.keyFromUrl("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png");
        String sub = WbiSigner.keyFromUrl("https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png");
        assertEquals("7cd084941338484aae1ad9425b84077c", img);
        String mixin = WbiSigner.mixinKey(img, sub);
        assertEquals("ea1db124af3c7062474693fa704f4ff8", mixin);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("foo", "114");
        params.put("bar", "514");
        params.put("zab", "1919810");
        assertEquals("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4", WbiSigner.sign(params, 1702204169, mixin));
    }

    @Test
    void encodingIsUpperCaseAndSpaceIsPercent20()
    {
        assertEquals("one%20one%20four", WbiSigner.encode("one one four"));
        assertEquals("%E4%BA%94%E4%B8%80%E5%9B%9B", WbiSigner.encode("五一四"));
    }

    @Test
    void filtersSpecialCharacters()
    {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("k", "a!b'c(d)e*f");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("k", "abcdef");
        assertEquals(WbiSigner.sign(b, 1, "x"), WbiSigner.sign(a, 1, "x"));
    }
}
