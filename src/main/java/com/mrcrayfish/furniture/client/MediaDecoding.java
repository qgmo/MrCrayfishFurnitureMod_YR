package com.mrcrayfish.furniture.client;

import at.dhyan.open_imaging.GifDecoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 相框图片、电视 GIF 的读取与尺寸上限（悠然服务器魔改）。不依赖客户端类，可单元测试。
 * <p>
 * 原来解码前不看尺寸：2MB 以内的 PNG 可以声明上万像素见方，解码成 int[] 能直接把客户端内存撑爆；
 * GIF 的帧数、每帧大小也没有上限。这里先读文件头拿到宽高（GIF 还有帧数和每帧大小），
 * 超过上限就拒绝，不做完整解码。
 */
public final class MediaDecoding
{
    private MediaDecoding() {}

    /** 拒绝原因：超过上限，或文件无法读取。 */
    public enum Reason
    {
        TOO_LARGE, INVALID
    }

    public static final class Rejected extends Exception
    {
        public final Reason reason;

        public Rejected(Reason reason, String message)
        {
            super(message, null, false, false);
            this.reason = reason;
        }
    }

    public static boolean imageAllowed(int width, int height, int maxSide)
    {
        return width > 0 && height > 0 && width <= maxSide && height <= maxSide;
    }

    /**
     * GIF：画布宽高都不超过 maxSide，帧数在 1..maxFrames，每帧自身的像素数不超过画布允许的最大面积，
     * 解码后全部帧的总像素（画布面积 × 帧数）不超过 maxTotalPixels。
     */
    public static boolean gifAllowed(int width, int height, int frames, long maxFramePixels, int maxSide, int maxFrames, long maxTotalPixels)
    {
        if(!imageAllowed(width, height, maxSide))
            return false;
        if(frames < 1 || frames > maxFrames)
            return false;
        if(maxFramePixels > (long) maxSide * maxSide)
            return false;
        return (long) width * height * frames <= maxTotalPixels;
    }

    /**
     * 最多读 max 个字节；超过时返回 null（不再继续读）。
     */
    public static byte[] readAtMost(InputStream in, long max) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(max, 0), 64 * 1024));
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while((n = in.read(buf)) != -1)
        {
            total += n;
            if(total > max)
                return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 读 PNG / JPEG：先从文件头取宽高，超过 maxSide 就拒绝，否则完整解码。
     */
    public static BufferedImage decodeImage(byte[] data, int maxSide) throws Rejected
    {
        try(ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data)))
        {
            if(iis == null)
                throw new Rejected(Reason.INVALID, "no image stream");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if(!readers.hasNext())
                throw new Rejected(Reason.INVALID, "unknown image format");
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if(!imageAllowed(width, height, maxSide))
                    throw new Rejected(Reason.TOO_LARGE, width + "x" + height);
                BufferedImage image = reader.read(0);
                if(image == null || image.getWidth() != width || image.getHeight() != height)
                    throw new Rejected(Reason.INVALID, "decoded size differs");
                return image;
            }
            finally
            {
                reader.dispose();
            }
        }
        catch(Rejected e)
        {
            throw e;
        }
        catch(Exception | OutOfMemoryError | StackOverflowError e)
        {
            throw new Rejected(Reason.INVALID, e.toString());
        }
    }

    /**
     * 读 GIF 的结构（不解码像素），按上限检查后返回；帧由调用方逐帧取出。
     */
    public static GifDecoder.GifImage readGif(byte[] data, int maxSide, int maxFrames, long maxTotalPixels) throws Rejected
    {
        GifDecoder.GifImage gif;
        try
        {
            gif = GifDecoder.read(data);
        }
        catch(Exception | OutOfMemoryError | StackOverflowError e)
        {
            throw new Rejected(Reason.INVALID, e.toString());
        }
        if(gif.getFrameCount() < 1 || gif.getWidth() < 1 || gif.getHeight() < 1)
            throw new Rejected(Reason.INVALID, "empty GIF");
        if(!gifAllowed(gif.getWidth(), gif.getHeight(), gif.getFrameCount(), gif.getMaxFramePixels(), maxSide, maxFrames, maxTotalPixels))
            throw new Rejected(Reason.TOO_LARGE, gif.getWidth() + "x" + gif.getHeight() + " x" + gif.getFrameCount());
        return gif;
    }
}
