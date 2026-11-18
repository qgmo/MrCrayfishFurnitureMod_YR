package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 直播接口的解析与选流（字段照 11-18 实测的返回，地址、签名换成了假的） */
class LiveDataTest
{
    private static JsonObject json(String s)
    {
        return new JsonParser().parse(s.replace('\'', '"')).getAsJsonObject();
    }

    @Test
    void roomInit()
    {
        LiveData.Room r = assertDoesNotThrow(() -> LiveData.parseRoomInit(json("{'code':0,'msg':'ok','data':{'room_id':5440,'short_id':1,'uid':9617619,'live_status':2,'encrypted':false,'is_locked':false}}")));
        assertEquals(5440, r.roomId);
        // 轮播按没开播算
        assertFalse(r.isLive());
        assertTrue(assertDoesNotThrow(() -> LiveData.parseRoomInit(json("{'code':0,'data':{'room_id':26808302,'short_id':0,'live_status':1}}"))).isLive());
        assertFalse(assertDoesNotThrow(() -> LiveData.parseRoomInit(json("{'code':0,'data':{'room_id':23058,'short_id':3,'live_status':0}}"))).isLive());
    }

    @Test
    void roomInitErrors()
    {
        assertEquals("cfm.tv.live.not_found", assertThrows(BiliData.BiliException.class, () -> LiveData.parseRoomInit(json("{'code':60004,'msg':'房间不存在','data':[]}"))).getKey());
        assertEquals("cfm.tv.live.locked", assertThrows(BiliData.BiliException.class, () -> LiveData.parseRoomInit(json("{'code':0,'data':{'room_id':9,'live_status':1,'encrypted':true,'pwd_verified':false}}"))).getKey());
        assertEquals("cfm.tv.live.locked", assertThrows(BiliData.BiliException.class, () -> LiveData.parseRoomInit(json("{'code':0,'data':{'room_id':9,'live_status':1,'is_locked':true}}"))).getKey());
        assertEquals("cfm.tv.bili.blocked", assertThrows(BiliData.BiliException.class, () -> LiveData.parseRoomInit(json("{'code':-352}"))).getKey());
    }

    private static String codec(String name, int qn, String accept, String host)
    {
        return "{'codec_name':'" + name + "','current_qn':" + qn + ",'accept_qn':" + accept + ",'base_url':'/live-bvc/1/live_x_" + name + ".flv?','url_info':[{'host':'https://" + host + "','extra':'expires=1&sign=x'},{'host':'https://" + host.replaceFirst("\\.", "b.") + "','extra':'expires=1&sign=y'}]}";
    }

    private static final String PLAY = "{'code':0,'message':'0','data':{'room_id':26808302,'short_id':0,'live_status':1,'playurl_info':{'playurl':{'stream':["
            + "{'protocol_name':'http_stream','format':[{'format_name':'flv','codec':[" + codec("avc", 250, "[10000,400,250]", "d1--ov-gotcha07.bilivideo.com") + "," + codec("hevc", 250, "[400,250]", "d1--ov-gotcha07.bilivideo.com") + "]}]},"
            + "{'protocol_name':'http_hls','format':[{'format_name':'ts','codec':[" + codec("avc", 250, "[10000,400,250]", "d1--ov-gotcha105.bilivideo.com") + "]},"
            + "{'format_name':'fmp4','codec':[" + codec("avc", 250, "[10000,400,250]", "d1--ov-gotcha207.bilivideo.com") + "]}]}]}}}}";

    @Test
    void playInfoListsEveryCodec()
    {
        LiveData.PlayInfo info = assertDoesNotThrow(() -> LiveData.parsePlayInfo(json(PLAY)));
        assertTrue(info.isLive());
        assertEquals(26808302, info.roomId);
        assertEquals(4, info.streams.size());
        LiveData.Stream flv = info.streams.get(0);
        assertTrue(flv.isFlv());
        assertEquals("avc", flv.codec);
        assertEquals(250, flv.qn);
        assertEquals(Arrays.asList(10000, 400, 250), flv.acceptQn);
        assertEquals(2, flv.urls.size());
        assertEquals("https://d1--ov-gotcha07.bilivideo.com/live-bvc/1/live_x_avc.flv?expires=1&sign=x", flv.urls.get(0).toString());
        assertEquals("d1--ov-gotcha07b.bilivideo.com", flv.urls.get(1).getHost());
        assertTrue(HostPolicy.allowed(flv.urls.get(0), HostPolicy.Purpose.CDN, false));
    }

    @Test
    void selectPrefersFlvThenTsAndAvc()
    {
        LiveData.PlayInfo info = assertDoesNotThrow(() -> LiveData.parsePlayInfo(json(PLAY)));
        LiveData.Stream s = LiveData.select(info.streams);
        assertNotNull(s);
        assertTrue(s.isFlv());
        assertEquals("avc", s.codec);
        // 没有 FLV：用 HLS 的 TS
        LiveData.Stream ts = LiveData.select(Arrays.asList(info.streams.get(1), info.streams.get(2), info.streams.get(3)));
        assertNotNull(ts);
        assertTrue(ts.isHlsTs());
        // 只剩 FLV 的 H.265：也行
        assertEquals("hevc", LiveData.select(Collections.singletonList(info.streams.get(1))).codec);
        // 只有 fMP4：不用
        assertNull(LiveData.select(Collections.singletonList(info.streams.get(3))));
    }

    @Test
    void offlineHasNoStreams()
    {
        LiveData.PlayInfo info = assertDoesNotThrow(() -> LiveData.parsePlayInfo(json("{'code':0,'data':{'room_id':21452505,'live_status':0,'playurl_info':null}}")));
        assertFalse(info.isLive());
        assertTrue(info.streams.isEmpty());
    }

    @Test
    void qualityMapping()
    {
        assertEquals(LiveData.LQN_SMOOTH, LiveData.wantedQn(BiliData.QN_360P));
        assertEquals(LiveData.LQN_SMOOTH, LiveData.wantedQn(BiliData.QN_480P));
        assertEquals(LiveData.LQN_HIGH, LiveData.wantedQn(BiliData.QN_720P));
        assertEquals(LiveData.LQN_BLURAY, LiveData.wantedQn(BiliData.QN_1080P));
        // 不超过想要的最高一档；都超过时最低一档；空表照想要的
        assertEquals(150, LiveData.pickQn(Arrays.asList(10000, 400, 250, 150, 80), 150));
        assertEquals(250, LiveData.pickQn(Arrays.asList(10000, 400, 250), 80));
        assertEquals(400, LiveData.pickQn(Arrays.asList(10000, 400, 250), 400));
        assertEquals(80, LiveData.pickQn(Collections.emptyList(), 80));
    }

    @Test
    void danmuInfo()
    {
        LiveData.DanmuInfo d = assertDoesNotThrow(() -> LiveData.parseDanmuInfo(json("{'code':0,'data':{'token':'abc','host_list':["
                + "{'host':'hw-sg-live-comet-02.chat.bilibili.com','port':2243,'wss_port':443,'ws_port':2244},"
                + "{'host':'odd.chat.bilibili.com','port':2243,'wss_port':8443,'ws_port':2244},"
                + "{'host':'broadcastlv-mixed.chat.bilibili.com','port':2243,'wss_port':443,'ws_port':2244}]}}")));
        assertEquals("abc", d.token);
        assertEquals(Arrays.asList("hw-sg-live-comet-02.chat.bilibili.com", "broadcastlv-mixed.chat.bilibili.com"), d.hosts);
        // 不签名时接口回 -352
        assertEquals("cfm.tv.bili.blocked", assertThrows(BiliData.BiliException.class, () -> LiveData.parseDanmuInfo(json("{'code':-352,'message':'-352'}"))).getKey());
        assertThrows(BiliData.BiliException.class, () -> LiveData.parseDanmuInfo(json("{'code':0,'data':{'token':'','host_list':[]}}")));
    }

    @Test
    void hostPolicyForLive()
    {
        assertTrue(HostPolicy.allowed(java.net.URI.create("https://api.live.bilibili.com/room/v1/Room/room_init?id=1"), HostPolicy.Purpose.API, false));
        assertFalse(HostPolicy.allowed(java.net.URI.create("https://live.bilibili.com/1"), HostPolicy.Purpose.API, false));
        assertTrue(HostPolicy.allowedLiveChat(java.net.URI.create("wss://broadcastlv-mixed.chat.bilibili.com:443/sub")));
        assertTrue(HostPolicy.allowedLiveChat(java.net.URI.create("wss://hw-sg-live-comet-01.chat.bilibili.com/sub")));
        assertFalse(HostPolicy.allowedLiveChat(java.net.URI.create("wss://broadcastlv.chat.bilibili.com:2245/sub")));
        assertFalse(HostPolicy.allowedLiveChat(java.net.URI.create("ws://broadcastlv.chat.bilibili.com/sub")));
        assertFalse(HostPolicy.allowedLiveChat(java.net.URI.create("wss://chat.bilibili.com.evil.example/sub")));
        assertFalse(HostPolicy.allowedLiveChat(java.net.URI.create("wss://evilchat.bilibili.com/sub")));
        // 搜索结果的封面
        assertTrue(HostPolicy.allowed(java.net.URI.create("https://archive.biliimg.com/bfs/archive/a.jpg"), HostPolicy.Purpose.COVER, false));
        assertEquals("https://i0.hdslb.com/bfs/a.jpg", HostPolicy.upgrade("//i0.hdslb.com/bfs/a.jpg").toString());
    }
}
