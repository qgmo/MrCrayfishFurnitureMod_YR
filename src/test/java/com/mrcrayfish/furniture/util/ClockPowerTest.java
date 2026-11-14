package com.mrcrayfish.furniture.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClockPowerTest
{
    @Test
    void defaultIntervalIsTwoMinutes()
    {
        assertEquals(2400, ClockPower.DEFAULT_INTERVAL);
        assertEquals(1, ClockPower.FE_PER_DRAIN);
    }

    @Test
    void parseIntervalFallsBackToDefault()
    {
        assertEquals(2400, ClockPower.parseInterval(null));
        assertEquals(2400, ClockPower.parseInterval(""));
        assertEquals(2400, ClockPower.parseInterval("abc"));
        assertEquals(2400, ClockPower.parseInterval("0"));
        assertEquals(2400, ClockPower.parseInterval("-5"));
        assertEquals(40, ClockPower.parseInterval(" 40 "));
    }

    @Test
    void oneEuIsTwoFe()
    {
        assertEquals(0.5, ClockPower.feToEu(1), 0);
        assertEquals(10000.0, ClockPower.feToEu(20000), 0);
        assertEquals(2, ClockPower.euToFe(1));
        assertEquals(1, ClockPower.euToFe(0.5));
        assertEquals(0, ClockPower.euToFe(0.4));
        assertEquals(20000, ClockPower.euToFe(10000));
        // 浮点误差
        assertEquals(1, ClockPower.euToFe(0.5 - 1e-9));
    }

    @Test
    void euToFeHandlesOddInput()
    {
        assertEquals(0, ClockPower.euToFe(-1));
        assertEquals(0, ClockPower.euToFe(Double.NaN));
        assertEquals(Integer.MAX_VALUE, ClockPower.euToFe(Double.POSITIVE_INFINITY));
    }

    @Test
    void timerFiresOnceEveryInterval()
    {
        ClockPower.DrainTimer timer = new ClockPower.DrainTimer(2400);
        int fired = 0;
        for(int i = 0; i < 2400 * 3; i++)
        {
            if(timer.tick())
            {
                fired++;
                assertEquals(0, (i + 1) % 2400);
            }
        }
        assertEquals(3, fired);
    }

    @Test
    void oneFePerTwoMinutesMakesTwentyThousandFeLastAboutTwentyEightDays()
    {
        long ticks = 20000L * ClockPower.DEFAULT_INTERVAL;
        double days = ticks / 20.0 / 3600 / 24;
        assertEquals(27.8, days, 0.1);
    }

    @Test
    void timerResetAndRestore()
    {
        ClockPower.DrainTimer timer = new ClockPower.DrainTimer(10);
        for(int i = 0; i < 4; i++)
            timer.tick();
        assertEquals(6, timer.getRemaining());
        timer.reset();
        assertEquals(10, timer.getRemaining());
        timer.setRemaining(3);
        assertEquals(3, timer.getRemaining());
        timer.setRemaining(0);
        assertEquals(10, timer.getRemaining());
        timer.setRemaining(99);
        assertEquals(10, timer.getRemaining());
        timer.setRemaining(-4);
        assertEquals(10, timer.getRemaining());
    }

    @Test
    void timerOfIntervalOneFiresEveryTick()
    {
        ClockPower.DrainTimer timer = new ClockPower.DrainTimer(0);
        assertTrue(timer.tick());
        assertTrue(timer.tick());
    }
}
