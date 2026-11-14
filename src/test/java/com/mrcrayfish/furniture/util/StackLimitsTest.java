package com.mrcrayfish.furniture.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StackLimitsTest
{
    @Test
    void clampCount()
    {
        assertEquals(0, StackLimits.clampCount(-5, 64));
        assertEquals(0, StackLimits.clampCount(0, 64));
        assertEquals(10, StackLimits.clampCount(10, 64));
        assertEquals(64, StackLimits.clampCount(127, 64));
        assertEquals(1, StackLimits.clampCount(127, 1));
        assertEquals(16, StackLimits.clampCount(64, 16));
    }
}
