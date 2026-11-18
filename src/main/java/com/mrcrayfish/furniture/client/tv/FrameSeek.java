package com.mrcrayfish.furniture.client.tv;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 新的一路视频“解到对上当前位置”（纯逻辑，悠然服务器魔改，服主 11-18 无缝换清晰度）：解码器已经跳到当前位置前最近的关键帧，
 * 这里一帧一帧往下解，时间戳早于当前位置的丢掉，第一帧不早于当前位置（减去 {@value #TOLERANCE_MS} 毫秒）的就是要换上的那一帧。
 * 当前位置在解的过程中还在往前走，每一帧都重新取。解码器、时钟、作废都通过接口传进来，单元测试用模拟解码器。
 */
final class FrameSeek
{
    /** 早于当前位置这么多以内也算对上（一帧左右） */
    static final long TOLERANCE_MS = 40L;

    interface Source<F>
    {
        /** 下一帧画面；读完返回 null */
        @Nullable
        F next() throws Exception;

        long ptsMs(F frame);
    }

    private FrameSeek() {}

    /**
     * 解到对上 target 的那一帧并返回；被作废（cancelled 为真）、读完、或解了 maxFrames 帧还没对上时返回 null。
     */
    @Nullable
    static <F> F seek(Source<F> source, LongSupplier target, BooleanSupplier cancelled, int maxFrames) throws Exception
    {
        for(int i = 0; i < maxFrames; i++)
        {
            if(cancelled.getAsBoolean())
                return null;
            F f = source.next();
            if(f == null)
                return null;
            if(source.ptsMs(f) >= target.getAsLong() - TOLERANCE_MS)
                return f;
        }
        return null;
    }
}
