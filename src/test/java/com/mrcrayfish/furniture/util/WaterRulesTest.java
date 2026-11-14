package com.mrcrayfish.furniture.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaterRulesTest
{
    @Test
    void numbersFromSpec()
    {
        assertEquals(1000, WaterRules.BASIN_CAPACITY);
        assertEquals(50, WaterRules.BASIN_FILL_PER_TICK);
        assertEquals(16000, WaterRules.BATH_CAPACITY);
        assertEquals(100, WaterRules.BATH_FILL_PER_TICK);
        assertEquals(100, WaterRules.TOILET_FLUSH);
        assertEquals(1, WaterRules.SHOWER_PER_TICK);
        assertEquals(1000, WaterRules.TAP_PER_BLOCK);
    }

    @Test
    void bathLevelRoundsUpPerThousand()
    {
        assertEquals(0, WaterRules.bathLevel(0));
        assertEquals(1, WaterRules.bathLevel(1));
        assertEquals(1, WaterRules.bathLevel(1000));
        assertEquals(2, WaterRules.bathLevel(1001));
        assertEquals(16, WaterRules.bathLevel(16000));
        assertEquals(16, WaterRules.bathLevel(99999));
        assertEquals(0, WaterRules.bathLevel(-3));
    }

    @Test
    void legacyBathLevelConverts()
    {
        assertEquals(0, WaterRules.bathAmountFromLevel(0));
        assertEquals(5000, WaterRules.bathAmountFromLevel(5));
        assertEquals(16000, WaterRules.bathAmountFromLevel(16));
        assertEquals(16000, WaterRules.bathAmountFromLevel(40));
        assertEquals(0, WaterRules.bathAmountFromLevel(-1));
    }

    @Test
    void evaporatesAThirdOfCapacityPerDay()
    {
        WaterRules.Evaporation basin = new WaterRules.Evaporation(1000);
        int amount = 1000;
        for(int t = 0; t < WaterRules.DAY_TICKS; t++)
            amount -= basin.tick(amount);
        // 1000 / 3 = 333.33：一天蒸发 333 mB，余数留着
        assertEquals(667, amount);

        WaterRules.Evaporation bath = new WaterRules.Evaporation(16000);
        amount = 16000;
        for(int t = 0; t < WaterRules.DAY_TICKS * 3; t++)
            amount -= bath.tick(amount);
        assertEquals(0, amount);
    }

    @Test
    void evaporationStopsWhenEmptyAndKeepsRemainder()
    {
        WaterRules.Evaporation e = new WaterRules.Evaporation(1000);
        assertEquals(0, e.tick(0));
        assertEquals(0, e.getAccumulated());
        e.tick(500);
        assertEquals(1000, e.getAccumulated());
        e.setAccumulated(-5);
        assertEquals(0, e.getAccumulated());
        e.setAccumulated(Long.MAX_VALUE);
        assertEquals(0, e.getAccumulated());
        // 不会蒸发掉比现有更多的水
        e.setAccumulated(71999);
        assertEquals(1, e.tick(1));
    }
}
