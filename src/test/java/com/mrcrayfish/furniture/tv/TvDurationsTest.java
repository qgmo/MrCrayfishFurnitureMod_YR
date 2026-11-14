package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 视频时长的采信规则（服主 11-14） */
class TvDurationsTest
{
    @Test
    void oneReporterIsTrusted()
    {
        TvDurations<String> d = new TvDurations<>(7);
        assertEquals(0, d.trusted());
        assertTrue(d.report("a", 90_000));
        assertEquals(90_000, d.trusted());
        assertFalse(d.disagree());
        // 自己再报一次：换掉自己那份
        assertTrue(d.report("a", 91_000));
        assertEquals(1, d.count());
        assertEquals(91_000, d.trusted());
    }

    @Test
    void closeReportsAreTrusted()
    {
        TvDurations<String> d = new TvDurations<>(0);
        d.report("a", 90_000);
        d.report("b", 91_500);
        d.report("c", 90_200);
        // 相差 1.5 秒（2 秒以内）：采信最长的
        assertFalse(d.disagree());
        assertEquals(91_500, d.trusted());
        // 长视频按 1%：2 小时差 60 秒仍算一致
        TvDurations<String> e = new TvDurations<>(0);
        e.report("a", 7_200_000);
        e.report("b", 7_260_000);
        assertEquals(7_260_000, e.trusted());
    }

    @Test
    void differentReportsAreNotTrusted()
    {
        TvDurations<String> d = new TvDurations<>(0);
        d.report("a", 90_000);
        d.report("b", 92_500);
        assertTrue(d.disagree());
        assertEquals(0, d.trusted());
        // 多一个人报得和 a 一样也不行：还是有人差得多
        d.report("c", 90_000);
        assertEquals(0, d.trusted());
        // b 改报一致的值后恢复采信
        d.report("b", 90_000);
        assertEquals(90_000, d.trusted());
        assertFalse(TvDurations.agree(7_200_000, 7_300_000));
        assertTrue(TvDurations.agree(1_000, 3_000));
        assertFalse(TvDurations.agree(1_000, 3_001));
    }

    @Test
    void outOfRangeIsRejected()
    {
        TvDurations<String> d = new TvDurations<>(0);
        assertFalse(d.report("a", 0));
        assertFalse(d.report("a", -5));
        assertFalse(d.report("a", TvDurations.MAX_MS + 1));
        assertTrue(d.report("a", TvDurations.MAX_MS));
        assertEquals(TvDurations.MAX_MS, d.trusted());
    }

    @Test
    void changingChannelClearsReports()
    {
        TvDurations<String> d = new TvDurations<>(41);
        d.report("a", 90_000);
        d.report("b", 120_000);
        assertTrue(d.disagree());
        int before = d.epoch();
        d.clear();
        assertNotEquals(before, d.epoch());
        assertEquals(0, d.count());
        assertFalse(d.disagree());
        assertEquals(0, d.trusted());
        d.report("b", 120_000);
        assertEquals(120_000, d.trusted());
    }

    @Test
    void reporterLimit()
    {
        TvDurations<Integer> d = new TvDurations<>(0);
        for(int i = 0; i < TvDurations.MAX_REPORTERS; i++)
            assertTrue(d.report(i, 60_000));
        assertFalse(d.report(-1, 10_000));
        // 已报过的人还能改
        assertTrue(d.report(0, 60_500));
        assertEquals(60_500, d.trusted());
    }
}
