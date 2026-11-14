package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest
{
    @Test
    void burstThenRefill()
    {
        RateLimiter<String> r = new RateLimiter<>(3, 2.0);
        assertTrue(r.tryAcquire("a", 0));
        assertTrue(r.tryAcquire("a", 0));
        assertTrue(r.tryAcquire("a", 0));
        assertFalse(r.tryAcquire("a", 0));
        // 每秒补 2 次：250 ms 不够一次，500 ms 一次
        assertFalse(r.tryAcquire("a", 250));
        assertTrue(r.tryAcquire("a", 500));
        assertFalse(r.tryAcquire("a", 500));
        // 别的玩家不受影响
        assertTrue(r.tryAcquire("b", 500));
    }

    @Test
    void refillCapsAtBurst()
    {
        RateLimiter<String> r = new RateLimiter<>(2, 10.0);
        r.tryAcquire("a", 0);
        r.tryAcquire("a", 0);
        assertTrue(r.tryAcquire("a", 100_000));
        assertTrue(r.tryAcquire("a", 100_000));
        assertFalse(r.tryAcquire("a", 100_000));
    }

    @Test
    void remoteLimitAllowsLongPresses()
    {
        // 三个键同时长按（每键每半秒一步 = 每秒 6 步）一段时间后会被限住，单键长按（每秒 2 步）不会
        RateLimiter<String> r = new RateLimiter<>(10, 5.0);
        int denied = 0;
        for(long t = 0; t < 60_000; t += 500)
            if(!r.tryAcquire("one", t))
                denied++;
        assertEquals(0, denied);
    }

    @Test
    void oldBucketsCleanedUp()
    {
        RateLimiter<Integer> r = new RateLimiter<>(1, 1.0);
        for(int i = 0; i < 1500; i++)
            r.tryAcquire(i, 0);
        r.tryAcquire(-1, 10_000);
        assertTrue(r.size() < 1500);
    }
}
