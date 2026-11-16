package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 世界里电脑屏幕上的缩略图（服主 11-16）：开着界面的人在本地把当前页面画成一张很小的图（默认 48×36、固定 16 色）上传，
 * 服务端定下一张发给附近的人，画在电脑模型的屏幕上。纯逻辑，两端都用。
 * <ul>
 * <li>取景：页面宽固定 {@value #VIEW_WIDTH} CSS 像素（同浏览器），高按缩略图的宽高比算（48×36 时 450），从同步的滚动位置往下取；
 * 与各人窗口大小无关，大家画出来才对得上。页面底下没有内容的地方当白色（同浏览器界面）。</li>
 * <li>缩小：每个缩略图像素取对应那一块的平均色，再往这一块里和平均色反差最大的那个像素拉一些（{@value #CONTRAST_WEIGHT}/20），
 * 免得白底上的小字平均下来全成了白的（深底浅字同理）；纯色的一块不受影响。然后取调色板里最近的颜色。各人渲染的倍数不同，
 * 这样算出来差别很小（整数运算，结果确定）。</li>
 * <li>编码：每像素 4 位（调色板下标），两个像素一个字节，高 4 位在前；发给附近的人时带上宽、高与调色板（{@link #encodeFrame}）。</li>
 * </ul>
 */
public final class ScreenThumb
{
    /** 页面宽（CSS 像素，同 {@code PageRenderer.VIEW_WIDTH}） */
    public static final int VIEW_WIDTH = 600;
    public static final int MAX_COLORS = 16;
    public static final int DEFAULT_WIDTH = 48, DEFAULT_HEIGHT = 36;
    public static final int MIN_WIDTH = 8, MAX_WIDTH = 128;
    public static final int MIN_HEIGHT = 6, MAX_HEIGHT = 96;
    public static final int DEFAULT_INTERVAL_MS = 1000;
    public static final int MIN_INTERVAL_MS = 250, MAX_INTERVAL_MS = 60000;
    /** 缩小时往反差最大的像素拉多少（二十分之几） */
    static final int CONTRAST_WEIGHT = 7;
    /** 页面以外（页面底下）的颜色 */
    public static final int BACKGROUND = 0xFFFFFF;

    /**
     * 默认调色板（按网页常见的颜色挑的）：黑、四级灰、白（文字缩小后是各种灰），红、橙、黄、绿、浅绿、深蓝、蓝、浅蓝、紫、棕。
     */
    public static final String[] DEFAULT_PALETTE = {
            "000000", "3C3C3C", "787878", "B4B4B4", "DCDCDC", "FFFFFF",
            "C62828", "EF8A17", "F2D33A", "2E9E44", "A8D8A0",
            "1A3A8C", "1A73E8", "A8C8F8", "7B3FA0", "7A5230"};

    private ScreenThumb() {}

    // ---- 规格 ----

    /** 缩略图的规格（服务端配置，随电脑方块发给客户端） */
    public static final class Spec
    {
        public static final Spec DEFAULT = new Spec(true, DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_INTERVAL_MS, parsePalette(DEFAULT_PALETTE, null));

        public final boolean enabled;
        public final int width;
        public final int height;
        /** 每个人最多多久传一次（毫秒） */
        public final int intervalMs;
        /** 0xRRGGBB，2–16 个 */
        private final int[] palette;

        public Spec(boolean enabled, int width, int height, int intervalMs, int[] palette)
        {
            if(width < MIN_WIDTH || width > MAX_WIDTH || height < MIN_HEIGHT || height > MAX_HEIGHT)
                throw new IllegalArgumentException("缩略图尺寸不对：" + width + "×" + height);
            if(palette.length < 2 || palette.length > MAX_COLORS)
                throw new IllegalArgumentException("调色板要 2–16 色：" + palette.length);
            this.enabled = enabled;
            this.width = width;
            this.height = height;
            this.intervalMs = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, intervalMs));
            this.palette = palette.clone();
            for(int i = 0; i < this.palette.length; i++)
                this.palette[i] &= 0xFFFFFF;
        }

        public int[] palette()
        {
            return palette.clone();
        }

        public int colors()
        {
            return palette.length;
        }

        public int pixels()
        {
            return width * height;
        }

        /** 取景的高（CSS 像素）：页面宽 × 高 / 宽，四舍五入 */
        public int viewHeight()
        {
            return (VIEW_WIDTH * height + width / 2) / width;
        }

        /** 打包后的字节数 */
        public int packedLength()
        {
            return (width * height + 1) / 2;
        }

        /** 发给客户端的写法：[开关, 宽, 高, 间隔, 颜色...] */
        public int[] toIntArray()
        {
            int[] a = new int[4 + palette.length];
            a[0] = enabled ? 1 : 0;
            a[1] = width;
            a[2] = height;
            a[3] = intervalMs;
            System.arraycopy(palette, 0, a, 4, palette.length);
            return a;
        }

        /** 不对时返回 null */
        @Nullable
        public static Spec fromIntArray(@Nullable int[] a)
        {
            if(a == null || a.length < 6 || a.length > 4 + MAX_COLORS)
                return null;
            try
            {
                return new Spec(a[0] != 0, a[1], a[2], a[3], Arrays.copyOfRange(a, 4, a.length));
            }
            catch(IllegalArgumentException e)
            {
                return null;
            }
        }

        @Override
        public boolean equals(Object o)
        {
            if(!(o instanceof Spec))
                return false;
            Spec s = (Spec) o;
            return enabled == s.enabled && width == s.width && height == s.height && intervalMs == s.intervalMs && Arrays.equals(palette, s.palette);
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(toIntArray());
        }
    }

    /**
     * 按配置建规格；写得不对的颜色、超出范围的尺寸改回默认，原因写进 {@code problems}（可为 null）。
     */
    public static Spec fromConfig(boolean enabled, int width, int height, int intervalMs, @Nullable String[] palette, @Nullable List<String> problems)
    {
        int w = width, h = height;
        if(w < MIN_WIDTH || w > MAX_WIDTH || h < MIN_HEIGHT || h > MAX_HEIGHT)
        {
            if(problems != null)
                problems.add("computer screen thumbnail size " + width + "x" + height + " out of range, using " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT);
            w = DEFAULT_WIDTH;
            h = DEFAULT_HEIGHT;
        }
        List<String> bad = new ArrayList<>();
        int[] p = palette == null ? null : parsePalette(palette, bad);
        if(p == null || !bad.isEmpty())
        {
            if(problems != null)
                problems.add("computer screen thumbnail palette is invalid (" + (bad.isEmpty() ? "needs 2-16 colours" : String.join(", ", bad)) + "), using the default palette");
            p = parsePalette(DEFAULT_PALETTE, null);
        }
        return new Spec(enabled, w, h, intervalMs, p);
    }

    /**
     * 颜色清单（{@code RRGGBB} 或 {@code #RRGGBB}）→ 0xRRGGBB；个数不在 2–16 或有写错的返回 null（写错的放进 {@code bad}）。
     */
    @Nullable
    public static int[] parsePalette(String[] hex, @Nullable List<String> bad)
    {
        if(hex.length < 2 || hex.length > MAX_COLORS)
            return null;
        int[] out = new int[hex.length];
        boolean ok = true;
        for(int i = 0; i < hex.length; i++)
        {
            String s = hex[i] == null ? "" : hex[i].trim();
            if(s.startsWith("#"))
                s = s.substring(1);
            if(s.length() != 6 || !s.matches("[0-9A-Fa-f]{6}"))
            {
                ok = false;
                if(bad != null)
                    bad.add("'" + hex[i] + "'");
                continue;
            }
            out[i] = Integer.parseInt(s.toUpperCase(Locale.ROOT), 16);
        }
        return ok ? out : null;
    }

    // ---- 取色 ----

    /** 调色板里离 rgb 最近的颜色的下标（按人眼加权的 RGB 距离 2:4:3，整数运算；一样近时取下标小的） */
    public static int nearest(int rgb, int[] palette)
    {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for(int i = 0; i < palette.length; i++)
        {
            int c = palette[i];
            int dr = r - (c >> 16 & 0xFF), dg = g - (c >> 8 & 0xFF), db = b - (c & 0xFF);
            long d = 2L * dr * dr + 4L * dg * dg + 3L * db * db;
            if(d < bestD)
            {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * 把渲染好的页面图缩成缩略图（调色板下标，一字节一个像素）。
     *
     * @param rows     页面图的一段（RGB，一行 {@code imageWidth} 个），从图上第 {@code firstRow} 行开始，{@code rowCount} 行
     * @param imageHeight 整张图的高（图像素；再往下是页面以外，当白色）
     * @param scale    图上 1 个 CSS 像素对应几个图像素
     * @param scroll   滚动位置（CSS 像素）
     */
    public static byte[] make(int[] rows, int imageWidth, int imageHeight, int firstRow, int rowCount, double scale, int scroll, Spec spec)
    {
        int w = spec.width, h = spec.height, vh = spec.viewHeight();
        int[] palette = spec.palette;
        byte[] out = new byte[w * h];
        for(int ty = 0; ty < h; ty++)
        {
            // 这一行缩略图像素覆盖的 CSS 区间 [y0, y1)，换成图像素
            int iy0 = (int) Math.floor((scroll + (double) ty * vh / h) * scale);
            int iy1 = Math.max(iy0 + 1, (int) Math.floor((scroll + (double) (ty + 1) * vh / h) * scale));
            for(int tx = 0; tx < w; tx++)
            {
                int ix0 = (int) Math.floor((double) tx * VIEW_WIDTH / w * scale);
                int ix1 = Math.max(ix0 + 1, (int) Math.floor((double) (tx + 1) * VIEW_WIDTH / w * scale));
                long sr = 0, sg = 0, sb = 0, n = 0;
                for(int iy = iy0; iy < iy1; iy++)
                {
                    for(int ix = ix0; ix < ix1; ix++)
                    {
                        int c = pixel(rows, imageWidth, imageHeight, firstRow, rowCount, ix, iy);
                        sr += c >> 16 & 0xFF;
                        sg += c >> 8 & 0xFF;
                        sb += c & 0xFF;
                        n++;
                    }
                }
                int ar = (int) ((sr + n / 2) / n), ag = (int) ((sg + n / 2) / n), ab = (int) ((sb + n / 2) / n);
                // 这一块里和平均色亮度差得最多的像素（白底上是字，深底上是浅色的字）。不看最外一圈图像素：
                // 各人渲染倍数不同，块的边上那一列可能是隔壁的颜色，只拿它算会让大家对不上
                int meanLum = luminance(ar, ag, ab);
                int far = ar << 16 | ag << 8 | ab, farD = 0;
                int fx0 = ix1 - ix0 >= 3 ? ix0 + 1 : ix0, fx1 = ix1 - ix0 >= 3 ? ix1 - 1 : ix1;
                int fy0 = iy1 - iy0 >= 3 ? iy0 + 1 : iy0, fy1 = iy1 - iy0 >= 3 ? iy1 - 1 : iy1;
                for(int iy = fy0; iy < fy1; iy++)
                {
                    for(int ix = fx0; ix < fx1; ix++)
                    {
                        int c = pixel(rows, imageWidth, imageHeight, firstRow, rowCount, ix, iy);
                        int d = Math.abs(luminance(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF) - meanLum);
                        if(d > farD)
                        {
                            farD = d;
                            far = c;
                        }
                    }
                }
                int k = CONTRAST_WEIGHT;
                int r = (ar * (20 - k) + (far >> 16 & 0xFF) * k + 10) / 20;
                int g = (ag * (20 - k) + (far >> 8 & 0xFF) * k + 10) / 20;
                int b = (ab * (20 - k) + (far & 0xFF) * k + 10) / 20;
                out[ty * w + tx] = (byte) nearest(r << 16 | g << 8 | b, palette);
            }
        }
        return out;
    }

    private static int pixel(int[] rows, int imageWidth, int imageHeight, int firstRow, int rowCount, int ix, int iy)
    {
        boolean inside = iy >= 0 && iy < imageHeight && iy >= firstRow && iy < firstRow + rowCount && ix < imageWidth;
        return inside ? rows[(iy - firstRow) * imageWidth + ix] & 0xFFFFFF : BACKGROUND;
    }

    /** 亮度（整数，2:5:1 近似） */
    private static int luminance(int r, int g, int b)
    {
        return 2 * r + 5 * g + b;
    }

    /** 页面图上要读的行：[第一行, 行数]（不超过图的高；可能为 0 行） */
    public static int[] rowsNeeded(int imageHeight, double scale, int scroll, Spec spec)
    {
        int from = Math.max(0, (int) Math.floor(scroll * scale));
        int to = Math.min(imageHeight, (int) Math.floor((scroll + spec.viewHeight()) * scale) + 1);
        return new int[]{Math.min(from, imageHeight), Math.max(0, to - from)};
    }

    // ---- 编码 ----

    /** 下标（每个 0–15）打包成每像素 4 位，高 4 位在前 */
    public static byte[] pack(byte[] indices)
    {
        byte[] out = new byte[(indices.length + 1) / 2];
        for(int i = 0; i < indices.length; i++)
        {
            int v = indices[i] & 0x0F;
            out[i >> 1] |= (byte) ((i & 1) == 0 ? v << 4 : v);
        }
        return out;
    }

    /** 解包：长度要正好、每个下标小于 {@code colors}、补齐用的最后半个字节为 0，不然返回 null */
    @Nullable
    public static byte[] unpack(@Nullable byte[] packed, int count, int colors)
    {
        if(packed == null || count < 0 || packed.length != (count + 1) / 2)
            return null;
        byte[] out = new byte[count];
        for(int i = 0; i < count; i++)
        {
            int b = packed[i >> 1] & 0xFF;
            int v = (i & 1) == 0 ? b >> 4 : b & 0x0F;
            if(v >= colors)
                return null;
            out[i] = (byte) v;
        }
        if((count & 1) == 1 && (packed[packed.length - 1] & 0x0F) != 0)
            return null;
        return out;
    }

    /** 发给附近的人的一帧：[宽 1][高 1][颜色数 1][颜色 × 3 字节 RGB][打包的像素] */
    public static byte[] encodeFrame(Spec spec, byte[] indices)
    {
        if(indices.length != spec.pixels())
            throw new IllegalArgumentException("像素数不对");
        byte[] packed = pack(indices);
        int n = spec.palette.length;
        byte[] out = new byte[3 + n * 3 + packed.length];
        out[0] = (byte) spec.width;
        out[1] = (byte) spec.height;
        out[2] = (byte) n;
        for(int i = 0; i < n; i++)
        {
            int c = spec.palette[i];
            out[3 + i * 3] = (byte) (c >> 16);
            out[4 + i * 3] = (byte) (c >> 8);
            out[5 + i * 3] = (byte) c;
        }
        System.arraycopy(packed, 0, out, 3 + n * 3, packed.length);
        return out;
    }

    /** 一帧解出来的样子：每个像素的颜色（0xFFRRGGBB，一行一行） */
    public static final class Frame
    {
        public final int width;
        public final int height;
        public final int[] argb;

        Frame(int width, int height, int[] argb)
        {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    /** 解一帧；格式不对返回 null */
    @Nullable
    public static Frame decodeFrame(@Nullable byte[] data)
    {
        if(data == null || data.length < 3)
            return null;
        int w = data[0] & 0xFF, h = data[1] & 0xFF, n = data[2] & 0xFF;
        if(w < MIN_WIDTH || w > MAX_WIDTH || h < MIN_HEIGHT || h > MAX_HEIGHT || n < 2 || n > MAX_COLORS)
            return null;
        int head = 3 + n * 3;
        if(data.length != head + (w * h + 1) / 2)
            return null;
        int[] palette = new int[n];
        for(int i = 0; i < n; i++)
            palette[i] = (data[3 + i * 3] & 0xFF) << 16 | (data[4 + i * 3] & 0xFF) << 8 | (data[5 + i * 3] & 0xFF);
        byte[] idx = unpack(Arrays.copyOfRange(data, head, data.length), w * h, n);
        if(idx == null)
            return null;
        int[] argb = new int[w * h];
        for(int i = 0; i < argb.length; i++)
            argb[i] = 0xFF000000 | palette[idx[i]];
        return new Frame(w, h, argb);
    }
}
