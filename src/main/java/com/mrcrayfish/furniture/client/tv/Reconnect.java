package com.mrcrayfish.furniture.client.tv;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 断线自动重连（纯逻辑，悠然服务器魔改，服主 11-18 直播弹幕）：每次连接断了（或连不上）就等一会儿再连，
 * 等的时间从 {@value #MIN_DELAY_MS} 毫秒起每次翻倍，最多 {@value #MAX_DELAY_MS} 毫秒；一次连接撑过 {@value #STABLE_MS} 毫秒算稳定，
 * 下一次又从最短的开始。不加随机抖动（同一份输入每次都一样）。时钟、等待都传进来，单元测试用假时钟。
 */
final class Reconnect
{
    static final long MIN_DELAY_MS = 1_000L;
    static final long MAX_DELAY_MS = 60_000L;
    static final long STABLE_MS = 60_000L;

    interface Attempt
    {
        /** 连一次，连接断了才返回（或抛异常） */
        void run() throws Exception;
    }

    interface Sleeper
    {
        void sleep(long ms) throws InterruptedException;
    }

    private long delay = MIN_DELAY_MS;

    /** 一次连接持续了 durationMs 后断了：返回要等多久再连 */
    long next(long durationMs)
    {
        if(durationMs >= STABLE_MS)
            delay = MIN_DELAY_MS;
        long d = delay;
        delay = Math.min(MAX_DELAY_MS, delay * 2);
        return d;
    }

    /**
     * 一直连到 stopped 为真（或等待时被中断）。每次出错交给 onError（只记日志用；已经要停了的不算）。
     */
    static void loop(Attempt attempt, BooleanSupplier stopped, LongSupplier clock, Sleeper sleeper, Consumer<Exception> onError)
    {
        Reconnect backoff = new Reconnect();
        while(!stopped.getAsBoolean())
        {
            long start = clock.getAsLong();
            try
            {
                attempt.run();
            }
            catch(InterruptedException e)
            {
                return;
            }
            catch(Exception e)
            {
                // 关掉时连接被我们自己断开，不算出错
                if(stopped.getAsBoolean())
                    return;
                onError.accept(e);
            }
            if(stopped.getAsBoolean())
                return;
            try
            {
                sleeper.sleep(backoff.next(clock.getAsLong() - start));
            }
            catch(InterruptedException e)
            {
                return;
            }
        }
    }
}
