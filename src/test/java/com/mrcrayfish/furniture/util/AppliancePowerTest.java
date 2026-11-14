package com.mrcrayfish.furniture.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppliancePowerTest
{
    @Test
    void ratesFromSpec()
    {
        assertEquals(4, AppliancePower.COMPUTER);
        assertEquals(2, AppliancePower.TV_GIF);
        assertEquals(4, AppliancePower.TV_VIDEO);
        assertEquals(8, AppliancePower.PRINTER);
        assertEquals(8, AppliancePower.TOASTER);
        assertEquals(12, AppliancePower.MICROWAVE);
        assertEquals(6, AppliancePower.WASHING_MACHINE);
        assertEquals(6, AppliancePower.DISHWASHER);
        assertEquals(1, AppliancePower.CEILING_FAN);
        assertEquals(12, AppliancePower.OVEN);
        assertEquals(1, AppliancePower.FRIDGE);
        assertEquals(8, AppliancePower.FREEZING);
    }

    @Test
    void bufferLastsTwentySecondsAtMaxRate()
    {
        assertEquals(400, AppliancePower.BUFFER_TICKS);
        assertEquals(4800, AppliancePower.bufferFor(AppliancePower.OVEN));
        assertEquals(400, AppliancePower.bufferFor(AppliancePower.CEILING_FAN));
        assertEquals(3600, AppliancePower.bufferFor(AppliancePower.FRIDGE + AppliancePower.FREEZING));
        assertEquals(400, AppliancePower.bufferFor(0));
    }

    @Test
    void receiveUpToRoom()
    {
        assertEquals(100, AppliancePower.receivable(0, 400, 100));
        assertEquals(50, AppliancePower.receivable(350, 400, 100));
        assertEquals(0, AppliancePower.receivable(400, 400, 100));
        assertEquals(0, AppliancePower.receivable(0, 400, -1));
    }

    @Test
    void wiredBufferFirstThenCardForTheShortfall()
    {
        assertEquals(0, AppliancePower.shortfall(12, 12));
        assertEquals(0, AppliancePower.shortfall(100, 12));
        assertEquals(7, AppliancePower.shortfall(5, 12));
        assertEquals(12, AppliancePower.shortfall(0, 12));
    }
}
