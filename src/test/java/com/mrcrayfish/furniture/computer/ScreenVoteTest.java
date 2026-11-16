package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ScreenVoteTest
{
    private static final UUID OP = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    private static byte[] px(int... v)
    {
        byte[] b = new byte[v.length];
        for(int i = 0; i < v.length; i++)
            b[i] = (byte) v[i];
        return b;
    }

    @Test
    void oneViewerIsAcceptedAsIs()
    {
        ScreenVote v = new ScreenVote();
        v.submit(A, 3, 0, px(1, 2, 3), 0);
        assertArrayEquals(px(1, 2, 3), v.decide(3, 0, Collections.singleton(A), null, 0), "只有一个人开着：照收，不校验");
        ScreenVote w = new ScreenVote();
        w.submit(OP, 3, 0, px(9, 9, 9), 0);
        assertArrayEquals(px(9, 9, 9), w.decide(3, 0, Collections.singleton(OP), OP, 0));
    }

    @Test
    void threeViewersTakeTheMajority()
    {
        ScreenVote v = new ScreenVote();
        List<UUID> viewers = Arrays.asList(OP, A, B);
        v.submit(OP, 1, 0, px(1, 1, 7, 4), 0);
        v.submit(A, 1, 0, px(2, 1, 5, 4), 10);
        v.submit(B, 1, 0, px(2, 3, 5, 6), 20);
        // 像素 0：2 对 1 → 2（操作者被多数压过）；像素 1：1 赢；像素 2：5 赢；像素 3：4 赢
        assertArrayEquals(px(2, 1, 5, 4), v.decide(1, 0, viewers, OP, 30));
    }

    @Test
    void threeWayTieGoesToTheOperator()
    {
        ScreenVote v = new ScreenVote();
        List<UUID> viewers = Arrays.asList(A, OP, B);
        v.submit(A, 1, 0, px(1), 0);
        v.submit(OP, 1, 0, px(2), 1);
        v.submit(B, 1, 0, px(3), 2);
        assertArrayEquals(px(2), v.decide(1, 0, viewers, OP, 3));
    }

    @Test
    void twoViewersTieGoesToTheOperator()
    {
        ScreenVote v = new ScreenVote();
        List<UUID> viewers = Arrays.asList(OP, A);
        v.submit(A, 1, 0, px(5, 5, 6), 0);
        v.submit(OP, 1, 0, px(5, 7, 8), 10);
        assertArrayEquals(px(5, 7, 8), v.decide(1, 0, viewers, OP, 20), "两个人对不上听操作者的");
    }

    @Test
    void staleUploadsAreIgnored()
    {
        ScreenVote v = new ScreenVote();
        List<UUID> viewers = Arrays.asList(OP, A, B);
        v.submit(A, 1, 0, px(3), 0);
        v.submit(B, 1, 0, px(3), 0);
        v.submit(OP, 2, 0, px(9), 5);
        // 当前是第 2 页：A、B 那两张是上一页的，不算，只剩操作者那张
        assertArrayEquals(px(9), v.decide(2, 0, viewers, OP, 10));
        assertEquals(1, v.size(), "过时的票丢掉");
        // 滚动位置对不上也不算
        v.submit(A, 2, 40, px(4), 20);
        assertArrayEquals(px(9), v.decide(2, 0, viewers, OP, 30));
        assertNull(v.decide(3, 0, viewers, OP, 40), "换了页面：一张有效的都没有");
        assertTrue(v.isEmpty());
    }

    @Test
    void uploadsFromPeopleWhoLeftAreIgnored()
    {
        ScreenVote v = new ScreenVote();
        v.submit(A, 1, 0, px(3), 0);
        v.submit(B, 1, 0, px(3), 0);
        v.submit(OP, 1, 0, px(9), 0);
        // A、B 关了界面：只剩操作者一个人
        assertArrayEquals(px(9), v.decide(1, 0, Collections.singleton(OP), OP, 10));
    }

    @Test
    void newUploadReplacesOld()
    {
        ScreenVote v = new ScreenVote();
        List<UUID> viewers = Arrays.asList(OP, A, B);
        v.submit(A, 1, 0, px(3), 0);
        v.submit(B, 1, 0, px(3), 0);
        v.submit(OP, 1, 0, px(9), 0);
        assertArrayEquals(px(3), v.decide(1, 0, viewers, OP, 1));
        v.submit(A, 1, 0, px(9), 2);
        assertArrayEquals(px(9), v.decide(1, 0, viewers, OP, 3), "A 改投了，9 成了多数");
        assertEquals(3, v.size());
    }

    @Test
    void loneSpectatorWaitsForOthers()
    {
        ScreenVote v = new ScreenVote();
        List<UUID> viewers = Arrays.asList(OP, A, B);
        v.submit(A, 1, 0, px(4), 1000);
        assertNull(v.decide(1, 0, viewers, OP, 1500), "多人开着、只来了旁观者一张：先等");
        assertArrayEquals(px(4), v.decide(1, 0, viewers, OP, 1000 + ScreenVote.HOLD_MS), "等不到就照收");
        ScreenVote w = new ScreenVote();
        w.submit(OP, 1, 0, px(6), 1000);
        assertArrayEquals(px(6), w.decide(1, 0, viewers, OP, 1000), "操作者那张不用等");
        ScreenVote x = new ScreenVote();
        x.submit(A, 1, 0, px(4), 1000);
        x.submit(C, 1, 0, px(5), 1100);
        assertArrayEquals(px(4), x.decide(1, 0, Arrays.asList(A, C), null, 1100), "没人操作、两个旁观者一样多：取先到的");
    }
}
