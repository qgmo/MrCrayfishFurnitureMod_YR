package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 大屏才放开高清晰度：上限的决定与"上限变了才重开" */
class TvQualityCapTest
{
    @Test
    void capOnlyRaisedInFullscreen()
    {
        assertEquals(BiliData.QN_1080P, TvVideoManager.capFor(BiliData.QN_1080P, true));
        assertEquals(BiliData.QN_480P, TvVideoManager.capFor(BiliData.QN_1080P, false));
        assertEquals(BiliData.QN_480P, TvVideoManager.capFor(BiliData.QN_720P, false));
        assertEquals(BiliData.QN_360P, TvVideoManager.capFor(BiliData.QN_360P, true));
        assertEquals(BiliData.QN_360P, TvVideoManager.capFor(BiliData.QN_360P, false));
        assertEquals(BiliData.QN_480P, TvVideoManager.capFor(BiliData.QN_480P, true));
    }

    @Test
    void restartOnlyWhenCapChanges()
    {
        // 设置 1080P（有 cookie）：进大屏要重开，退出要重开
        assertTrue(TvVideoManager.needsRestart(BiliData.QN_480P, BiliData.QN_1080P, true));
        assertTrue(TvVideoManager.needsRestart(BiliData.QN_1080P, BiliData.QN_1080P, false));
        // 已经是对的上限：不重开
        assertFalse(TvVideoManager.needsRestart(BiliData.QN_1080P, BiliData.QN_1080P, true));
        assertFalse(TvVideoManager.needsRestart(BiliData.QN_480P, BiliData.QN_1080P, false));
        // 设置本来 ≤480P（或没 cookie 时生效上限被压到 480P）：进出大屏都不重开
        assertFalse(TvVideoManager.needsRestart(BiliData.QN_480P, BiliData.QN_480P, true));
        assertFalse(TvVideoManager.needsRestart(BiliData.QN_480P, BiliData.QN_480P, false));
        assertFalse(TvVideoManager.needsRestart(BiliData.QN_360P, BiliData.QN_360P, true));
    }

    @Test
    void sessionSizeFollowsCap()
    {
        int normal = TvVideoManager.capFor(BiliData.QN_1080P, false);
        int full = TvVideoManager.capFor(BiliData.QN_1080P, true);
        assertArrayEquals(new int[]{854, 480}, VideoDecoder.maxSize(normal));
        assertArrayEquals(new int[]{1920, 1080}, VideoDecoder.maxSize(full));
        assertTrue(VideoSession.queueFrames(normal) > VideoSession.queueFrames(full));
    }
}
