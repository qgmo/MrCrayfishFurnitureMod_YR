package com.mrcrayfish.furniture.tv;

import javax.annotation.Nullable;

/**
 * 遥控器发给服务端的操作（悠然服务器魔改，服主 11-14 定的按法见 {@link RemoteInput}）。
 * 每次操作（长按的每一步也算一次）耗遥控器电池 2 FE。
 */
public enum RemoteAction
{
    /** 左键：播放 / 暂停 */
    TOGGLE_PLAY,
    /** 右键：弹出 / 收起进度条 */
    TOGGLE_OSD,
    /** 左键长按：后退 10 秒（每半秒一次） */
    SEEK_BACK,
    /** 右键长按：快进 10 秒（每半秒一次） */
    SEEK_FORWARD,
    /** 潜行 + 左键：上一个频道 */
    PREV_CHANNEL,
    /** 潜行 + 右键：下一个频道 */
    NEXT_CHANNEL,
    /** 潜行 + 左键长按：音量减 */
    VOLUME_DOWN,
    /** 潜行 + 右键长按：音量加 */
    VOLUME_UP,
    /** 潜行 + 中键（选取方块键）：弹幕开关 */
    TOGGLE_DANMAKU,
    /** 潜行 + 长按中键：开机 / 关机 */
    POWER;

    private static final RemoteAction[] VALUES = values();

    @Nullable
    public static RemoteAction byId(int id)
    {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }
}
