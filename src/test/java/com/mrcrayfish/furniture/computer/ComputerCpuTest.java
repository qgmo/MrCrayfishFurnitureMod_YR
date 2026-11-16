package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 家具电脑的 CPU（服主 11-16）：实际频率 = 标准 2.0 GHz 被最大频率封顶；处理速度 = 实际频率 ÷ 2.0 GHz，只体现在排版的等待上 */
class ComputerCpuTest
{
    static NBTTagCompound board(int maxFreq, boolean scrapped)
    {
        NBTTagCompound c = new NBTTagCompound();
        c.setString(ChipModel.TAG, ChipModel.YR2000.id);
        c.setBoolean("Scrapped", scrapped);
        if(!scrapped)
            c.setInteger("MaxFreq", maxFreq);
        c.setInteger("StdFreq", ChipModel.YR2000.ratedKHz);
        return BoardNbt.install(BoardNbt.empty(BoardType.FURNITURE), Collections.singletonList(new BoardNbt.Part(ChipModel.YR2000, c)));
    }

    @Test
    void clockIsStandardCappedByMaxFrequency()
    {
        assertEquals(2_000_000, ComputerCpu.STANDARD_KHZ);
        assertEquals(2_000_000, ComputerCpu.clockKHz(null), "没有主板数据（以前放下的、创造模式拿的）按标准");
        assertEquals(2_000_000, ComputerCpu.clockKHz(board(2_450_000, false)), "最大频率比标准高：按标准，不超频");
        assertEquals(1_620_000, ComputerCpu.clockKHz(board(1_620_000, false)), "最大频率比标准低：按最大频率");
        assertTrue(ComputerCpu.capped(board(1_620_000, false)));
        assertFalse(ComputerCpu.capped(board(2_450_000, false)));
        assertFalse(ComputerCpu.capped(null));
        assertEquals(0, ComputerCpu.clockKHz(board(0, true)), "报废");
        assertTrue(ComputerCpu.dead(board(0, true)));
        assertTrue(ComputerCpu.dead(BoardNbt.empty(BoardType.FURNITURE)), "有主板数据但没装 CPU");
        assertFalse(ComputerCpu.dead(null));
        assertEquals("yr2000", ComputerCpu.modelId(null));
        assertEquals("yr2000", ComputerCpu.modelId(board(1_900_000, false)));
    }

    @Test
    void speedIsClockOverStandard()
    {
        assertEquals(1.0, ComputerCpu.speed(2_000_000), 1e-9);
        assertEquals(0.8, ComputerCpu.speed(1_600_000), 1e-9);
        assertEquals(0.5, ComputerCpu.speed(1_000_000), 1e-9);
    }

    @Test
    void layoutWaitScalesWithStandardOverActual()
    {
        int base = 200, perKb = 10;
        // 10 KB 的页：标准频率下排版 300 ms
        assertEquals(300, ComputerCpu.layoutMillis(10 * 1024, base, perKb));
        assertEquals(0, ComputerCpu.extraLayoutMillis(10 * 1024, 2_000_000, base, perKb), "标准频率不多等");
        assertEquals(0, ComputerCpu.extraLayoutMillis(10 * 1024, 2_450_000, base, perKb), "（封顶后不会比标准快；万一传进来也不缩短）");
        assertEquals(75, ComputerCpu.extraLayoutMillis(10 * 1024, 1_600_000, base, perKb), "1.6 GHz：慢四分之一");
        assertEquals(300, ComputerCpu.extraLayoutMillis(10 * 1024, 1_000_000, base, perKb), "1.0 GHz：等两倍");
        assertEquals(0, ComputerCpu.extraLayoutMillis(10 * 1024, 0, base, perKb), "坏了的不走这里");
        // 越大的页多等越久
        assertTrue(ComputerCpu.extraLayoutMillis(60 * 1024, 1_600_000, base, perKb) > ComputerCpu.extraLayoutMillis(1024, 1_600_000, base, perKb));
        // 配置成 0：不等
        assertEquals(0, ComputerCpu.extraLayoutMillis(60 * 1024, 1_000_000, 0, 0));
    }
}
