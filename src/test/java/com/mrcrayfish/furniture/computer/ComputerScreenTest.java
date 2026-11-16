package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ComputerScreenTest
{
    private static final UUID OP = UUID.randomUUID(), OTHER = UUID.randomUUID(), SPEC = UUID.randomUUID();

    private static ComputerScreen showing(byte[] frame)
    {
        ComputerScreen s = new ComputerScreen();
        s.operatorChanged(OP);
        assertTrue(s.submit(OP, 1, 0, frame, 1, 0, Collections.singleton(OP), OP, 0));
        assertArrayEquals(frame, s.frame());
        return s;
    }

    @Test
    void acceptsCurrentAndKeepsLastFrameWhenEveryoneLeaves()
    {
        ComputerScreen s = showing(new byte[]{1, 2});
        assertFalse(s.submit(OP, 1, 0, new byte[]{1, 2}, 1, 0, Collections.singleton(OP), OP, 1), "一样的不算变了");
        assertFalse(s.submit(OP, 0, 0, new byte[]{3, 3}, 1, 0, Collections.singleton(OP), OP, 2), "过时的不收");
        // 没人开着界面了：停在最后一帧
        assertFalse(s.decide(1, 0, Collections.<UUID>emptyList(), null, 3));
        assertFalse(s.operatorChanged(null), "操作者走开不清屏");
        assertArrayEquals(new byte[]{1, 2}, s.frame());
        assertFalse(s.hasPendingVotes());
    }

    @Test
    void clearedOnPowerOff()
    {
        ComputerScreen s = showing(new byte[]{1, 2});
        assertTrue(s.clear(), "关机、没电、页面清掉");
        assertNull(s.frame());
        assertFalse(s.clear(), "已经是空的");
    }

    @Test
    void clearedWhenAnotherPlayerOperates()
    {
        ComputerScreen s = showing(new byte[]{1, 2});
        assertFalse(s.operatorChanged(null));
        assertFalse(s.operatorChanged(OP), "原来的人回来接着用不清");
        assertNotNull(s.frame());
        assertTrue(s.operatorChanged(OTHER), "换了别人操作：清屏");
        assertNull(s.frame());
        // 走开后换人也清
        ComputerScreen t = showing(new byte[]{1, 2});
        t.operatorChanged(null);
        assertTrue(t.operatorChanged(OTHER));
    }

    @Test
    void pageChangeKeepsFrameUntilNewVotes()
    {
        ComputerScreen s = showing(new byte[]{1, 2});
        List<UUID> viewers = Arrays.asList(OP, SPEC);
        s.submit(SPEC, 1, 0, new byte[]{1, 2}, 1, 0, viewers, OP, 1);
        s.pageChanged();
        assertFalse(s.hasPendingVotes(), "换页后以前的票都不算");
        assertArrayEquals(new byte[]{1, 2}, s.frame(), "画面先留着");
        assertTrue(s.submit(OP, 2, 0, new byte[]{4, 4}, 2, 0, viewers, OP, 2));
        assertArrayEquals(new byte[]{4, 4}, s.frame());
    }
}
