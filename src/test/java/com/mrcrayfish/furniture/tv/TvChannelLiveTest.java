package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 直播间的频道写法（服主 11-18） */
class TvChannelLiveTest
{
    @Test
    void liveRoomAddress()
    {
        TvChannel c = TvChannel.parse("https://live.bilibili.com/21452505");
        assertEquals(TvChannel.Kind.LIVE, c.getKind());
        assertEquals(21452505L, c.getRoom());
        assertTrue(c.isLive());
        assertTrue(c.isStream());
        assertFalse(c.isVideo());
        assertEquals("live#21452505", c.key());
        // 查询串不管、结尾斜杠、h5 / blanc 路径、http、短号
        assertEquals(21452505L, TvChannel.parse("https://live.bilibili.com/21452505?spm_id_from=333.1007").getRoom());
        assertEquals(21452505L, TvChannel.parse("https://live.bilibili.com/21452505/").getRoom());
        assertEquals(5440L, TvChannel.parse("https://live.bilibili.com/h5/5440").getRoom());
        assertEquals(5440L, TvChannel.parse("https://live.bilibili.com/blanc/5440").getRoom());
        assertEquals(1L, TvChannel.parse("http://live.bilibili.com/1").getRoom());
    }

    @Test
    void shortForm()
    {
        TvChannel c = TvChannel.parse("live21452505");
        assertEquals(TvChannel.Kind.LIVE, c.getKind());
        assertEquals(21452505L, c.getRoom());
        assertEquals("live21452505", c.getRaw());
        assertEquals(TvChannel.Kind.LIVE, TvChannel.parse("LIVE1").getKind());
        // 同一个房间两种写法是同一个键
        assertEquals(TvChannel.parse("live1").key(), TvChannel.parse("https://live.bilibili.com/1").key());
    }

    @Test
    void badLiveWritings()
    {
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("live0").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("live").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("live-1").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("live 123").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("live1234567890123").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("live12?p=2").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("https://live.bilibili.com/").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("https://live.bilibili.com/p/eden/area-tags").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("https://live.bilibili.com/0").getKind());
        // 播放页地址里的 live 不是直播间
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("https://www.bilibili.com/video/live123").getKind());
        // 别的网站同样的路径是 GIF 网址（客户端照白名单判断）
        assertEquals(TvChannel.Kind.GIF, TvChannel.parse("https://example.com/21452505").getKind());
    }

    @Test
    void serverAcceptsLiveRooms()
    {
        assertNull(TvChannel.rejectReason("https://live.bilibili.com/21452505"));
        assertNull(TvChannel.rejectReason("live5440"));
        assertEquals("cfm.tv.channel.invalid", TvChannel.rejectReason("live0"));
        assertEquals("cfm.tv.channel.invalid", TvChannel.rejectReason("https://live.bilibili.com/abc"));
    }
}
