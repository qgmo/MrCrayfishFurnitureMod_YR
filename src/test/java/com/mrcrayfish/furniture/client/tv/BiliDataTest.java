package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BiliDataTest
{
    private static JsonObject json(String s)
    {
        return new JsonParser().parse(s.replace('\'', '"')).getAsJsonObject();
    }

    private static final String VIEW = "{'code':0,'message':'0','data':{'bvid':'BV1xx411c7mD','aid':170001,'title':'示例','pic':'http://i0.hdslb.com/bfs/archive/a.jpg','duration':300,"
            + "'pages':[{'cid':111,'page':1,'part':'第一P','duration':120},{'cid':222,'page':2,'part':'第二P','duration':180}]}}";

    @Test
    void viewPicksPage()
    {
        BiliData.VideoInfo p2 = assertDoesNotThrow(() -> BiliData.parseView(json(VIEW), 2));
        assertEquals(222, p2.cid);
        assertEquals(180_000, p2.durationMs);
        assertEquals("示例 - 第二P", p2.title);
        assertEquals("http://i0.hdslb.com/bfs/archive/a.jpg", p2.cover);
        BiliData.BiliException e = assertThrows(BiliData.BiliException.class, () -> BiliData.parseView(json(VIEW), 3));
        assertEquals("cfm.tv.bili.no_page", e.getKey());
    }

    @Test
    void viewErrors()
    {
        assertEquals("cfm.tv.bili.not_found", assertThrows(BiliData.BiliException.class, () -> BiliData.parseView(json("{'code':-404,'message':'啥都木有'}"), 1)).getKey());
        assertEquals("cfm.tv.bili.blocked", assertThrows(BiliData.BiliException.class, () -> BiliData.parseView(json("{'code':-412}"), 1)).getKey());
        assertEquals("cfm.tv.channel.unsupported", assertThrows(BiliData.BiliException.class,
                () -> BiliData.parseView(json("{'code':0,'data':{'redirect_url':'https://www.bilibili.com/bangumi/play/ep1','pages':[{'cid':1}]}}"), 1)).getKey());
        assertEquals("cfm.tv.bili.charging", assertThrows(BiliData.BiliException.class,
                () -> BiliData.parseView(json("{'code':0,'data':{'is_upower_exclusive':true,'pages':[{'cid':1}]}}"), 1)).getKey());
    }

    @Test
    void playurlMp4UpgradesToHttps()
    {
        BiliData.Stream s = assertDoesNotThrow(() -> BiliData.parsePlayurl(json("{'code':0,'data':{'quality':32,'format':'mp4','timelength':120500,"
                + "'durl':[{'order':1,'url':'http://upos-sz-mirrorcos.bilivideo.com/a.mp4?e=1&deadline=2','backup_url':['https://upos-sz-mirrorhw.bilivideo.com/a.mp4?x=1']}]}}"), 32));
        assertNotNull(s);
        assertFalse(s.dash);
        assertEquals(2, s.video.size());
        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/a.mp4?e=1&deadline=2", s.video.get(0).toString());
        assertEquals(120_500, s.durationMs);
        assertTrue(s.audio.isEmpty());
    }

    @Test
    void playurlDashPicksAvcAtOrBelowLimitAndCheapestAudio()
    {
        String dash = "{'code':0,'data':{'timelength':60000,'dash':{'duration':60,'video':["
                + "{'id':64,'codecid':7,'baseUrl':'https://a.bilivideo.com/64.m4s'},"
                + "{'id':32,'codecid':12,'baseUrl':'https://a.bilivideo.com/32h.m4s'},"
                + "{'id':32,'codecid':7,'baseUrl':'https://a.bilivideo.com/32a.m4s','backupUrl':['https://b.bilivideo.com/32a.m4s']},"
                + "{'id':16,'codecid':7,'base_url':'https://a.bilivideo.com/16.m4s'}],"
                + "'audio':[{'id':30280,'bandwidth':320000,'baseUrl':'https://a.bilivideo.com/a280.m4s'},{'id':30216,'bandwidth':67000,'baseUrl':'https://a.bilivideo.com/a216.m4s'}]}}}";
        BiliData.Stream s = assertDoesNotThrow(() -> BiliData.parsePlayurl(json(dash), 32));
        assertNotNull(s);
        assertTrue(s.dash);
        assertEquals(32, s.quality);
        assertEquals("https://a.bilivideo.com/32a.m4s", s.video.get(0).toString());
        assertEquals(2, s.video.size());
        assertEquals("https://a.bilivideo.com/a216.m4s", s.audio.get(0).toString());
        // 上限 360P
        BiliData.Stream low = assertDoesNotThrow(() -> BiliData.parsePlayurl(json(dash), 16));
        assertEquals(16, low.quality);
    }

    @Test
    void playurlDashAllAboveLimitTakesLowest()
    {
        String dash = "{'code':0,'data':{'dash':{'video':[{'id':80,'codecid':7,'baseUrl':'https://a.bilivideo.com/80.m4s'},{'id':64,'codecid':7,'baseUrl':'https://a.bilivideo.com/64.m4s'}],'audio':null}}}";
        BiliData.Stream s = assertDoesNotThrow(() -> BiliData.parsePlayurl(json(dash), 32));
        assertEquals(64, s.quality);
        assertTrue(s.audio.isEmpty());
    }

    @Test
    void playurlWithoutStreamsIsNull() throws Exception
    {
        assertNull(BiliData.parsePlayurl(json("{'code':0,'data':{'v_voucher':'abc'}}"), 32));
    }

    @Test
    void navAndSpi()
    {
        String[] keys = BiliData.parseNavKeys(json("{'code':-101,'message':'账号未登录','data':{'isLogin':false,'wbi_img':{'img_url':'https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png','sub_url':'https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png'}}}"));
        assertArrayEquals(new String[]{"7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45"}, keys);
        assertNull(BiliData.parseNavKeys(json("{'code':0,'data':{'wbi_img':{'img_url':'https://x/../evil','sub_url':'x'}}}")));
        String[] b = BiliData.parseSpi(json("{'code':0,'data':{'b_3':'ABCDEF01-2345-6789-ABCD-EF0123456789infoc','b_4':'XYZ%3D%3D'}}"));
        assertEquals("ABCDEF01-2345-6789-ABCD-EF0123456789infoc", b[0]);
    }
}
