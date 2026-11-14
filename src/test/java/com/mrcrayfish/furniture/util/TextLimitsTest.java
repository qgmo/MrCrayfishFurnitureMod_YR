package com.mrcrayfish.furniture.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextLimitsTest
{
    private static String repeat(char c, int n)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    void urlLength()
    {
        assertTrue(TextLimits.urlLengthOk(null));
        assertTrue(TextLimits.urlLengthOk(""));
        assertTrue(TextLimits.urlLengthOk(repeat('a', 256)));
        assertFalse(TextLimits.urlLengthOk(repeat('a', 257)));
        assertFalse(TextLimits.urlLengthOk(repeat('a', 32000)));
    }

    @Test
    void truncateKeepsShortText()
    {
        assertNull(TextLimits.truncate(null, 100));
        assertEquals("欢迎", TextLimits.truncate("欢迎", 100));
        assertEquals(repeat('x', 100), TextLimits.truncate(repeat('x', 100), 100));
    }

    @Test
    void truncateCutsToLimit()
    {
        assertEquals(repeat('x', 100), TextLimits.truncate(repeat('x', 30000), 100));
    }

    @Test
    void truncateDoesNotSplitSurrogatePair()
    {
        String text = repeat('x', 99) + "😀" + "tail"; // 第 100、101 个 UTF-16 字符是一个表情
        String cut = TextLimits.truncate(text, 100);
        assertEquals(repeat('x', 99), cut);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }
}
