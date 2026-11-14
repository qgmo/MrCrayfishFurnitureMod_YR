package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DanmakuTest
{
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><chatid>111</chatid>"
            + "<d p=\"12.5,1,25,16777215,1,0,abc,1\">第二条</d>"
            + "<d p=\"1.0,1,25,16711680,1,0,abc,2\">第一条 &amp; &lt;b&gt; &#x4F60;</d>"
            + "<d p=\"3,5,25,255,1,0,abc,3\">顶部</d>"
            + "<d p=\"4,4,25,255,1,0,abc,4\">底部</d>"
            + "<d p=\"5,7,25,255,1,0,abc,5\">[高级弹幕]</d>"
            + "<d p=\"6,1,25,255,1,0,abc,6\">§c带格式符\u0007</d>"
            + "</i>";

    @Test
    void parseKeepsSupportedModesSortedAndClean()
    {
        List<Danmaku.Comment> list = Danmaku.parse(XML);
        assertEquals(5, list.size());
        assertEquals(1000, list.get(0).timeMs);
        assertEquals("第一条 & <b> 你", list.get(0).text);
        assertEquals(0xFF0000, list.get(0).color);
        assertEquals(Danmaku.MODE_TOP, list.get(1).mode);
        assertEquals(Danmaku.MODE_BOTTOM, list.get(2).mode);
        assertEquals("c带格式符", list.get(3).text);
        assertEquals(12_500, list.get(4).timeMs);
    }

    @Test
    void longTextTruncatedAndCapped()
    {
        StringBuilder sb = new StringBuilder("<i>");
        for(int i = 0; i < 5000; i++)
            sb.append("<d p=\"").append(i).append(",1,25,0\">").append("长").append(i).append("</d>");
        sb.append("<d p=\"1,1,25,0\">");
        for(int i = 0; i < 100; i++)
            sb.append('字');
        sb.append("</d></i>");
        List<Danmaku.Comment> list = Danmaku.parse(sb.toString());
        assertEquals(Danmaku.MAX_COMMENTS, list.size());
        for(Danmaku.Comment c : list)
            assertTrue(c.text.length() <= Danmaku.MAX_TEXT);
    }

    @Test
    void scrollLanesDoNotOverlap()
    {
        List<Danmaku.Comment> list = new ArrayList<>();
        for(int i = 0; i < 6; i++)
            list.add(new Danmaku.Comment(1000 + i * 10, Danmaku.MODE_SCROLL, 0, "xxxxxxxxxx"));
        List<Danmaku.Placed> placed = Danmaku.layout(list, 200, 3, s -> s.length() * 6);
        // 同时出发的只能放进 3 行，其余丢掉
        assertEquals(3, placed.size());
        assertEquals(0, placed.get(0).lane);
        assertEquals(1, placed.get(1).lane);
        assertEquals(2, placed.get(2).lane);
        // 尾巴进场后同一行可以再放
        List<Danmaku.Comment> later = new ArrayList<>(list.subList(0, 1));
        later.add(new Danmaku.Comment(4000, Danmaku.MODE_SCROLL, 0, "xxxxxxxxxx"));
        List<Danmaku.Placed> p2 = Danmaku.layout(later, 200, 1, s -> s.length() * 6);
        assertEquals(2, p2.size());
    }

    @Test
    void visibleMovesRightToLeftAndExpires()
    {
        List<Danmaku.Comment> list = new ArrayList<>();
        list.add(new Danmaku.Comment(1000, Danmaku.MODE_SCROLL, 0xFFFFFF, "hi"));
        list.add(new Danmaku.Comment(1000, Danmaku.MODE_TOP, 0xFFFFFF, "top"));
        List<Danmaku.Placed> placed = Danmaku.layout(list, 100, 4, s -> 20);
        assertTrue(Danmaku.visibleAt(placed, 999, 100, 60, 10).isEmpty());
        List<Danmaku.Visible> v0 = Danmaku.visibleAt(placed, 1000, 100, 60, 10);
        assertEquals(2, v0.size());
        assertEquals(100F, v0.get(0).x, 0.01);
        List<Danmaku.Visible> v1 = Danmaku.visibleAt(placed, 1000 + Danmaku.SCROLL_MS / 2, 100, 60, 10);
        assertEquals(40F, v1.get(0).x, 0.01);
        assertEquals(1, Danmaku.visibleAt(placed, 1000 + Danmaku.FIXED_MS, 100, 60, 10).size());
        assertTrue(Danmaku.visibleAt(placed, 1000 + Danmaku.SCROLL_MS, 100, 60, 10).isEmpty());
    }
}
