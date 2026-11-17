package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.chip.ChipNumbers;
import moe.qingu.youran.api.chip.CpuChip;
import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.item.CreativeStandards;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 家具电脑的 CPU：旋钮、温度、过热断电、寿命、排版时的运行错误（服主 11-17） */
class CpuRuntimeTest
{
    static final ChipNumbers N = ChipNumbers.DEFAULTS;

    static NBTTagCompound board(int maxKHz, long lifetime)
    {
        NBTTagCompound chip = CreativeStandards.chip(ChipModel.YR2000);
        chip.setInteger(ChipNbt.MAX_FREQ, maxKHz);
        chip.setLong(ChipNbt.LIFETIME, lifetime);
        NBTTagCompound b = BoardNbt.empty(BoardType.FURNITURE);
        b.setTag(BoardNbt.CPU, chip);
        return b;
    }

    @Test
    void knobAndStatus()
    {
        CpuRuntime r = new CpuRuntime();
        CpuChip chip = CpuChip.of(board(1_500_000, 3_600_000), ChipModel.YR2000, N);
        NBTTagCompound st = r.status(N, chip, N.ambientK(0.8f));
        assertEquals(2_000_000, st.getInteger("Set"));
        assertEquals(1_500_000, st.getInteger("Act"));
        assertTrue(st.getBoolean("Capped"));
        // 最大频率低 25%：每秒一次错
        assertEquals(1.0, st.getDouble("Rate"), 1e-9);
        assertEquals(11, st.getInteger("Cnt"));
        assertEquals(4, st.getInteger("Idx"));
        for(int i = 0; i < 6; i++)
            r.turn(N, chip, -1);
        assertEquals(1_600_000, r.clockKHz);
        st = r.status(N, chip, N.ambientK(0.8f));
        assertEquals(1_500_000, st.getInteger("Act"));
        assertFalse(r.turn(N, chip, -1), "到头不动");
        assertEquals(1000, st.getLong("Life"));
    }

    @Test
    void overheatTripsAndLifeRunsOut()
    {
        NBTTagCompound b = board(2_600_000, 3_600_000);
        CpuChip chip = CpuChip.of(b, ChipModel.YR2000, N);
        CpuRuntime r = new CpuRuntime();
        double desert = N.ambientK(2.0f);
        Random never = new Random()
        {
            @Override
            public double nextDouble()
            {
                return 0.99;
            }
        };
        CpuRuntime.Event ev = CpuRuntime.Event.NONE;
        int s = 0;
        for(; s < 1200 && ev == CpuRuntime.Event.NONE; s++)
            ev = r.second(N, chip, b, desert, true, 2_600_000, 1, never);
        assertEquals(CpuRuntime.Event.OVERHEAT, ev, "沙漠里超到 2.6 GHz 满载：过热断电");
        assertEquals(CpuRuntime.TRIP_OVERHEAT, r.trip);
        assertTrue(b.getCompoundTag(BoardNbt.CPU).getLong("Used") > 3_600_000L * 5 / 100);
        // 寿命用完：开着机的那一秒
        NBTTagCompound b2 = board(2_000_000, 3);
        CpuChip c2 = CpuChip.of(b2, ChipModel.YR2000, N);
        CpuRuntime r2 = new CpuRuntime();
        double plains = N.ambientK(0.8f);
        assertEquals(CpuRuntime.Event.NONE, r2.second(N, c2, b2, plains, true, 2_000_000, 0, never));
        assertEquals(CpuRuntime.Event.NONE, r2.second(N, c2, b2, plains, true, 2_000_000, 0, never));
        assertEquals(CpuRuntime.Event.WORN_OUT, r2.second(N, c2, b2, plains, true, 2_000_000, 0, never));
        assertTrue(CpuChip.of(b2, ChipModel.YR2000, N).dead);
        // 关着机不记寿命
        NBTTagCompound b3 = board(2_000_000, 3);
        CpuRuntime r3 = new CpuRuntime();
        for(int i = 0; i < 10; i++)
            r3.second(N, CpuChip.of(b3, ChipModel.YR2000, N), b3, plains, false, 2_000_000, 0, never);
        assertFalse(CpuChip.of(b3, ChipModel.YR2000, N).dead);
    }

    @Test
    void layoutFaults()
    {
        Random r = new Random(1);
        int errors = 0, crashes = 0;
        for(int i = 0; i < 10000; i++)
        {
            int f = CpuRuntime.layoutFault(1.0, 0.3, 1000, r);
            if(f == 1) errors++;
            if(f == 2) crashes++;
        }
        // 1 秒排版、每秒一次：约 63% 出错，其中三成死机
        assertEquals(0.632, (errors + crashes) / 10000.0, 0.02);
        assertEquals(0.3, crashes / (double) (errors + crashes), 0.03);
        assertEquals(0, CpuRuntime.layoutFault(0, 0.3, 1000, r));
    }

    @Test
    void savesAndLoads()
    {
        CpuRuntime r = new CpuRuntime();
        assertTrue(r.isEmpty());
        r.clockKHz = 2_300_000;
        r.thermal.reset(320);
        r.time = 99;
        r.trip = CpuRuntime.TRIP_BURNT;
        r.crashed = true;
        CpuRuntime back = new CpuRuntime();
        back.read(r.write());
        assertEquals(2_300_000, back.clockKHz);
        assertEquals(320, back.thermal.tempK(), 1e-9);
        assertEquals(99, back.time);
        assertEquals(CpuRuntime.TRIP_BURNT, back.trip);
        assertTrue(back.crashed);
        // 操作者报的负载过 3 秒就不算
        back.loadPermille = 800;
        back.loadAt = 1000;
        assertEquals(0.8, back.load(1040), 1e-9);
        assertEquals(0, back.load(1100), 1e-9);
    }
}
