package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** playurl 选流逻辑（html5 与 DASH 的取舍）和画面尺寸上限、队列内存预算；不联网 */
class BiliPlayurlSelectTest
{
    private static JsonObject json(String s)
    {
        return new JsonParser().parse(s.replace('\'', '"')).getAsJsonObject();
    }

    /** html5：只有 480P 的单文件 MP4 */
    private static final String HTML5_480 = "{'code':0,'data':{'quality':32,'format':'mp4','timelength':60000,"
            + "'durl':[{'order':1,'url':'https://a.bilivideo.com/h5-480.mp4'}]}}";
    /** html5：已经是 1080P */
    private static final String HTML5_1080 = "{'code':0,'data':{'quality':80,'format':'mp4','timelength':60000,"
            + "'durl':[{'order':1,'url':'https://a.bilivideo.com/h5-1080.mp4'}]}}";
    /** DASH：有 1080P / 720P / 480P，各有 H.264 */
    private static final String DASH = "{'code':0,'data':{'timelength':60000,'dash':{'duration':60,'video':["
            + "{'id':80,'codecid':7,'baseUrl':'https://a.bilivideo.com/80.m4s'},"
            + "{'id':64,'codecid':7,'baseUrl':'https://a.bilivideo.com/64.m4s'},"
            + "{'id':32,'codecid':7,'baseUrl':'https://a.bilivideo.com/32.m4s'}],"
            + "'audio':[{'id':30216,'bandwidth':64000,'baseUrl':'https://a.bilivideo.com/a.m4s'}]}}}";

    /** 按 html5 / DASH 返回固定 JSON，并数各自被取了几次 */
    private static final class Fake implements BiliApi.PlayurlFetcher
    {
        final String html5Json, dashJson;
        final int maxQn;
        final AtomicInteger html5Calls = new AtomicInteger(), dashCalls = new AtomicInteger();

        Fake(String html5Json, String dashJson, int maxQn)
        {
            this.html5Json = html5Json;
            this.dashJson = dashJson;
            this.maxQn = maxQn;
        }

        @Override
        public BiliData.Stream fetch(boolean html5) throws BiliData.BiliException
        {
            (html5 ? html5Calls : dashCalls).incrementAndGet();
            String j = html5 ? html5Json : dashJson;
            if(j == null)
                throw new BiliData.BiliException("cfm.tv.bili.network", "boom");
            return BiliData.parsePlayurl(json(j), maxQn);
        }
    }

    @Test
    void html5LowWithCookieTriesDashAndTakesHigher() throws Exception
    {
        Fake f = new Fake(HTML5_480, DASH, 80);
        BiliData.Stream s = BiliApi.selectStream(f, 80, true);
        assertTrue(s.dash);
        assertEquals(80, s.quality);
        assertEquals(1, f.dashCalls.get());
    }

    @Test
    void dashRespectsCapEvenWhenHigherExists() throws Exception
    {
        Fake f = new Fake(HTML5_480, DASH, 64);
        BiliData.Stream s = BiliApi.selectStream(f, 64, true);
        assertTrue(s.dash);
        assertEquals(64, s.quality);
    }

    @Test
    void html5LowWithCookieKeepsHtml5WhenDashNotBetter() throws Exception
    {
        String dash480 = "{'code':0,'data':{'dash':{'video':[{'id':32,'codecid':7,'baseUrl':'https://a.bilivideo.com/32.m4s'}]}}}";
        Fake f = new Fake(HTML5_480, dash480, 80);
        BiliData.Stream s = BiliApi.selectStream(f, 80, true);
        assertFalse(s.dash);
        assertEquals(32, s.quality);
    }

    @Test
    void html5LowWithCookieKeepsHtml5WhenDashFails() throws Exception
    {
        Fake f = new Fake(HTML5_480, null, 80);
        BiliData.Stream s = BiliApi.selectStream(f, 80, true);
        assertFalse(s.dash);
        assertEquals(1, f.dashCalls.get());
    }

    @Test
    void html5AdequateDoesNotTryDash() throws Exception
    {
        Fake f = new Fake(HTML5_1080, DASH, 80);
        BiliData.Stream s = BiliApi.selectStream(f, 80, true);
        assertFalse(s.dash);
        assertEquals(80, s.quality);
        assertEquals(0, f.dashCalls.get());
    }

    @Test
    void noCookieUnchanged() throws Exception
    {
        Fake f = new Fake(HTML5_480, DASH, 32);
        BiliData.Stream s = BiliApi.selectStream(f, 32, false);
        assertFalse(s.dash);
        assertEquals(0, f.dashCalls.get());
        // 没 cookie 时即使清晰度低也不额外试 DASH
        Fake g = new Fake(HTML5_480, DASH, 80);
        assertFalse(BiliApi.selectStream(g, 80, false).dash);
        assertEquals(0, g.dashCalls.get());
    }

    @Test
    void html5MissingFallsBackToDashWithOrWithoutCookie() throws Exception
    {
        for(boolean cookie : new boolean[]{false, true})
        {
            Fake f = new Fake("{'code':0,'data':{'v_voucher':'abc'}}", DASH, 64);
            BiliData.Stream s = BiliApi.selectStream(f, 64, cookie);
            assertTrue(s.dash);
            assertEquals(64, s.quality);
        }
    }

    @Test
    void bothFailThrowsLastError()
    {
        Fake f = new Fake(null, null, 32);
        assertEquals("cfm.tv.bili.network", assertThrows(BiliData.BiliException.class, () -> BiliApi.selectStream(f, 32, true)).getKey());
        Fake g = new Fake("{'code':0,'data':{'v_voucher':'abc'}}", "{'code':0,'data':{}}", 32);
        assertEquals("cfm.tv.bili.no_stream", assertThrows(BiliData.BiliException.class, () -> BiliApi.selectStream(g, 32, false)).getKey());
    }

    @Test
    void maxSizeFollowsQuality()
    {
        assertArrayEquals(new int[]{640, 360}, VideoDecoder.maxSize(BiliData.QN_360P));
        assertArrayEquals(new int[]{854, 480}, VideoDecoder.maxSize(BiliData.QN_480P));
        assertArrayEquals(new int[]{1280, 720}, VideoDecoder.maxSize(BiliData.QN_720P));
        assertArrayEquals(new int[]{1920, 1080}, VideoDecoder.maxSize(BiliData.QN_1080P));
        assertArrayEquals(new int[]{640, 360}, VideoDecoder.maxSize(0));
    }

    @Test
    void fitKeepsAspectEvenAndNeverUpscales()
    {
        assertNull(VideoDecoder.fit(1280, 720, 1920, 1080), "不放大");
        assertNull(VideoDecoder.fit(854, 480, 854, 480));
        assertArrayEquals(new int[]{852, 480}, VideoDecoder.fit(1920, 1080, 854, 480));
        assertArrayEquals(new int[]{1920, 1080}, VideoDecoder.fit(3840, 2160, 1920, 1080));
        // 竖屏 1080×1920 受高限制：1920→1080 即 607.5→606
        int[] v = VideoDecoder.fit(1080, 1920, 1920, 1080);
        assertEquals(1080, v[1]);
        assertEquals(0, v[0] % 2);
        assertEquals(606, v[0]);
        // 4:3 源、宽高皆取偶数
        int[] r = VideoDecoder.fit(1440, 1080, 854, 480);
        assertEquals(0, r[0] % 2);
        assertEquals(0, r[1] % 2);
        assertTrue(r[0] <= 854 && r[1] <= 480);
        assertNull(VideoDecoder.fit(0, 0, 854, 480));
    }

    @Test
    void queueMemoryStaysBounded()
    {
        assertEquals(12, VideoSession.queueFrames(BiliData.QN_360P));
        assertEquals(12, VideoSession.queueFrames(BiliData.QN_480P));
        assertEquals(12, VideoSession.queueFrames(BiliData.QN_720P));
        int n1080 = VideoSession.queueFrames(BiliData.QN_1080P);
        assertTrue(n1080 >= 4 && n1080 < 12);
        // 队列 + 解码线程手里与正在显示的 2 帧，不超过 64 MB 预算
        assertTrue((long) (n1080 + 2) * VideoDecoder.maxFrameBytes(BiliData.QN_1080P) <= 64L * 1024 * 1024);
    }
}
