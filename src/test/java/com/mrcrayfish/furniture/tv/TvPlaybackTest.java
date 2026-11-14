package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TvPlaybackTest
{
    @Test
    void positionAdvancesOnlyWhilePlaying()
    {
        TvPlayback p = new TvPlayback();
        p.restart(1000);
        assertEquals(0, p.positionAt(1000));
        assertEquals(5000, p.positionAt(6000));
        p.togglePlay(6000);
        assertFalse(p.isPlaying());
        assertEquals(5000, p.positionAt(60000));
        p.togglePlay(60000);
        assertEquals(7000, p.positionAt(62000));
    }

    @Test
    void seekStepsAndFloor()
    {
        TvPlayback p = new TvPlayback();
        p.restart(0);
        p.seek(3000, TvPlayback.SEEK_STEP_MS);
        assertEquals(13000, p.positionAt(3000));
        p.seek(3000, -TvPlayback.SEEK_STEP_MS * 5);
        assertEquals(0, p.positionAt(3000));
        // 暂停时快进，仍是暂停
        p.togglePlay(3000);
        p.seek(4000, TvPlayback.SEEK_STEP_MS);
        assertFalse(p.isPlaying());
        assertEquals(10000, p.positionAt(99999));
    }

    @Test
    void clockGoingBackDoesNotRewind()
    {
        TvPlayback p = new TvPlayback(true, 5000, 10_000);
        assertEquals(5000, p.positionAt(9_000));
    }

    @Test
    void osdTogglesAndExpires()
    {
        TvPlayback p = new TvPlayback();
        p.toggleOsd(0);
        assertTrue(p.isOsdShown(100));
        assertEquals(TvPlayback.OSD_MS - 100, p.osdLeftMs(100));
        p.toggleOsd(200);
        assertFalse(p.isOsdShown(201));
        p.showOsd(300);
        assertFalse(p.isOsdShown(300 + TvPlayback.OSD_MS));
        assertEquals(0, p.osdLeftMs(10_000));
    }

    @Test
    void stopAtEndPausesOnTheLastFrame()
    {
        TvPlayback p = new TvPlayback();
        p.restart(0);
        assertFalse(p.stopAtEnd(89_000, 90_000));
        assertFalse(p.stopAtEnd(95_000, 0));
        assertTrue(p.stopAtEnd(90_040, 90_000));
        assertFalse(p.isPlaying());
        assertEquals(90_000, p.positionAt(500_000));
        // 已经停在片尾：不再算改动
        assertFalse(p.stopAtEnd(600_000, 90_000));
        // 快进过了片尾：夹回片尾
        p.togglePlay(600_000);
        p.seek(600_000, TvPlayback.SEEK_STEP_MS);
        assertTrue(p.stopAtEnd(600_000, 90_000));
        assertEquals(90_000, p.positionAt(700_000));
        // 后退仍是暂停
        p.rewind(700_000, TvPlayback.SEEK_STEP_MS, 90_000);
        assertFalse(p.isPlaying());
        assertEquals(80_000, p.positionAt(800_000));
    }

    @Test
    void rewindClampsToTheEndFirst()
    {
        TvPlayback p = new TvPlayback();
        p.restart(0);
        // 播完很久（服务端没采信时长，进度一直走）：按客户端给的时长先夹回片尾再退
        p.rewind(1_000_000, TvPlayback.SEEK_STEP_MS, 90_000);
        assertTrue(p.isPlaying());
        assertEquals(80_000, p.positionAt(1_000_000));
        // 不知道时长：照旧从当前位置退
        p.rewind(1_000_000, TvPlayback.SEEK_STEP_MS, 0);
        assertEquals(70_000, p.positionAt(1_000_000));
        // 还没到片尾：时长不影响
        p.rewind(1_000_000, TvPlayback.SEEK_STEP_MS, 90_000);
        assertEquals(60_000, p.positionAt(1_000_000));
        p.rewind(1_000_000, TvPlayback.SEEK_STEP_MS * 100, 90_000);
        assertEquals(0, p.positionAt(1_000_000));
    }
}
