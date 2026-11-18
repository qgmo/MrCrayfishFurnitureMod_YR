package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

/** 进出大屏时无缝换清晰度：什么时候准备、作废、换上、失败退回（假时钟、模拟解码器，服主 11-18） */
class QualitySwitchTest
{
    private static final int SD = BiliData.QN_480P, HD = BiliData.QN_1080P, MID = BiliData.QN_720P;

    @Test
    void nothingToDoWhileTheTargetIsCurrent()
    {
        QualitySwitch q = new QualitySwitch(SD);
        assertEquals(QualitySwitch.Decision.NONE, q.poll(0));
        q.setTarget(SD);
        assertEquals(QualitySwitch.Decision.NONE, q.poll(0));
        assertEquals(-1, q.preparingAttempt());
    }

    @Test
    void prepareThenHandOver()
    {
        QualitySwitch q = new QualitySwitch(SD);
        q.setTarget(HD);
        assertEquals(QualitySwitch.Decision.START, q.poll(0));
        int a = q.preparingAttempt();
        assertEquals(HD, q.preparingQuality());
        // 准备期间：继续用旧的，不再重复开始
        assertEquals(SD, q.current());
        assertEquals(QualitySwitch.Decision.NONE, q.poll(100));
        assertEquals(QualitySwitch.Decision.NONE, q.poll(200));
        assertTrue(q.ready(a));
        q.handedOver(a);
        assertEquals(HD, q.current());
        assertEquals(-1, q.preparingAttempt());
        assertEquals(QualitySwitch.Decision.NONE, q.poll(300));
        // 退出大屏：同样无缝地降下来
        q.setTarget(SD);
        assertEquals(QualitySwitch.Decision.START, q.poll(400));
        int b = q.preparingAttempt();
        assertNotEquals(a, b);
        q.handedOver(b);
        assertEquals(SD, q.current());
    }

    @Test
    void quickTogglingKeepsOnlyTheLastTarget()
    {
        QualitySwitch q = new QualitySwitch(SD);
        q.setTarget(HD);
        assertEquals(QualitySwitch.Decision.START, q.poll(0));
        int a = q.preparingAttempt();
        // 马上又退出大屏：目标回到现在用的，正在准备的作废
        q.setTarget(SD);
        assertEquals(QualitySwitch.Decision.CANCEL, q.poll(10));
        assertFalse(q.valid(a));
        assertFalse(q.ready(a));
        q.handedOver(a);
        assertEquals(SD, q.current());
        // 进、出、再进：中途的作废，只按最后一次准备
        q.setTarget(HD);
        assertEquals(QualitySwitch.Decision.START, q.poll(20));
        int b = q.preparingAttempt();
        q.setTarget(MID);
        assertEquals(QualitySwitch.Decision.RESTART, q.poll(30));
        int c = q.preparingAttempt();
        assertFalse(q.valid(b));
        assertTrue(q.valid(c));
        assertEquals(MID, q.preparingQuality());
        // 晚到的旧结果不能换上
        assertFalse(q.ready(b));
        q.failed(b, 40);
        assertEquals(0, q.failures());
        q.handedOver(c);
        assertEquals(MID, q.current());
    }

    @Test
    void failureKeepsTheOldOneAndRetriesWithACap()
    {
        QualitySwitch q = new QualitySwitch(SD);
        q.setTarget(HD);
        long now = 0;
        long[] expected = {5_000, 10_000, 20_000, 40_000, 60_000};
        for(int i = 0; i < QualitySwitch.MAX_FAILURES; i++)
        {
            assertEquals(QualitySwitch.Decision.START, q.poll(now), "attempt " + i);
            q.failed(q.preparingAttempt(), now);
            assertEquals(SD, q.current());
            // 等够之前不再试
            assertEquals(QualitySwitch.Decision.NONE, q.poll(now + expected[i] - 1));
            now += expected[i];
        }
        // 连续失败到上限：不再试
        assertEquals(QualitySwitch.Decision.NONE, q.poll(now + 3_600_000));
        // 目标变了：重新算
        q.setTarget(MID);
        assertEquals(QualitySwitch.Decision.START, q.poll(now));
    }

    @Test
    void interruptedIsNotAFailure()
    {
        QualitySwitch q = new QualitySwitch(SD);
        q.setTarget(HD);
        assertEquals(QualitySwitch.Decision.START, q.poll(0));
        q.interrupted(q.preparingAttempt());
        assertEquals(0, q.failures());
        // 下一帧马上重新开始
        assertEquals(QualitySwitch.Decision.START, q.poll(1));
    }

    /** 模拟解码器：每 40 毫秒一帧，从关键帧 0 开始 */
    private static final class FakeDecoder implements FrameSeek.Source<Long>
    {
        final Deque<Long> frames = new ArrayDeque<>();
        int decoded;

        FakeDecoder(long from, long to)
        {
            for(long t = from; t <= to; t += 40)
                frames.add(t);
        }

        @Override
        public Long next()
        {
            decoded++;
            return frames.pollFirst();
        }

        @Override
        public long ptsMs(Long frame)
        {
            return frame;
        }
    }

    @Test
    void decodesUpToTheCurrentPositionAndDropsEarlierFrames() throws Exception
    {
        FakeDecoder d = new FakeDecoder(0, 10_000);
        // 当前位置在解的过程中往前走：每解一帧时间过去 10 毫秒（解得比放得快）
        long[] clock = {2_000};
        Long f = FrameSeek.seek(d, () -> clock[0] += 10, () -> false, 1000);
        assertNotNull(f);
        long target = clock[0];
        assertTrue(f >= target - FrameSeek.TOLERANCE_MS, f + " vs " + target);
        assertTrue(f - 40 < target - FrameSeek.TOLERANCE_MS, "the frame before was still too early");
    }

    @Test
    void seekGivesUpWhenCancelledEndedOrTooSlow() throws Exception
    {
        assertNull(FrameSeek.seek(new FakeDecoder(0, 10_000), () -> 5_000L, () -> true, 1000));
        FakeDecoder d = new FakeDecoder(0, 10_000);
        int[] calls = {0};
        assertNull(FrameSeek.seek(d, () -> 5_000L, () -> ++calls[0] > 3, 1000));
        assertEquals(3, d.decoded);
        // 读完了还没对上（位置在流的末尾之后）
        assertNull(FrameSeek.seek(new FakeDecoder(0, 1_000), () -> 5_000L, () -> false, 1000));
        // 解得比放得慢，解了上限帧数还没追上：算失败
        assertNull(FrameSeek.seek(new FakeDecoder(0, 100_000), () -> 50_000L, () -> false, 100));
        // 一开始就对上
        assertEquals(Long.valueOf(0), FrameSeek.seek(new FakeDecoder(0, 1_000), () -> 0L, () -> false, 1000));
        assertEquals(Arrays.asList(0L), Arrays.asList(FrameSeek.seek(new FakeDecoder(0, 0), () -> 20L, () -> false, 10)));
    }
}
