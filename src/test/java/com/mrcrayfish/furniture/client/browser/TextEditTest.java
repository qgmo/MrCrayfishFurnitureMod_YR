package com.mrcrayfish.furniture.client.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextEditTest
{
    @Test
    void typingAndCursor()
    {
        TextEdit t = new TextEdit(20);
        t.insert("minebay");
        t.insert(".yr");
        assertEquals("minebay.yr", t.getText());
        t.home(false);
        t.insert("https://");
        assertEquals("https://minebay.yr", t.getText());
        assertEquals(8, t.getCursor());
        t.end(false);
        t.backspace(false);
        assertEquals("https://minebay.y", t.getText());
        t.left(true, false);
        assertEquals(16, t.getCursor(), "按词左移停在 y 前");
    }

    @Test
    void selectionCopyCutPaste()
    {
        TextEdit t = new TextEdit(100, "hello world");
        t.home(false);
        t.right(true, true);
        assertEquals("hello", t.selected());
        assertEquals("hello", t.cut());
        assertEquals(" world", t.getText());
        t.selectAll();
        t.insert("你好");
        assertEquals("你好", t.getText());
        t.left(false, true);
        assertTrue(t.hasSelection());
        t.left(false, false);
        assertFalse(t.hasSelection());
        assertNull(t.cut());
    }

    @Test
    void limitsAndCleaning()
    {
        TextEdit t = new TextEdit(5);
        t.insert("abc\ndef§g");
        assertEquals("abcde", t.getText(), "去掉换行、格式符，截到上限");
        t.insert("x");
        assertEquals("abcde", t.getText(), "满了不再插入");
        t.selectAll();
        t.insert("1234567");
        assertEquals("12345", t.getText(), "替换选中时也按上限截");
        TextEdit e = new TextEdit(3);
        e.insert("a😀b");
        assertEquals("a😀", e.getText(), "不切开代理对");
        e.end(false);
        e.backspace(false);
        assertEquals("a", e.getText(), "退格整个删掉表情");
    }

    @Test
    void wordDeletion()
    {
        TextEdit t = new TextEdit(100, "foo bar baz");
        t.backspace(true);
        assertEquals("foo bar ", t.getText());
        t.home(false);
        t.delete(true);
        assertEquals(" bar ", t.getText());
        t.moveTo(99, false);
        assertEquals(5, t.getCursor());
    }
}
