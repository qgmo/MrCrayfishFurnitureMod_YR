package com.mrcrayfish.furniture.tv;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 按玩家限频（令牌桶，纯逻辑，时间由调用方传入，悠然服务器魔改）。每个键最多攒 {@code burst} 次，
 * 每秒补 {@code perSecond} 次。只在服务端主线程用。
 *
 * @param <K> 玩家标识
 */
public final class RateLimiter<K>
{
    private static final class Bucket
    {
        double tokens;
        long lastMs;
    }

    private final int burst;
    private final double perMs;
    private final Map<K, Bucket> buckets = new HashMap<>();

    public RateLimiter(int burst, double perSecond)
    {
        this.burst = Math.max(1, burst);
        this.perMs = perSecond / 1000.0;
    }

    /** 这次请求放不放行（放行就扣一次） */
    public boolean tryAcquire(K key, long nowMs)
    {
        Bucket b = buckets.get(key);
        if(b == null)
        {
            b = new Bucket();
            b.tokens = burst;
            b.lastMs = nowMs;
            buckets.put(key, b);
            if(buckets.size() > 1000)
                cleanup(nowMs);
        }
        else
        {
            long dt = Math.max(0, nowMs - b.lastMs);
            b.tokens = Math.min(burst, b.tokens + dt * perMs);
            b.lastMs = nowMs;
        }
        if(b.tokens >= 1)
        {
            b.tokens -= 1;
            return true;
        }
        return false;
    }

    /** 早就攒满的桶删掉 */
    private void cleanup(long nowMs)
    {
        Iterator<Bucket> it = buckets.values().iterator();
        while(it.hasNext())
        {
            Bucket b = it.next();
            if(b.tokens + (nowMs - b.lastMs) * perMs >= burst)
                it.remove();
        }
    }

    int size()
    {
        return buckets.size();
    }
}
