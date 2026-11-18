package com.mrcrayfish.furniture.client.tv;

/**
 * 进出大屏时换清晰度（纯逻辑，悠然服务器魔改，服主 11-18：无缝切换，不重新加载整个视频）。
 * 只管“什么时候开始准备新的一路、什么时候作废、准备好了能不能换上、失败了多久再试”，真正的准备在 {@link VideoSession} 里：
 * <ul>
 * <li>{@link #setTarget}：要换到哪一档（只留最后一次的目标，来回快速进出大屏时中途的准备作废）；</li>
 * <li>{@link #poll}：每帧问一次该做什么：开始准备（{@link Decision#START}）、作废正在准备的（{@link Decision#CANCEL}）、
 * 作废并按新目标重新准备（{@link Decision#RESTART}）、什么也不做；每次开始都发一个新的尝试编号，同一时间最多一个在准备；</li>
 * <li>{@link #ready}：某次尝试准备好了，还是现在的目标就可以换上（{@link #handedOver}），否则作废；</li>
 * <li>{@link #failed}：准备失败（网络错、接口拒绝、解码出错）：继续用旧的，等 {@value #RETRY_BASE_MS} 毫秒起翻倍（最多 {@value #RETRY_MAX_MS}）再试，
 * 连续失败 {@value #MAX_FAILURES} 次就不再试，直到目标变了。</li>
 * </ul>
 * 线程：渲染线程问、准备线程报结果，方法都加锁。时间由调用方传进来（单元测试用假时钟）。
 */
final class QualitySwitch
{
    static final long RETRY_BASE_MS = 5_000L;
    static final long RETRY_MAX_MS = 60_000L;
    static final int MAX_FAILURES = 5;

    enum Decision
    {
        NONE, START, CANCEL, RESTART
    }

    /** 现在画面用的那一档 */
    private int current;
    /** 想要的那一档 */
    private int target;
    /** 正在准备的那一档（-1 表示没有）与尝试编号 */
    private int preparing = -1;
    private int attempt;
    private int failures;
    private long retryAt;

    QualitySwitch(int initial)
    {
        this.current = initial;
        this.target = initial;
    }

    synchronized int current()
    {
        return current;
    }

    synchronized int target()
    {
        return target;
    }

    /** 正在准备的尝试编号；没有在准备时为 -1 */
    synchronized int preparingAttempt()
    {
        return preparing >= 0 ? attempt : -1;
    }

    synchronized int preparingQuality()
    {
        return preparing;
    }

    synchronized void setTarget(int quality)
    {
        if(quality != target)
        {
            target = quality;
            // 新目标：之前的失败不算
            failures = 0;
            retryAt = 0;
        }
    }

    /** 每帧问一次：该做什么。START / RESTART 之后用 {@link #preparingAttempt()} 拿新的尝试编号 */
    synchronized Decision poll(long now)
    {
        if(target == current)
        {
            if(preparing >= 0)
            {
                preparing = -1;
                attempt++;
                return Decision.CANCEL;
            }
            return Decision.NONE;
        }
        if(preparing == target)
            return Decision.NONE;
        if(preparing >= 0)
        {
            // 准备的不是最新的目标：作废，按新目标重来
            preparing = target;
            attempt++;
            return Decision.RESTART;
        }
        if(failures >= MAX_FAILURES || now < retryAt)
            return Decision.NONE;
        preparing = target;
        attempt++;
        return Decision.START;
    }

    /** 这次尝试还有效（没被作废） */
    synchronized boolean valid(int attemptId)
    {
        return preparing >= 0 && attemptId == attempt;
    }

    /** 准备好了：还有效就可以换上（换上后调 {@link #handedOver}）；返回 false 表示已作废，调用方关掉新的一路 */
    synchronized boolean ready(int attemptId)
    {
        return valid(attemptId);
    }

    /** 换上了 */
    synchronized void handedOver(int attemptId)
    {
        if(!valid(attemptId))
            return;
        current = preparing;
        preparing = -1;
        failures = 0;
        retryAt = 0;
    }

    /** 准备失败：继续用旧的，过一会儿再试 */
    synchronized void failed(int attemptId, long now)
    {
        if(!valid(attemptId))
            return;
        preparing = -1;
        failures++;
        retryAt = now + Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(10, failures - 1));
    }

    /** 准备被别的原因打断（例如跳转了进度）：不算失败，下一帧重新开始 */
    synchronized void interrupted(int attemptId)
    {
        if(valid(attemptId))
            preparing = -1;
    }

    synchronized int failures()
    {
        return failures;
    }
}
