package com.mrcrayfish.furniture.computer;

/**
 * 令牌桶（纯逻辑，时间由调用方给）：每秒补 {@code rate} 个，最多攒 {@code burst} 个。
 */
public final class RateLimiter
{
    private final double rate;
    private final double burst;
    private double tokens;
    private long last = Long.MIN_VALUE;

    public RateLimiter(double rate, double burst)
    {
        this.rate = rate;
        this.burst = burst;
        this.tokens = burst;
    }

    /** 取 cost 个令牌；不够返回 false（不扣） */
    public boolean tryAcquire(long nowMillis, double cost)
    {
        if(last != Long.MIN_VALUE && nowMillis > last)
        {
            tokens = Math.min(burst, tokens + (nowMillis - last) * rate / 1000.0);
        }
        if(last == Long.MIN_VALUE || nowMillis > last)
        {
            last = nowMillis;
        }
        if(tokens < cost)
            return false;
        tokens -= cost;
        return true;
    }
}
