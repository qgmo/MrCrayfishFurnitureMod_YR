package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.image.DyePng;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 画板表单控件（{@code <input type="x-yr-canvas">}，悠然服务器的约定，服主 11-15）里的一张像素画。纯逻辑，两端都用。
 * <p>
 * 像素值：0 透明，1–16 是 Minecraft 16 种染料色（{@link DyePng#DYE_RGB}）。工具：铅笔、橡皮（画成透明）、油漆桶（四连通填充）、直线、取色；
 * 每一笔前存一份撤销（最多 {@value #MAX_UNDO} 步）。提交时写成 PNG（{@link DyePng#encode}），按 {@code data:image/png;base64,…} 放进表单字段。
 * 同步给旁观者时整张压缩发（{@link #syncBytes}）。
 */
public final class PixelCanvas
{
    public static final int DEFAULT_SIZE = 64;
    public static final int MAX_UNDO = 32;
    /** 同步给旁观者的压缩数据最多多少字节 */
    public static final int MAX_SYNC_BYTES = 48 * 1024;

    public final int width;
    public final int height;
    private final byte[] pixels;
    /** 页面给的初始图（没有为全透明）；“改没改过”和它比 */
    private final byte[] initial;
    private final Deque<byte[]> undo = new ArrayDeque<>();
    /** 每改一次加一（界面据此重做贴图，同步据此判断要不要发） */
    private int version;

    public PixelCanvas(int width, int height)
    {
        this(width, height, null);
    }

    /** @param initial 初始像素（长度要是宽 × 高，值 0–16）；null 为全透明 */
    public PixelCanvas(int width, int height, @Nullable byte[] initial)
    {
        if(!DyePng.validSize(width, height))
            throw new IllegalArgumentException("画板尺寸不对：" + width + "×" + height);
        this.width = width;
        this.height = height;
        this.pixels = new byte[width * height];
        if(initial != null && initial.length == pixels.length)
        {
            for(int i = 0; i < pixels.length; i++)
                pixels[i] = clampPixel(initial[i]);
        }
        this.initial = pixels.clone();
    }

    /** 按页面上写的初始值（{@code value}，data URL）建；写法不对、尺寸不符、颜色不对时当作没给 */
    public static PixelCanvas fromValue(int width, int height, @Nullable String value)
    {
        byte[] init = null;
        byte[] png = DyePng.fromDataUrl(value);
        if(png != null)
        {
            try
            {
                init = DyePng.decode(png, width, height).pixels;
            }
            catch(DyePng.BadImage ignored)
            {
                // 当作空白
            }
        }
        return new PixelCanvas(width, height, init);
    }

    private static byte clampPixel(int v)
    {
        return (byte) (v < 0 || v > DyePng.DYE_RGB.length ? 0 : v);
    }

    public int version()
    {
        return version;
    }

    public boolean inside(int x, int y)
    {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public int get(int x, int y)
    {
        return inside(x, y) ? pixels[y * width + x] : 0;
    }

    /** 像素的拷贝 */
    public byte[] pixels()
    {
        return pixels.clone();
    }

    /** 和页面给的初始图不一样（画过） */
    public boolean isModified()
    {
        return !Arrays.equals(pixels, initial);
    }

    public boolean isBlank()
    {
        for(byte b : pixels)
        {
            if(b != 0)
                return false;
        }
        return true;
    }

    public boolean canUndo()
    {
        return !undo.isEmpty();
    }

    /** 开始一笔：存一份撤销 */
    public void beginStroke()
    {
        undo.push(pixels.clone());
        while(undo.size() > MAX_UNDO)
            undo.removeLast();
    }

    public boolean undo()
    {
        byte[] b = undo.poll();
        if(b == null)
            return false;
        System.arraycopy(b, 0, pixels, 0, pixels.length);
        version++;
        return true;
    }

    /** 画一个点（橡皮就是 color = 0） */
    public void set(int x, int y, int color)
    {
        if(!inside(x, y))
            return;
        byte c = clampPixel(color);
        int i = y * width + x;
        if(pixels[i] != c)
        {
            pixels[i] = c;
            version++;
        }
    }

    /** 直线（Bresenham，含两端；在画板外的部分不画） */
    public void line(int x0, int y0, int x1, int y1, int color)
    {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        // 再长也不会超过两边之和那么多步
        for(int steps = 0; steps <= 4 * (width + height) + Math.abs(x1 - x0) + Math.abs(y1 - y0); steps++)
        {
            set(x, y, color);
            if(x == x1 && y == y1)
                break;
            int e2 = 2 * err;
            if(e2 >= dy)
            {
                err += dy;
                x += sx;
            }
            if(e2 <= dx)
            {
                err += dx;
                y += sy;
            }
        }
    }

    /** 油漆桶：从 (x, y) 起，把和它同色、四连通的一片换成 {@code color} */
    public void fill(int x, int y, int color)
    {
        if(!inside(x, y))
            return;
        byte target = pixels[y * width + x];
        byte c = clampPixel(color);
        if(target == c)
            return;
        int[] stack = new int[width * height];
        int sp = 0;
        stack[sp++] = y * width + x;
        pixels[y * width + x] = c;
        while(sp > 0)
        {
            int i = stack[--sp];
            int px = i % width, py = i / width;
            if(px > 0 && pixels[i - 1] == target)
            {
                pixels[i - 1] = c;
                stack[sp++] = i - 1;
            }
            if(px < width - 1 && pixels[i + 1] == target)
            {
                pixels[i + 1] = c;
                stack[sp++] = i + 1;
            }
            if(py > 0 && pixels[i - width] == target)
            {
                pixels[i - width] = c;
                stack[sp++] = i - width;
            }
            if(py < height - 1 && pixels[i + width] == target)
            {
                pixels[i + width] = c;
                stack[sp++] = i + width;
            }
        }
        version++;
    }

    /** 全部清成透明（先存撤销） */
    public void clear()
    {
        if(isBlank())
            return;
        beginStroke();
        Arrays.fill(pixels, (byte) 0);
        version++;
    }

    /** 提交用的 PNG */
    public byte[] toPng()
    {
        return DyePng.encode(width, height, pixels);
    }

    /** 表单字段的值：{@code data:image/png;base64,…} */
    public String toDataUrl()
    {
        return DyePng.toDataUrl(toPng());
    }

    // ---- 同步给旁观者 ----

    /** 压缩的像素（一字节一个像素，deflate） */
    public byte[] syncBytes()
    {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try
        {
            d.setInput(pixels);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while(!d.finished())
                out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        }
        finally
        {
            d.end();
        }
    }

    /** 解开同步来的像素：解出来要正好宽 × 高个字节、每个值 0–16，不然返回 null */
    @Nullable
    public static byte[] fromSync(byte[] data, int width, int height)
    {
        if(data == null || data.length > MAX_SYNC_BYTES || !DyePng.validSize(width, height))
            return null;
        Inflater inf = new Inflater();
        try
        {
            inf.setInput(data);
            byte[] out = new byte[width * height];
            int n = 0;
            while(n < out.length)
            {
                int k = inf.inflate(out, n, out.length - n);
                if(k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary()))
                    break;
                n += k;
            }
            if(n != out.length)
                return null;
            // 正好这么多，后面不能再有
            if(!inf.finished() && (inf.inflate(new byte[1]) != 0 || !inf.finished()))
                return null;
            for(byte b : out)
            {
                if(b < 0 || b > DyePng.DYE_RGB.length)
                    return null;
            }
            return out;
        }
        catch(DataFormatException e)
        {
            return null;
        }
        finally
        {
            inf.end();
        }
    }

    /**
     * 页面上画板的显示倍数：宽不超过 480、高不超过 256 的最大整数倍（至少 1，最多 4），编辑时另外放大。
     */
    public static int previewZoom(int width, int height)
    {
        int z = Math.min(480 / Math.max(1, width), 256 / Math.max(1, height));
        return Math.max(1, Math.min(4, z));
    }
}
