package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TvChannelTest
{
    @Test
    void bvWithAndWithoutPage()
    {
        TvChannel c = TvChannel.parse("BV1xx411c7mD");
        assertEquals(TvChannel.Kind.BILIBILI, c.getKind());
        assertEquals("BV1xx411c7mD", c.getBvid());
        assertEquals(1, c.getPage());
        TvChannel p = TvChannel.parse("  BV1xx411c7mD?p=3 ");
        assertEquals(3, p.getPage());
        assertEquals("BV1xx411c7mD#3", p.key());
        // 前缀不分大小写，后面 10 位区分
        assertEquals("BV1xx411c7mD", TvChannel.parse("bv1xx411c7mD").getBvid());
    }

    @Test
    void av()
    {
        TvChannel c = TvChannel.parse("av170001?p=2");
        assertEquals(TvChannel.Kind.BILIBILI, c.getKind());
        assertNull(c.getBvid());
        assertEquals(170001, c.getAid());
        assertEquals(2, c.getPage());
        assertEquals("av170001#2", c.key());
        assertEquals(TvChannel.Kind.BILIBILI, TvChannel.parse("AV1").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("av0").getKind());
        assertEquals(TvChannel.Kind.INVALID, TvChannel.parse("av" + (1L << 51)).getKind());
    }

    @Test
    void videoPageUrl()
    {
        TvChannel c = TvChannel.parse("https://www.bilibili.com/video/BV1xx411c7mD/?p=4&spm_id_from=333.1007");
        assertEquals(TvChannel.Kind.BILIBILI, c.getKind());
        assertEquals("BV1xx411c7mD", c.getBvid());
        assertEquals(4, c.getPage());
        assertEquals(1, TvChannel.parse("https://m.bilibili.com/video/av170001").getPage());
    }

    @Test
    void bangumiUnsupported()
    {
        assertEquals(TvChannel.Kind.UNSUPPORTED, TvChannel.parse("ep123456").getKind());
        assertEquals(TvChannel.Kind.UNSUPPORTED, TvChannel.parse("ss2345").getKind());
        assertEquals(TvChannel.Kind.UNSUPPORTED, TvChannel.parse("https://www.bilibili.com/bangumi/play/ep123").getKind());
        assertEquals("cfm.tv.channel.unsupported", TvChannel.rejectReason("ep1"));
    }

    @Test
    void gifAndEmpty()
    {
        assertEquals(TvChannel.Kind.GIF, TvChannel.parse("https://i.imgur.com/abc.gif").getKind());
        assertEquals(TvChannel.Kind.EMPTY, TvChannel.parse("   ").getKind());
        assertEquals(TvChannel.Kind.EMPTY, TvChannel.parse(null).getKind());
        assertNull(TvChannel.rejectReason(""));
        assertNull(TvChannel.rejectReason("https://i.imgur.com/abc.gif"));
        assertFalse(TvChannel.parse("https://i.imgur.com/abc.gif").isVideo());
    }

    @Test
    void invalid()
    {
        for(String s : new String[]{"BV1xx411c7m", "BV1xx411c7mD0", "BV0xx411c7mD", "BV1xx411c7mD?p=0", "BV1xx411c7mD?p=1001", "BV1xx411c7mD?t=3",
                "BV1xx411c7mD p=2", "hello", "BV1Ox411c7mD", "§cBV1xx411c7mD", "https://www.bilibili.com/read/cv1", "ftp://x/y.gif", "http://[bad"})
        {
            assertEquals(TvChannel.Kind.INVALID, TvChannel.parse(s).getKind(), s);
            assertEquals("cfm.tv.channel.invalid", TvChannel.rejectReason(s), s);
        }
        StringBuilder longUrl = new StringBuilder("https://i.imgur.com/");
        while(longUrl.length() < 300)
            longUrl.append('a');
        assertEquals("cfm.tv.channel.too_long", TvChannel.rejectReason(longUrl + ".gif"));
    }
}
