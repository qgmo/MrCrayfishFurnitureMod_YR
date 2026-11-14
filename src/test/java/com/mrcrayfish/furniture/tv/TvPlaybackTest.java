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
}
