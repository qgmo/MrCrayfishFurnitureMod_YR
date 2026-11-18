package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 播完模式（服主 11-18） */
class TvEndModeTest
{
    @Test
    void buttonCyclesThroughThreeModes()
    {
        assertEquals(TvEndMode.REPEAT, TvEndMode.STOP.cycle());
        assertEquals(TvEndMode.NEXT, TvEndMode.REPEAT.cycle());
        assertEquals(TvEndMode.STOP, TvEndMode.NEXT.cycle());
    }

    @Test
    void savedIdsAndDefault()
    {
        // 旧存档没有这一项：读出 0 = 停住（默认，原来的行为）
        assertEquals(TvEndMode.STOP, TvEndMode.fromNbt(0));
        assertEquals(TvEndMode.NEXT, TvEndMode.fromNbt(2));
        assertEquals(TvEndMode.STOP, TvEndMode.fromNbt(7));
        assertEquals(TvEndMode.STOP, TvEndMode.fromNbt(-1));
        // 网络包：不认得的编号拒收
        assertNull(TvEndMode.byId(3));
        assertNull(TvEndMode.byId(-1));
        assertEquals(TvEndMode.REPEAT, TvEndMode.byId(1));
    }

    @Test
    void stopAndRepeat()
    {
        assertEquals(TvEndMode.Outcome.STOP, TvEndMode.STOP.outcome(0, 3));
        assertEquals(TvEndMode.Outcome.RESTART, TvEndMode.REPEAT.outcome(1, 3));
        assertEquals(TvEndMode.Outcome.RESTART, TvEndMode.REPEAT.outcome(0, 1));
    }

    @Test
    void nextGoesRoundAndActsAsRepeatWithOneChannel()
    {
        assertEquals(TvEndMode.Outcome.SWITCH, TvEndMode.NEXT.outcome(0, 3));
        assertEquals(1, TvEndMode.nextChannel(0, 3));
        assertEquals(2, TvEndMode.nextChannel(1, 3));
        // 第三台播完回到第一台
        assertEquals(0, TvEndMode.nextChannel(2, 3));
        assertEquals(TvEndMode.Outcome.SWITCH, TvEndMode.NEXT.outcome(2, 3));
        // 两台
        assertEquals(1, TvEndMode.nextChannel(0, 2));
        assertEquals(0, TvEndMode.nextChannel(1, 2));
        // 只有一台有内容：等同重播
        assertEquals(0, TvEndMode.nextChannel(0, 1));
        assertEquals(TvEndMode.Outcome.RESTART, TvEndMode.NEXT.outcome(0, 1));
        // 没有频道（不会播到片尾，防一下）
        assertEquals(0, TvEndMode.nextChannel(0, 0));
    }
}
