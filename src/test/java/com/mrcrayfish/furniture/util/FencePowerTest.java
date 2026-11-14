package com.mrcrayfish.furniture.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FencePowerTest
{
    @Test
    void numbersFromSpec()
    {
        assertEquals(20000, FencePower.DEFAULT_CAPACITY);
        assertEquals(256, FencePower.TRANSFER_PER_FACE);
        assertEquals(50, FencePower.ZAP_COST);
        // 2 万 FE 能电 400 次
        assertEquals(400, FencePower.DEFAULT_CAPACITY / FencePower.ZAP_COST);
    }

    @Test
    void sanitizeOldOrBadData()
    {
        assertEquals(20000, FencePower.sanitizeCapacity(0));
        assertEquals(20000, FencePower.sanitizeCapacity(-3));
        assertEquals(60000, FencePower.sanitizeCapacity(60000));
        assertEquals(0, FencePower.sanitizeEnergy(-1, 20000));
        assertEquals(20000, FencePower.sanitizeEnergy(99999, 20000));
        assertEquals(123, FencePower.sanitizeEnergy(123, 20000));
    }

    @Test
    void equalizeSameCapacityMovesHalfTheDifferenceCapped()
    {
        assertEquals(256, FencePower.equalizeTransfer(20000, 20000, 0, 20000, 256));
        assertEquals(50, FencePower.equalizeTransfer(200, 20000, 100, 20000, 256));
        // 差 1 不送（向下取整，免得来回）
        assertEquals(0, FencePower.equalizeTransfer(101, 20000, 100, 20000, 256));
        // 少的那边不往多的那边送
        assertEquals(0, FencePower.equalizeTransfer(100, 20000, 200, 20000, 256));
        assertEquals(0, FencePower.equalizeTransfer(100, 20000, 100, 20000, 256));
    }

    @Test
    void equalizeByFillRatio()
    {
        // 6 万的满、2 万的空：拉平到同为 3/4 满时 A 送出 15000
        assertEquals(15000, FencePower.equalizeTransfer(60000, 60000, 0, 20000, Integer.MAX_VALUE));
        // 2 万的半满（10000）、6 万的空：拉平后两边都是 1/8，A 送出 7500
        assertEquals(7500, FencePower.equalizeTransfer(10000, 20000, 0, 60000, Integer.MAX_VALUE));
        // 比例相同不送
        assertEquals(0, FencePower.equalizeTransfer(10000, 20000, 30000, 60000, 256));
    }

    @Test
    void equalizeConvergesWithoutOscillating()
    {
        int a = 20000, b = 0, capA = 20000, capB = 60000;
        for(int tick = 0; tick < 1000; tick++)
        {
            int ab = FencePower.equalizeTransfer(a, capA, b, capB, 256);
            int ba = FencePower.equalizeTransfer(b, capB, a, capA, 256);
            assertTrue(ab == 0 || ba == 0);
            a = a - ab + ba;
            b = b + ab - ba;
            assertEquals(20000, a + b);
        }
        assertEquals(5000, a);
        assertEquals(15000, b);
    }

    @Test
    void receivableLimitedByFaceAndRoom()
    {
        assertEquals(256, FencePower.receivable(0, 20000, 1000));
        assertEquals(10, FencePower.receivable(19990, 20000, 1000));
        assertEquals(0, FencePower.receivable(20000, 20000, 1000));
        assertEquals(0, FencePower.receivable(0, 20000, -5));
    }
}
