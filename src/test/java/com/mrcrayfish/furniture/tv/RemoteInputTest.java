package com.mrcrayfish.furniture.tv;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RemoteInputTest
{
    private static final String TV = "tv";

    private static List<String> names(List<RemoteInput.Output<String>> out)
    {
        return out.stream().map(Object::toString).collect(Collectors.toList());
    }

    @Test
    void shortPressesOnRelease()
    {
        RemoteInput<String> in = new RemoteInput<>();
        List<RemoteInput.Output<String>> out = new ArrayList<>();
        assertTrue(in.press(RemoteInput.Button.LEFT, false, TV, 0, out));
        assertTrue(out.isEmpty());
        in.tick(200, out);
        assertTrue(in.release(RemoteInput.Button.LEFT, 300, out));
        assertEquals(1, out.size());
        assertEquals(RemoteAction.TOGGLE_PLAY, out.get(0).action);
        out.clear();
        in.press(RemoteInput.Button.RIGHT, false, TV, 1000, out);
        in.release(RemoteInput.Button.RIGHT, 1100, out);
        assertEquals(RemoteAction.TOGGLE_OSD, out.get(0).action);
    }

    @Test
    void longPressRepeatsEveryHalfSecondAndSkipsShort()
    {
        RemoteInput<String> in = new RemoteInput<>();
        List<RemoteInput.Output<String>> out = new ArrayList<>();
        in.press(RemoteInput.Button.RIGHT, false, TV, 0, out);
        in.tick(499, out);
        assertTrue(out.isEmpty());
        in.tick(500, out);
        in.tick(990, out);
        in.tick(1000, out);
        in.tick(1600, out);
        in.release(RemoteInput.Button.RIGHT, 1700, out);
        // 0.5、1.0、1.5 秒各一步，松开不再出短按
        assertEquals(3, out.size());
        for(RemoteInput.Output<String> o : out)
            assertEquals(RemoteAction.SEEK_FORWARD, o.action);
    }

    @Test
    void longPressCatchesUpWhenTicksAreLate()
    {
        RemoteInput<String> in = new RemoteInput<>();
        List<RemoteInput.Output<String>> out = new ArrayList<>();
        in.press(RemoteInput.Button.LEFT, false, TV, 0, out);
        in.release(RemoteInput.Button.LEFT, 1250, out);
        assertEquals(2, out.size());
        assertEquals(RemoteAction.SEEK_BACK, out.get(0).action);
    }

    @Test
    void sneakMapping()
    {
        RemoteInput<String> in = new RemoteInput<>();
        List<RemoteInput.Output<String>> out = new ArrayList<>();
        in.press(RemoteInput.Button.LEFT, true, TV, 0, out);
        in.release(RemoteInput.Button.LEFT, 100, out);
        in.press(RemoteInput.Button.RIGHT, true, TV, 200, out);
        in.release(RemoteInput.Button.RIGHT, 300, out);
        in.press(RemoteInput.Button.LEFT, true, TV, 400, out);
        in.release(RemoteInput.Button.LEFT, 1000, out);
        in.press(RemoteInput.Button.RIGHT, true, TV, 2000, out);
        in.release(RemoteInput.Button.RIGHT, 2600, out);
        assertEquals("[PREV_CHANNEL@tv, NEXT_CHANNEL@tv, VOLUME_DOWN@tv, VOLUME_UP@tv]", names(out).toString());
    }

    @Test
    void middleOnlyWithSneakAndPowerOnce()
    {
        RemoteInput<String> in = new RemoteInput<>();
        List<RemoteInput.Output<String>> out = new ArrayList<>();
        // 不潜行的中键交给游戏（选取方块）
        assertFalse(in.press(RemoteInput.Button.MIDDLE, false, TV, 0, out));
        assertFalse(in.release(RemoteInput.Button.MIDDLE, 100, out));
        assertTrue(out.isEmpty());
        assertTrue(in.press(RemoteInput.Button.MIDDLE, true, TV, 200, out));
        in.release(RemoteInput.Button.MIDDLE, 300, out);
        assertEquals(RemoteAction.TOGGLE_DANMAKU, out.get(0).action);
        out.clear();
        in.press(RemoteInput.Button.MIDDLE, true, TV, 1000, out);
        in.tick(1600, out);
        in.tick(3000, out);
        in.release(RemoteInput.Button.MIDDLE, 3500, out);
        assertEquals(1, out.size());
        assertEquals(RemoteAction.POWER, out.get(0).action);
    }

    @Test
    void notAimingOnlySneakLeftOpensConfig()
    {
        RemoteInput<String> in = new RemoteInput<>();
        List<RemoteInput.Output<String>> out = new ArrayList<>();
        assertTrue(in.press(RemoteInput.Button.LEFT, true, null, 0, out));
        assertEquals(1, out.size());
        assertTrue(out.get(0).isOpenConfig());
        assertTrue(in.release(RemoteInput.Button.LEFT, 50, out));
        assertEquals(1, out.size());
        // 潜行右键（电池仓）、普通左右键交给游戏
        assertFalse(in.press(RemoteInput.Button.RIGHT, true, null, 100, out));
        assertFalse(in.press(RemoteInput.Button.LEFT, false, null, 100, out));
        assertFalse(in.release(RemoteInput.Button.RIGHT, 200, out));
        assertEquals(1, out.size());
    }

    @Test
    void targetFixedAtPressAndCancel()
    {
        RemoteInput<String> in = new RemoteInput<>();
        List<RemoteInput.Output<String>> out = new ArrayList<>();
        in.press(RemoteInput.Button.LEFT, false, "a", 0, out);
        in.tick(600, out);
        assertEquals("a", out.get(0).target);
        in.cancelAll();
        in.tick(5000, out);
        assertFalse(in.release(RemoteInput.Button.LEFT, 5000, out));
        assertEquals(1, out.size());
    }
}
