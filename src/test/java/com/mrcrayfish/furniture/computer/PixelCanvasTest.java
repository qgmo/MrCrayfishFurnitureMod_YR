package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.image.DyePng;
import net.minecraft.item.EnumDyeColor;
import org.junit.jupiter.api.Test;

import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/** 画板：工具、撤销、PNG、同步、尺寸 */
class PixelCanvasTest
{
    @Test
    void pencilLineAndEraser()
    {
        PixelCanvas c = new PixelCanvas(10, 5);
        assertTrue(c.isBlank());
        int v = c.version();
        c.set(2, 3, 14);
        assertEquals(14, c.get(2, 3));
        assertTrue(c.version() > v);
        c.set(-1, 0, 5);
        c.set(10, 0, 5);
        assertEquals(0, c.get(-1, 0), "画板外的不画");
        c.line(0, 0, 9, 4, 1);
        assertEquals(1, c.get(0, 0));
        assertEquals(1, c.get(9, 4));
        int n = 0;
        for(byte b : c.pixels())
            n += b == 1 ? 1 : 0;
        assertEquals(10, n, "从 (0,0) 到 (9,4) 的直线 10 个点");
        c.set(9, 4, 0);
        assertEquals(0, c.get(9, 4), "橡皮就是画透明");
        c.line(5, -100, 5, 100, 2);
        assertEquals(2, c.get(5, 0));
        assertEquals(2, c.get(5, 4));
        c.set(1, 1, 99);
        assertEquals(0, c.get(1, 1), "不认识的颜色当透明");
    }

    @Test
    void fill()
    {
        PixelCanvas c = new PixelCanvas(6, 6);
        // 一圈墙，里面填色、外面不动
        for(int i = 1; i <= 4; i++)
        {
            c.set(i, 1, 16);
            c.set(i, 4, 16);
            c.set(1, i, 16);
            c.set(4, i, 16);
        }
        c.fill(2, 2, 15);
        assertEquals(15, c.get(2, 2));
        assertEquals(15, c.get(3, 3));
        assertEquals(16, c.get(1, 1));
        assertEquals(0, c.get(0, 0));
        assertEquals(0, c.get(5, 5));
        c.fill(0, 0, 5);
        assertEquals(5, c.get(5, 0));
        assertEquals(15, c.get(2, 2), "墙挡住");
        // 大画板也不会栈溢出
        PixelCanvas big = new PixelCanvas(256, 256);
        big.fill(0, 0, 3);
        assertEquals(3, big.get(255, 255));
    }

    @Test
    void undoAndClear()
    {
        PixelCanvas c = new PixelCanvas(4, 4);
        assertFalse(c.canUndo());
        c.beginStroke();
        c.set(0, 0, 1);
        c.beginStroke();
        c.set(1, 1, 2);
        assertTrue(c.undo());
        assertEquals(0, c.get(1, 1));
        assertEquals(1, c.get(0, 0));
        c.clear();
        assertTrue(c.isBlank());
        assertTrue(c.undo(), "清空也能撤销");
        assertEquals(1, c.get(0, 0));
        assertTrue(c.undo());
        assertFalse(c.undo());
        for(int i = 0; i < PixelCanvas.MAX_UNDO + 10; i++)
            c.beginStroke();
        int undos = 0;
        while(c.undo())
            undos++;
        assertEquals(PixelCanvas.MAX_UNDO, undos);
    }

    @Test
    void pngAndInitialValue() throws Exception
    {
        PixelCanvas c = new PixelCanvas(468, 60);
        c.line(0, 0, 467, 59, 12);
        String url = c.toDataUrl();
        assertTrue(url.startsWith("data:image/png;base64,"));
        DyePng.Image img = DyePng.decode(DyePng.fromDataUrl(url), 468, 60);
        assertArrayEquals(c.pixels(), img.pixels);
        PixelCanvas back = PixelCanvas.fromValue(468, 60, url);
        assertArrayEquals(c.pixels(), back.pixels());
        assertFalse(back.isModified(), "和初始图一样就算没画过");
        back.set(0, 1, 3);
        assertTrue(back.isModified());
        assertTrue(PixelCanvas.fromValue(10, 10, url).isBlank(), "尺寸不符的初始值不要");
        assertThrows(IllegalArgumentException.class, () -> new PixelCanvas(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new PixelCanvas(300, 300));
    }

    @Test
    void syncRoundTripAndRejects()
    {
        PixelCanvas c = new PixelCanvas(125, 125);
        c.fill(0, 0, 7);
        c.line(0, 0, 124, 124, 1);
        byte[] s = c.syncBytes();
        assertArrayEquals(c.pixels(), PixelCanvas.fromSync(s, 125, 125));
        assertNull(PixelCanvas.fromSync(s, 125, 124), "尺寸对不上（解出来多了）");
        assertNull(PixelCanvas.fromSync(s, 126, 125), "解出来少了");
        assertNull(PixelCanvas.fromSync(new byte[]{1, 2, 3}, 125, 125));
        // 像素值超出 0–16
        byte[] bad = new byte[16];
        bad[3] = 40;
        assertNull(PixelCanvas.fromSync(deflate(bad), 4, 4));
        // 压缩炸弹：解出来远比尺寸大
        assertNull(PixelCanvas.fromSync(deflate(new byte[1 << 20]), 4, 4));
        assertNull(PixelCanvas.fromSync(new byte[PixelCanvas.MAX_SYNC_BYTES + 1], 4, 4));
    }

    static byte[] deflate(byte[] in)
    {
        Deflater d = new Deflater();
        d.setInput(in);
        d.finish();
        byte[] buf = new byte[in.length + 64];
        int n = d.deflate(buf);
        d.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    /** 16 种颜色就是 Minecraft 的染料色 */
    @Test
    void dyeColorsMatchMinecraft()
    {
        for(EnumDyeColor c : EnumDyeColor.values())
            assertEquals(c.getColorValue(), DyePng.DYE_RGB[c.getMetadata()], c.getName());
    }

    @Test
    void canvasSyncChecks()
    {
        PixelCanvas c = new PixelCanvas(8, 8);
        c.set(1, 1, 2);
        byte[] data = c.syncBytes();
        java.util.Map<Integer, int[]> canvases = new java.util.HashMap<>();
        canvases.put(5, new int[]{8, 8});
        PageFacts facts = PageFactsAccess.with(canvases);
        assertNull(CanvasSync.check(facts, 3, 3, 5, 8, 8, data));
        assertNotNull(CanvasSync.check(facts, 3, 2, 5, 8, 8, data), "别的页面");
        assertNotNull(CanvasSync.check(facts, 3, 3, 6, 8, 8, data), "不是画板");
        assertNotNull(CanvasSync.check(facts, 3, 3, 5, 8, 7, data), "尺寸对不上");
        assertNotNull(CanvasSync.check(facts, 3, 3, 5, 8, 8, new byte[]{9}), "数据坏的");
        // 发件箱：没画过不发；画了就发；同一版本不再发；400 毫秒最多一张
        CanvasSync.Outbox out = new CanvasSync.Outbox();
        PixelCanvas blank = new PixelCanvas(8, 8);
        assertFalse(out.due(1, blank, 0));
        assertTrue(out.due(5, c, 0));
        out.sent(5, c, 0);
        assertFalse(out.due(5, c, 1000));
        c.set(2, 2, 3);
        assertFalse(out.due(5, c, 100), "还没到时间");
        assertTrue(out.due(5, c, 500));
        // 画了又擦回去：发过的照样要发（旁观者那边要跟着回去）
        out.sent(5, c, 500);
        c.set(2, 2, 0);
        c.set(1, 1, 0);
        assertFalse(c.isModified());
        assertTrue(out.due(5, c, 1000));
    }
}
