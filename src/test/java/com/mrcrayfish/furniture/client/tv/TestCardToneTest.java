package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 直播没开播时的测试卡与提示音（程序生成，服主 11-18） */
class TestCardToneTest
{
    private static int at(int[] px, int x, int y)
    {
        return px[y * TestCard.WIDTH + x];
    }

    @Test
    void cardIsDeterministicAndHasTheUsualParts()
    {
        int[] a = TestCard.render(TestCard.WIDTH, TestCard.HEIGHT);
        int[] b = TestCard.render(TestCard.WIDTH, TestCard.HEIGHT);
        assertArrayEquals(a, b);
        assertEquals(TestCard.WIDTH * TestCard.HEIGHT, a.length);
        TestCard.Geometry g = TestCard.geometry(TestCard.WIDTH, TestCard.HEIGHT);
        // 外框黑、背景灰、大圆白边
        assertEquals(TestCard.BLACK, at(a, 0, 0));
        assertEquals(TestCard.GRAY, at(a, g.cx - g.r - 6, g.cy + 3));
        assertEquals(TestCard.WHITE, at(a, g.cx, g.cy - g.r + 1));
        // 彩条：从左到右八种颜色
        int y = (g.barsTop + g.grayTop) / 2;
        for(int i = 0; i < 8; i++)
        {
            int x = g.cx - g.r + (2 * i + 1) * g.r / 8;
            long dx = x - g.cx, dy = y - g.cy;
            if(dx * dx + dy * dy < (long) (g.r - 3) * (g.r - 3))
                assertEquals(TestCard.BARS[i], at(a, x, y), "bar " + i);
        }
        // 灰阶：从左到右变亮
        int gy = (g.grayTop + g.gratingTop) / 2;
        int left = at(a, g.cx - g.r / 2, gy) & 0xFF, right = at(a, g.cx + g.r / 2, gy) & 0xFF;
        assertTrue(left < right);
        // 频率条只有黑白；频道号那条是黑的
        int fy = (g.gratingTop + g.labelTop) / 2;
        for(int x = g.cx - g.r / 2; x < g.cx + g.r / 2; x++)
        {
            int c = at(a, x, fy);
            assertTrue(c == TestCard.BLACK || c == TestCard.WHITE);
        }
        assertEquals(TestCard.BLACK, at(a, g.cx + g.r / 3, (g.labelTop + g.labelBottom) / 2));
        // 圆心十字
        assertEquals(TestCard.WHITE, at(a, g.cx, g.cy));
    }

    @Test
    void otherSizesWork()
    {
        assertEquals(64 * 48, TestCard.render(64, 48).length);
    }

    @Test
    void toneIsAQuietSeamlessSine()
    {
        short[] s = Tone.standard();
        assertEquals(Tone.SAMPLES, s.length);
        // 1 kHz、48 kHz：每 48 个样本一个周期，整段正好 100 个周期，循环时接得上
        assertEquals(0, Tone.SAMPLES % (Tone.SAMPLE_RATE / Tone.FREQUENCY));
        for(int i = 0; i + 48 < s.length; i += 7)
            assertEquals(s[i], s[i + 48]);
        assertEquals(s[0], (short) 0);
        assertEquals(s[s.length - 48 + 1], s[1]);
        int max = 0;
        for(short v : s)
            max = Math.max(max, Math.abs(v));
        assertEquals(Tone.AMPLITUDE, max);
        assertTrue(max < Short.MAX_VALUE / 3);
        // 四分之一周期处是波峰
        assertEquals(Tone.AMPLITUDE, s[12]);
    }
}
