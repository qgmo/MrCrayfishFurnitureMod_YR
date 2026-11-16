package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;

/**
 * 家具电脑的 CPU（悠然服务器魔改，服主 11-16）：主板上的一片 YR-2000（YouranMod 的 {@code api.pcb.BoardNbt}，合成时从主板带进电脑）。
 * <ul>
 * <li>实际频率：先取 YR-2000 的标准频率 2.0 GHz，被这片芯片的最大频率封顶（最大频率低于 2.0 GHz 就按最大频率）。可调的 1.6–2.6 GHz
 * 这一版不做设置界面。没有主板数据的电脑（以前放下的、创造模式拿的）按标准 2.0 GHz。芯片报废了（没有最大频率）为 0：电脑没有反应。</li>
 * <li>浏览器的处理速度 = 实际频率 ÷ 2.0 GHz（服主定），只体现在排版等待上（服主 11-16）：页面数据到了以后，排版要的时间按
 * “标准频率 ÷ 实际频率”拉长——标准频率时不加任何等待（和现在一样快），慢了多等 {@link #extraLayoutMillis}，等的时候地址栏旁写“正在排版……”。
 * 网速、滚动、打字、鼠标、画面刷新都不受影响。基准（标准频率下排一页的时间）按现在实际的排版耗时定，做成服务端配置。</li>
 * </ul>
 * 纯逻辑，测试见 {@code ComputerCpuTest}。
 */
public final class ComputerCpu
{
    /** 方块实体 NBT（也是合成出来的电脑物品的 {@code BlockEntityTag}）里放主板数据的键 */
    public static final String TAG_BOARD = BoardNbt.TAG;
    /** 标准频率（kHz）：YR-2000 的 2.0 GHz（服主定），处理速度按它换算 */
    public static final int STANDARD_KHZ = ChipModel.YR2000.ratedKHz;
    /**
     * 标准频率下排版一页的时间（基准）：固定的一段 + 页面每 KB 一段。Claude 按现在实际的排版耗时定（11-16 开发环境实测：
     * 7.7 KB 的页约 240–260 ms、17.9 KB 的约 480 ms，不算第一次打开时的冷启动），配置项 {@code computer-layout-base-ms}、
     * {@code computer-layout-per-kb-ms}；标准频率时不多等，只用来按比例算慢了多等多久。
     * 服主 11-16：按实测的量慢芯片几乎感觉不到，基准调大到 4 倍（200 ms + 100 ms/KB）
     */
    public static final int DEFAULT_LAYOUT_BASE_MS = 200;
    public static final int DEFAULT_LAYOUT_PER_KB_MS = 100;

    private ComputerCpu()
    {
    }

    /**
     * 实际频率（kHz）：没有主板数据为标准频率；有的话取这片 CPU 的标准频率（没写按 YR-2000），被最大频率封顶；没装 CPU、报废了为 0。
     */
    public static int clockKHz(@Nullable NBTTagCompound board)
    {
        if(board == null)
            return STANDARD_KHZ;
        if(!BoardNbt.hasCpu(board))
            return 0;
        int std = BoardNbt.cpuStandardKHz(board);
        return BoardNbt.cpuClockKHz(board, std > 0 ? std : STANDARD_KHZ);
    }

    /** CPU 坏了（报废、没装）：电脑通了电也没有反应 */
    public static boolean dead(@Nullable NBTTagCompound board)
    {
        return clockKHz(board) <= 0;
    }

    /** 处理速度：实际频率 ÷ 2.0 GHz */
    public static double speed(int clockKHz)
    {
        return clockKHz / (double) STANDARD_KHZ;
    }

    /** 标准频率下排版这一页的时间（毫秒） */
    public static long layoutMillis(int pageBytes, int baseMs, int perKbMs)
    {
        return Math.max(0, baseMs) + (long) Math.max(0, perKbMs) * Math.max(0, pageBytes) / 1024;
    }

    /**
     * 比现在多等多久（毫秒）：标准频率下要 T，处理速度是 s 时要 T ÷ s，多出 T × (1 ÷ s − 1)。不比标准频率慢的为 0；
     * CPU 坏了（频率 0）的不会走到这里（电脑没有反应）。
     */
    public static long extraLayoutMillis(int pageBytes, int clockKHz, int baseMs, int perKbMs)
    {
        if(clockKHz <= 0 || clockKHz >= STANDARD_KHZ)
            return 0;
        return Math.round(layoutMillis(pageBytes, baseMs, perKbMs) * (STANDARD_KHZ / (double) clockKHz - 1));
    }

    /** 主板上 CPU 的型号（显示用的翻译键后缀，如 {@code yr2000}）；没有主板数据按 YR-2000 */
    public static String modelId(@Nullable NBTTagCompound board)
    {
        if(board != null && BoardNbt.hasCpu(board))
        {
            ChipModel m = ChipModel.byId(board.getCompoundTag(BoardNbt.CPU).getString(ChipModel.TAG));
            if(m != null)
                return m.id;
        }
        return ChipModel.YR2000.id;
    }

    /** 频率给人看（同 YouranMod：1 GHz 以上写 GHz，两位小数） */
    public static String formatFreq(int kHz)
    {
        return ChipModel.formatFreq(kHz);
    }

    /** 被最大频率压到了标准频率以下 */
    public static boolean capped(@Nullable NBTTagCompound board)
    {
        int clock = clockKHz(board);
        return board != null && clock > 0 && clock < (BoardNbt.cpuStandardKHz(board) > 0 ? BoardNbt.cpuStandardKHz(board) : STANDARD_KHZ);
    }

    /** 方块实体 / 物品 NBT 里的主板数据；没有为 null */
    @Nullable
    public static NBTTagCompound board(@Nullable NBTTagCompound teTag)
    {
        return teTag != null && teTag.hasKey(TAG_BOARD, 10) ? teTag.getCompoundTag(TAG_BOARD) : null;
    }
}
