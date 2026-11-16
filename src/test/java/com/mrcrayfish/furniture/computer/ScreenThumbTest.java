package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreenThumbTest
{
    private static final ScreenThumb.Spec SPEC = ScreenThumb.Spec.DEFAULT;
    private static final int[] PALETTE = SPEC.palette();

    @Test
    void defaults()
    {
        assertEquals(48, SPEC.width);
        assertEquals(36, SPEC.height);
        assertEquals(16, SPEC.colors());
        assertEquals(450, SPEC.viewHeight(), "600 × 36 / 48");
        assertEquals(864, SPEC.packedLength(), "48 × 36 / 2");
        assertEquals(1000, SPEC.intervalMs);
    }

    @Test
    void paletteColoursMapToThemselves()
    {
        for(int i = 0; i < PALETTE.length; i++)
            assertEquals(i, ScreenThumb.nearest(PALETTE[i], PALETTE), Integer.toHexString(PALETTE[i]));
    }

    @Test
    void nearestColour()
    {
        assertEquals(PALETTE[5], PALETTE[ScreenThumb.nearest(0xFAFAFA, PALETTE)], "近白 → 白");
        assertEquals(PALETTE[0], PALETTE[ScreenThumb.nearest(0x0A0A0A, PALETTE)], "近黑 → 黑");
        assertEquals(PALETTE[4], PALETTE[ScreenThumb.nearest(0xD0D0D0, PALETTE)], "白底上的字缩小后是浅灰");
        assertEquals(PALETTE[12], PALETTE[ScreenThumb.nearest(0x1E6FE0, PALETTE)], "链接蓝 → 蓝");
        assertEquals(PALETTE[6], PALETTE[ScreenThumb.nearest(0xD03030, PALETTE)], "红");
        // 一样近取下标小的
        assertEquals(0, ScreenThumb.nearest(0x808080, new int[]{0x707070, 0x909090}));
    }

    @Test
    void packUnpackRoundTrip()
    {
        byte[] idx = new byte[SPEC.pixels()];
        for(int i = 0; i < idx.length; i++)
            idx[i] = (byte) (i * 7 % 16);
        byte[] packed = ScreenThumb.pack(idx);
        assertEquals(SPEC.packedLength(), packed.length);
        assertEquals((0 << 4) | 7, packed[0] & 0xFF, "高 4 位在前");
        assertArrayEquals(idx, ScreenThumb.unpack(packed, idx.length, 16));
        // 奇数个
        byte[] odd = {1, 2, 3};
        byte[] p = ScreenThumb.pack(odd);
        assertEquals(2, p.length);
        assertArrayEquals(odd, ScreenThumb.unpack(p, 3, 16));
    }

    @Test
    void unpackRejectsMalformed()
    {
        byte[] packed = ScreenThumb.pack(new byte[SPEC.pixels()]);
        assertNull(ScreenThumb.unpack(new byte[packed.length - 1], SPEC.pixels(), 16), "短了");
        assertNull(ScreenThumb.unpack(new byte[packed.length + 1], SPEC.pixels(), 16), "长了");
        assertNull(ScreenThumb.unpack(null, SPEC.pixels(), 16));
        assertNull(ScreenThumb.unpack(new byte[]{(byte) 0x0C}, 2, 12), "下标超出调色板");
        assertNotNull(ScreenThumb.unpack(new byte[]{(byte) 0x0B}, 2, 12));
        assertNull(ScreenThumb.unpack(new byte[]{0x10, 0x01}, 3, 16), "补齐的半个字节不是 0");
    }

    @Test
    void frameRoundTrip()
    {
        byte[] idx = new byte[SPEC.pixels()];
        idx[0] = 12;
        idx[idx.length - 1] = 5;
        byte[] frame = ScreenThumb.encodeFrame(SPEC, idx);
        assertEquals(3 + 16 * 3 + 864, frame.length);
        ScreenThumb.Frame f = ScreenThumb.decodeFrame(frame);
        assertNotNull(f);
        assertEquals(48, f.width);
        assertEquals(36, f.height);
        assertEquals(0xFF000000 | PALETTE[12], f.argb[0]);
        assertEquals(0xFF000000 | PALETTE[5], f.argb[f.argb.length - 1]);
        assertEquals(0xFF000000 | PALETTE[0], f.argb[1]);
        // 坏的
        assertNull(ScreenThumb.decodeFrame(null));
        assertNull(ScreenThumb.decodeFrame(new byte[0]));
        assertNull(ScreenThumb.decodeFrame(java.util.Arrays.copyOf(frame, frame.length - 1)));
        byte[] bad = frame.clone();
        bad[2] = 17;
        assertNull(ScreenThumb.decodeFrame(bad), "颜色太多");
        bad = frame.clone();
        bad[0] = 2;
        assertNull(ScreenThumb.decodeFrame(bad), "宽太小");
    }

    @Test
    void specSyncRoundTripAndConfig()
    {
        assertEquals(SPEC, ScreenThumb.Spec.fromIntArray(SPEC.toIntArray()));
        assertNull(ScreenThumb.Spec.fromIntArray(new int[]{1, 2, 36, 1000, 0, 0xFFFFFF}), "宽太小");
        assertNull(ScreenThumb.Spec.fromIntArray(new int[0]));
        List<String> problems = new ArrayList<>();
        ScreenThumb.Spec s = ScreenThumb.fromConfig(true, 64, 48, 500, new String[]{"#000000", "ffffff", "FF0000"}, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(3, s.colors());
        assertEquals(0xFF0000, s.palette()[2]);
        assertEquals(450, s.viewHeight());
        s = ScreenThumb.fromConfig(true, 2000, 36, 1000, new String[]{"000000", "nope"}, problems);
        assertEquals(2, problems.size(), "尺寸、颜色各一条");
        assertEquals(ScreenThumb.Spec.DEFAULT.width, s.width);
        assertEquals(16, s.colors(), "写错的调色板改回默认");
        assertNull(ScreenThumb.parsePalette(new String[]{"000000"}, null), "至少 2 色");
    }

    /** 一张假的页面图：上半黑、下半白；左边 1/4 是蓝 */
    private static int[] image(double scale, int heightCss, int[] size)
    {
        int w = (int) Math.ceil(ScreenThumb.VIEW_WIDTH * scale), h = (int) Math.ceil(heightCss * scale);
        int[] px = new int[w * h];
        for(int y = 0; y < h; y++)
        {
            for(int x = 0; x < w; x++)
            {
                double cx = x / scale, cy = y / scale;
                px[y * w + x] = cx < 150 ? 0x1A73E8 : cy < 225 ? 0x000000 : 0xFFFFFF;
            }
        }
        size[0] = w;
        size[1] = h;
        return px;
    }

    private static byte[] make(double scale, int heightCss, int scroll)
    {
        int[] size = new int[2];
        int[] px = image(scale, heightCss, size);
        int[] rows = ScreenThumb.rowsNeeded(size[1], scale, scroll, SPEC);
        int[] slice = java.util.Arrays.copyOfRange(px, rows[0] * size[0], (rows[0] + rows[1]) * size[0]);
        return ScreenThumb.make(slice, size[0], size[1], rows[0], rows[1], scale, scroll, SPEC);
    }

    @Test
    void downsampleQuantises()
    {
        byte[] t = make(1.0, 450, 0);
        assertEquals(SPEC.pixels(), t.length);
        assertEquals(12, t[0], "左上是蓝");
        assertEquals(0, t[10 * 48 + 30], "上半黑");
        assertEquals(5, t[30 * 48 + 30], "下半白");
        assertEquals(12, t[30 * 48 + 5]);
    }

    @Test
    void smallTextStaysVisible()
    {
        // 白底上一行 2 CSS 像素粗的深色横线（像一行小字）：平均下来很浅，拉向反差最大的像素后是灰的，不是白的
        for(double scale : new double[]{1.0, 2.0, 2.5})
        {
            int w = (int) Math.ceil(600 * scale), h = (int) Math.ceil(450 * scale);
            int[] px = new int[w * h];
            for(int y = 0; y < h; y++)
            {
                for(int x = 0; x < w; x++)
                {
                    double cy = y / scale;
                    px[y * w + x] = cy >= 30 && cy < 32 ? 0x202124 : 0xFFFFFF;
                }
            }
            byte[] t = ScreenThumb.make(px, w, h, 0, h, scale, 0, SPEC);
            int row = 30 * 36 / 450;
            assertNotEquals(5, t[row * 48 + 10], "倍数 " + scale + "：小字那一行不是白的");
            assertEquals(5, t[(row + 3) * 48 + 10], "倍数 " + scale + "：空白处还是白的");
            // 深底浅字反过来
            for(int i = 0; i < px.length; i++)
                px[i] = px[i] == 0xFFFFFF ? 0x000000 : 0xFFFFFF;
            t = ScreenThumb.make(px, w, h, 0, h, scale, 0, SPEC);
            assertNotEquals(0, t[row * 48 + 10], "倍数 " + scale + "：深底上的浅字不是黑的");
        }
    }

    @Test
    void sameAtDifferentScales()
    {
        // 各人界面大小不同、渲染倍数不同，按固定取景画出来要一样
        byte[] a = make(1.0, 900, 0);
        assertArrayEquals(a, make(2.0, 900, 0));
        assertArrayEquals(a, make(2.5, 900, 0));
        assertArrayEquals(make(1.0, 900, 100), make(1.75, 900, 100));
    }

    @Test
    void belowThePageIsWhite()
    {
        // 页面只有 300 CSS 像素高，滚到 200：取景的大半在页面以外
        byte[] t = make(2.0, 300, 200);
        assertEquals(0, t[0 * 48 + 30], "第一行还在黑色那半里（200–225）");
        assertEquals(5, t[35 * 48 + 30], "页面以外是白的");
        assertEquals(5, t[35 * 48 + 2], "页面以外连蓝条也没有");
    }
}
