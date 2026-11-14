package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TvClockTest
{
    @Test
    void targetUsesLocalReceiveTimeAndLatency()
    {
        TvClock c = new TvClock(60_000, true, 1_000_000, 80);
        assertEquals(60_080, c.targetAt(1_000_000));
        assertEquals(62_080, c.targetAt(1_002_000));
        // 本地时钟往回调：不倒退
        assertEquals(60_080, c.targetAt(999_000));
    }

    @Test
    void pausedIgnoresTimeAndLatency()
    {
        TvClock c = new TvClock(42_000, false, 0, 200);
        assertEquals(42_000, c.targetAt(10_000_000));
    }

    @Test
    void latencyCapped()
    {
        assertEquals(TvClock.MAX_LATENCY_MS, new TvClock(0, true, 0, 99_999).targetAt(0));
        assertEquals(150, TvClock.latencyFromPing(300));
        assertEquals(0, TvClock.latencyFromPing(-1));
    }

    @Test
    void decideThresholds()
    {
        assertEquals(TvClock.Action.NONE, TvClock.decide(10_000, 10_250));
        assertEquals(TvClock.Action.ADJUST, TvClock.decide(10_000, 10_301));
        assertEquals(TvClock.Action.ADJUST, TvClock.decide(10_000, 8_600));
        assertEquals(TvClock.Action.SEEK, TvClock.decide(10_000, 11_501));
        assertEquals(TvClock.Action.SEEK, TvClock.decide(10_000, 0));
    }

    @Test
    void ended()
    {
        TvClock c = new TvClock(59_000, true, 0, 0);
        assertFalse(c.endedAt(500, 60_000));
        assertTrue(c.endedAt(1_000, 60_000));
        assertFalse(c.endedAt(1_000_000, 0));
    }

    @Test
    void twoClientsAgreeAfterSameState()
    {
        // 服务端 t=5000 时发出位置 3000；两台客户端时钟不同、延迟不同，各自算出的目标位置相差就是延迟估计的误差
        TvClock a = new TvClock(3000, true, 100_000, 40);
        TvClock b = new TvClock(3000, true, 7_777_000, 120);
        long da = a.targetAt(100_000 + 2_000) - 3000;
        long db = b.targetAt(7_777_000 + 2_000) - 3000;
        assertEquals(80, Math.abs(da - db));
    }
}
