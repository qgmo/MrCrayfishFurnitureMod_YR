package com.mrcrayfish.furniture.util;

import com.mrcrayfish.furniture.util.PowerCellCost.Cell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PowerCellCostTest
{
    // RFTools 7.73 默认配置（整合包里也是默认）
    private static final double COST = 1.10, CAP = 10000, MIN = 100;

    @Test
    void connectedCellsFormOneBlob()
    {
        List<Cell> cells = Arrays.asList(new Cell(0, 64, 0, 0), new Cell(1, 64, 0, 0), new Cell(1, 65, 0, 0), new Cell(5, 64, 0, 0), new Cell(1, 65, 0, -1));
        List<List<Cell>> blobs = PowerCellCost.blobs(cells);
        assertEquals(3, blobs.size());
        int biggest = 0;
        for(List<Cell> b : blobs)
            biggest = Math.max(biggest, b.size());
        assertEquals(3, biggest);
    }

    @Test
    void emptyNetworkCostsNothingExtra()
    {
        assertEquals(1.0F, PowerCellCost.factor(Collections.emptyList(), new Cell(0, 0, 0, 0), COST, CAP, MIN));
    }

    @Test
    void nearbyCellCountsAsMinimumDistance()
    {
        // 一格能量单元就在旁边：距离按 100 算，factor = (1 + 1 + 100/10000 × 0.1 × 1/2) / 2
        float f = PowerCellCost.factor(Collections.singletonList(new Cell(1, 64, 0, 0)), new Cell(0, 64, 0, 0), COST, CAP, MIN);
        assertEquals((1 + 1 + 0.01 * 0.1 * 0.5) / 2, f, 1e-6);
    }

    @Test
    void farOrOtherDimensionIsCapped()
    {
        Cell appliance = new Cell(0, 64, 0, 0);
        float far = PowerCellCost.factor(Collections.singletonList(new Cell(50000, 64, 0, 0)), appliance, COST, CAP, MIN);
        float nether = PowerCellCost.factor(Collections.singletonList(new Cell(0, 64, 0, -1)), appliance, COST, CAP, MIN);
        assertEquals((1 + 1 + 0.1 * 0.5) / 2, far, 1e-6);
        assertEquals(far, nether, 1e-9);
        // 越远越贵，但不超过 RFTools 的上限 1.1
        float mid = PowerCellCost.factor(Collections.singletonList(new Cell(5000, 64, 0, 0)), appliance, COST, CAP, MIN);
        assertTrue(mid > 1.0F && mid < far && far <= 1.1F);
    }

    @Test
    void bigFarBlobApproachesTheCap()
    {
        List<Cell> cells = new ArrayList<>();
        for(int i = 0; i < 99; i++)
            cells.add(new Cell(20000 + i, 64, 0, 0));
        float f = PowerCellCost.factor(cells, new Cell(0, 64, 0, 0), COST, CAP, MIN);
        // 一团 99 格，总 100 格（含电器）：(1 + 1 + 0.1 × 0.99) / 2
        assertEquals((2 + 0.1 * 0.99) / 2, f, 1e-6);
    }

    @Test
    void availableFollowsRfTools()
    {
        assertEquals(909, PowerCellCost.available(1000, 1.1F));
        assertEquals(1000, PowerCellCost.available(1000, 1.0F));
        assertEquals(0, PowerCellCost.available(0, 1.05F));
        assertEquals(0, PowerCellCost.available(-5, 1.05F));
    }
}
