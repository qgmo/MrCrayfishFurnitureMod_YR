package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 频道设置界面的搜索：接口解析（字段照 11-18 实测的返回）、数字缩写、结果排列 */
class BiliSearchTest
{
    private static JsonObject json(String s)
    {
        return new JsonParser().parse(s.replace('\'', '"')).getAsJsonObject();
    }

    private static final String VIDEO = "{'code':0,'message':'0','data':{'page':1,'pagesize':20,'numResults':1000,'numPages':50,'result':["
            + "{'type':'video','bvid':'BV1Nxxr6oES2','title':'耗资上万！我重制了网页版MC','author':'土豆味小哲','play':1814113,'pic':'//i2.hdslb.com/bfs/archive/a.jpg'},"
            + "{'type':'video','bvid':'BV1A4411c7mA','title':'<em class=\\'keyword\\'>我的世界</em>：盘点 &amp; 回顾','author':'cok小呆','play':1176,'pic':'https://archive.biliimg.com/bfs/archive/b.jpg'},"
            + "{'type':'video','bvid':'not-a-bv','title':'坏的','author':'x','play':1,'pic':''},"
            + "{'type':'video','bvid':'BV1xx411c7mD','title':'封面不在白名单','author':'y','play':-5,'pic':'https://evil.example/c.jpg'}]}}";

    @Test
    void videoResults()
    {
        BiliSearch.Page p = assertDoesNotThrow(() -> BiliSearch.parse(json(VIDEO), BiliSearch.Type.VIDEO, 1));
        assertEquals(50, p.numPages);
        assertEquals(3, p.items.size());
        BiliSearch.Item a = p.items.get(0);
        assertEquals("BV1Nxxr6oES2", a.channel);
        assertEquals("耗资上万！我重制了网页版MC", a.title);
        assertEquals("土豆味小哲", a.author);
        assertEquals(1814113, a.count);
        assertEquals("https://i2.hdslb.com/bfs/archive/a.jpg", a.cover);
        // 高亮标签去掉、实体转回来；biliimg 的封面也收
        BiliSearch.Item b = p.items.get(1);
        assertEquals("我的世界：盘点 & 回顾", b.title);
        assertEquals("https://archive.biliimg.com/bfs/archive/b.jpg", b.cover);
        // 白名单外的封面不要；负数播放量按 0
        BiliSearch.Item c = p.items.get(2);
        assertNull(c.cover);
        assertEquals(0, c.count);
    }

    private static final String LIVE = "{'code':0,'data':{'numPages':50,'result':["
            + "{'type':'live_room','roomid':24678311,'short_id':0,'title':'网吧出差','uname':'楚钧艾克','online':485268,'user_cover':'//i0.hdslb.com/bfs/live/u.jpg','cover':'//i0.hdslb.com/bfs/live-key-frame/k.jpg','live_status':1},"
            + "{'type':'live_room','roomid':27358706,'title':'新手玩<em class=\\'keyword\\'>LOL</em>的第三天','uname':'杏仁鹿77','online':12554,'user_cover':'','cover':'//i0.hdslb.com/bfs/live-key-frame/k2.jpg'},"
            + "{'type':'live_room','roomid':0,'title':'坏的'}]}}";

    @Test
    void liveResults()
    {
        BiliSearch.Page p = assertDoesNotThrow(() -> BiliSearch.parse(json(LIVE), BiliSearch.Type.LIVE, 2));
        assertEquals(2, p.items.size());
        assertEquals(2, p.page);
        BiliSearch.Item a = p.items.get(0);
        assertEquals("live24678311", a.channel);
        assertEquals(com.mrcrayfish.furniture.tv.TvChannel.Kind.LIVE, com.mrcrayfish.furniture.tv.TvChannel.parse(a.channel).getKind());
        assertEquals("楚钧艾克", a.author);
        assertEquals(485268, a.count);
        assertEquals("https://i0.hdslb.com/bfs/live/u.jpg", a.cover);
        // 没有主播设的封面：用关键帧
        assertEquals("新手玩LOL的第三天", p.items.get(1).title);
        assertEquals("https://i0.hdslb.com/bfs/live-key-frame/k2.jpg", p.items.get(1).cover);
        // search_type=live 的写法：result 里分 live_room
        BiliSearch.Page q = assertDoesNotThrow(() -> BiliSearch.parse(json("{'code':0,'data':{'numPages':1,'result':{'live_room':[{'roomid':5,'title':'t','uname':'u','online':1}],'live_user':[]}}}"), BiliSearch.Type.LIVE, 1));
        assertEquals("live5", q.items.get(0).channel);
    }

    @Test
    void challengeAndErrors()
    {
        JsonObject challenge = json("{'code':0,'message':'0','data':{'v_voucher':'voucher_x'}}");
        assertTrue(BiliSearch.isChallenge(challenge));
        assertEquals("cfm.tv.search.blocked", assertThrows(BiliData.BiliException.class, () -> BiliSearch.parse(challenge, BiliSearch.Type.VIDEO, 1)).getKey());
        assertFalse(BiliSearch.isChallenge(json(VIDEO)));
        assertEquals("cfm.tv.bili.blocked", assertThrows(BiliData.BiliException.class, () -> BiliSearch.parse(json("{'code':-412}"), BiliSearch.Type.VIDEO, 1)).getKey());
        // 没有结果
        assertTrue(assertDoesNotThrow(() -> BiliSearch.parse(json("{'code':0,'data':{'numPages':0,'result':[]}}"), BiliSearch.Type.VIDEO, 1)).items.isEmpty());
    }

    @Test
    void titlesAreCleaned()
    {
        assertEquals("a b", BiliSearch.text("  a \n\t b ", 80));
        // 同弹幕：去掉格式符 § 本身与控制字符（后面的字母留着，没有 § 就不会变色）
        assertEquals("c红字", BiliSearch.text("§c红\u0007字", 80));
        assertEquals("abc", BiliSearch.text("abcdef", 3));
        assertEquals("<", BiliSearch.text("&lt;", 80));
    }

    @Test
    void numbersInChineseAndEnglish()
    {
        assertEquals("9999", BiliSearch.compact(9999, "zh_cn"));
        assertEquals("1万", BiliSearch.compact(10_000, "zh_cn"));
        assertEquals("1.2万", BiliSearch.compact(12_345, "zh_cn"));
        assertEquals("181.4万", BiliSearch.compact(1_814_113, "zh_cn"));
        // 向下取，不进位成“1万”以上的写法
        assertEquals("9999.9万", BiliSearch.compact(99_999_999, "zh_cn"));
        assertEquals("1.2亿", BiliSearch.compact(123_456_789, "zh_cn"));
        assertEquals("1.2萬", BiliSearch.compact(12_345, "zh_tw"));
        assertEquals("1.2億", BiliSearch.compact(123_456_789, "zh_tw"));
        assertEquals("999", BiliSearch.compact(999, "en_us"));
        assertEquals("1.1K", BiliSearch.compact(1_176, "en_us"));
        assertEquals("1.8M", BiliSearch.compact(1_814_113, "en_us"));
        assertEquals("2B", BiliSearch.compact(2_000_000_000, "en_us"));
        assertEquals("0", BiliSearch.compact(-3, "en_us"));
        assertEquals("12K", BiliSearch.compact(12_000, null));
    }

    @Test
    void gridColumnsFollowTheWindow()
    {
        // GUI 坐标：1920×1080 在 GUI 缩放 1 / 2 / 3 / 4 下分别是 1920×1080、960×540、640×360、480×270；854×480 窗口缩放 2 是 427×240
        SearchGrid huge = SearchGrid.compute(1920, 1080, 10);
        SearchGrid big = SearchGrid.compute(960, 540, 10);
        SearchGrid mid = SearchGrid.compute(640, 360, 10);
        SearchGrid small = SearchGrid.compute(480, 270, 10);
        SearchGrid tiny = SearchGrid.compute(427, 240, 10);
        assertEquals(8, huge.columns);
        assertEquals(4, big.columns);
        assertEquals(4, mid.columns);
        assertEquals(4, small.columns);
        assertEquals(3, tiny.columns);
        // 窗口大时封面也大
        assertTrue(huge.cardWidth > mid.cardWidth);
        assertTrue(big.cardWidth > small.cardWidth);
        int[] widths = {1920, 960, 640, 480, 427};
        SearchGrid[] grids = {huge, big, mid, small, tiny};
        for(int i = 0; i < grids.length; i++)
        {
            SearchGrid g = grids[i];
            assertTrue(g.cardWidth >= SearchGrid.MIN_CARD, "card " + g.cardWidth);
            // 放得下，右边不出界
            assertTrue(g.left + g.columns * g.cardWidth + (g.columns - 1) * SearchGrid.GAP <= widths[i] - SearchGrid.MARGIN);
            assertEquals(g.cardWidth * 9 / 16, g.coverHeight);
        }
        assertEquals(1, SearchGrid.compute(100, 200, 10).columns);
    }

    @Test
    void gridHitScrollAndMore()
    {
        SearchGrid g = SearchGrid.compute(480, 270, 10);
        int[] p0 = g.cardPos(0), p5 = g.cardPos(5);
        assertEquals(SearchGrid.MARGIN, p0[0]);
        assertEquals(SearchGrid.HEADER, p0[1]);
        assertEquals(p0[0] + g.cardWidth + SearchGrid.GAP, p5[0]);
        assertEquals(p0[1] + g.cardHeight + SearchGrid.GAP, p5[1]);
        assertEquals(0, g.hit(p0[0] + 1, p0[1] + 1, 0, 20));
        assertEquals(5, g.hit(p5[0] + 3, p5[1] + 3, 0, 20));
        // 卡片之间的空隙、标题栏、超出条数：都不算
        assertEquals(-1, g.hit(p0[0] + g.cardWidth + 1, p0[1] + 1, 0, 20));
        assertEquals(-1, g.hit(p0[0] + 1, 10, 0, 20));
        assertEquals(-1, g.hit(p5[0] + 3, p5[1] + 3, 0, 5));
        // 滚下去一排以后，同一个位置点到的是下一排
        assertEquals(4, g.hit(p0[0] + 1, p0[1] + 1, g.cardHeight + SearchGrid.GAP, 20));
        int rows = 5;
        assertEquals(rows * (g.cardHeight + SearchGrid.GAP) - SearchGrid.GAP, g.contentHeight(20));
        assertEquals(0, g.contentHeight(0));
        assertEquals(Math.max(0, g.contentHeight(20) - g.viewHeight), g.maxScroll(20));
        assertFalse(g.nearBottom(0, 20));
        assertTrue(g.nearBottom(g.maxScroll(20), 20));
    }
}
