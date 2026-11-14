package com.mrcrayfish.furniture.tv;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 拿着遥控器时左、右、中三个键的按法（纯逻辑，时间由调用方传入，悠然服务器魔改，服主 11-14 定）。
 * <p>
 * 对着电视：
 * <ul>
 * <li>左键：播放 / 暂停；长按：后退（每半秒 10 秒）；</li>
 * <li>右键：弹出进度条；长按：快进（每半秒 10 秒）；</li>
 * <li>潜行 + 左键：上一个频道；长按：音量减；</li>
 * <li>潜行 + 右键：下一个频道；长按：音量加；</li>
 * <li>潜行 + 中键：弹幕开关；长按：开机 / 关机（只一次）。</li>
 * </ul>
 * 短按在松开时才算（按住超过 {@value #LONG_MS} ms 就是长按，松开不再算短按），所以长按、短按不冲突。
 * 没对着电视时只管潜行 + 左键（打开本地配置界面），别的键照常交给游戏（潜行 + 右键是电池仓）。
 * 潜行与否、对着哪台电视，都按按下那一刻算。
 *
 * @param <T> 电视的标识（方块坐标）
 */
public final class RemoteInput<T>
{
    /** 按住多久算长按 */
    public static final long LONG_MS = 500L;
    /** 长按时每隔多久再来一步 */
    public static final long REPEAT_MS = 500L;

    public enum Button
    {
        LEFT, RIGHT, MIDDLE
    }

    /** 一次操作：对哪台电视做什么；target 为 null 表示打开本地配置界面 */
    public static final class Output<T>
    {
        @Nullable
        public final T target;
        @Nullable
        public final RemoteAction action;

        Output(@Nullable T target, @Nullable RemoteAction action)
        {
            this.target = target;
            this.action = action;
        }

        public boolean isOpenConfig()
        {
            return action == null;
        }

        @Override
        public String toString()
        {
            return action == null ? "OPEN_CONFIG" : action + "@" + target;
        }
    }

    private static final class Held<T>
    {
        final T target;
        final boolean sneaking;
        final long downAt;
        int steps;

        Held(T target, boolean sneaking, long downAt)
        {
            this.target = target;
            this.sneaking = sneaking;
            this.downAt = downAt;
        }
    }

    private final Map<Button, Held<T>> held = new EnumMap<>(Button.class);
    /** 被截住（不交给游戏）的键：松开时也要截住 */
    private final Map<Button, Boolean> captured = new EnumMap<>(Button.class);

    /**
     * 按下。返回 true 表示这次按键归遥控器（调用方要拦住，不让游戏再处理）。
     *
     * @param tv 对着的电视，没对着时为 null
     */
    public boolean press(Button button, boolean sneaking, @Nullable T tv, long nowMs, List<Output<T>> out)
    {
        if(tv == null)
        {
            if(button == Button.LEFT && sneaking)
            {
                captured.put(button, true);
                out.add(new Output<>(null, null));
                return true;
            }
            captured.remove(button);
            return false;
        }
        if(button == Button.MIDDLE && !sneaking)
        {
            captured.remove(button);
            return false;
        }
        captured.put(button, true);
        if(!held.containsKey(button))
        {
            held.put(button, new Held<>(tv, sneaking, nowMs));
        }
        return true;
    }

    /** 松开。返回 true 表示按下时被遥控器截住了（松开也要拦住） */
    public boolean release(Button button, long nowMs, List<Output<T>> out)
    {
        Held<T> h = held.remove(button);
        if(h != null)
        {
            // 先补上到松开为止该有的长按步数，再看算不算短按
            steps(button, h, nowMs, out);
            if(h.steps == 0)
            {
                RemoteAction shortAction = shortAction(button, h.sneaking);
                if(shortAction != null)
                    out.add(new Output<>(h.target, shortAction));
            }
        }
        Boolean was = captured.remove(button);
        return was != null && was;
    }

    /** 每帧（或每 tick）调一次：按住的键到点了就出长按的一步 */
    public void tick(long nowMs, List<Output<T>> out)
    {
        for(Map.Entry<Button, Held<T>> e : held.entrySet())
        {
            steps(e.getKey(), e.getValue(), nowMs, out);
        }
    }

    /** 换了手里的东西、打开别的界面、断线时：放掉所有按着的键，不再出操作 */
    public void cancelAll()
    {
        held.clear();
        captured.clear();
    }

    public boolean isHeld(Button button)
    {
        return held.containsKey(button);
    }

    private void steps(Button button, Held<T> h, long nowMs, List<Output<T>> out)
    {
        RemoteAction longAction = longAction(button, h.sneaking);
        if(longAction == null)
            return;
        long elapsed = nowMs - h.downAt;
        if(elapsed < LONG_MS)
            return;
        int due = longAction == RemoteAction.POWER ? 1 : (int) ((elapsed - LONG_MS) / REPEAT_MS) + 1;
        while(h.steps < due)
        {
            h.steps++;
            out.add(new Output<>(h.target, longAction));
        }
    }

    @Nullable
    static RemoteAction shortAction(Button button, boolean sneaking)
    {
        switch(button)
        {
            case LEFT:
                return sneaking ? RemoteAction.PREV_CHANNEL : RemoteAction.TOGGLE_PLAY;
            case RIGHT:
                return sneaking ? RemoteAction.NEXT_CHANNEL : RemoteAction.TOGGLE_OSD;
            default:
                return sneaking ? RemoteAction.TOGGLE_DANMAKU : null;
        }
    }

    @Nullable
    static RemoteAction longAction(Button button, boolean sneaking)
    {
        switch(button)
        {
            case LEFT:
                return sneaking ? RemoteAction.VOLUME_DOWN : RemoteAction.SEEK_BACK;
            case RIGHT:
                return sneaking ? RemoteAction.VOLUME_UP : RemoteAction.SEEK_FORWARD;
            default:
                return sneaking ? RemoteAction.POWER : null;
        }
    }
}
