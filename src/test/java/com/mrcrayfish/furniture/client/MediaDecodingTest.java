package com.mrcrayfish.furniture.client;

import at.dhyan.open_imaging.GifDecoder;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class MediaDecodingTest
{
    private static byte[] png(int w, int h) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] gif(int w, int h, int frames) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try(ImageOutputStream ios = ImageIO.createImageOutputStream(out))
        {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for(int i = 0; i < frames; i++)
            {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                img.setRGB(i % w, 0, 0xFFFFFF);
                writer.writeToSequence(new IIOImage(img, null, null), null);
            }
            writer.endWriteSequence();
        }
        writer.dispose();
        return out.toByteArray();
    }

    /** 只有文件头的 PNG：IHDR 声明的宽高很大，后面没有像素数据。 */
    private static byte[] pngHeaderOnly(int w, int h)
    {
        ByteBuffer ihdr = ByteBuffer.allocate(4 + 13);
        ihdr.put(new byte[]{'I', 'H', 'D', 'R'}).putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 17 + 4);
        buf.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}).putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        return buf.array();
    }

    @Test
    void imageLimits()
    {
        assertTrue(MediaDecoding.imageAllowed(1024, 1024, 1024));
        assertFalse(MediaDecoding.imageAllowed(1025, 10, 1024));
        assertFalse(MediaDecoding.imageAllowed(10, 1025, 1024));
        assertFalse(MediaDecoding.imageAllowed(0, 10, 1024));
        assertFalse(MediaDecoding.imageAllowed(-1, 10, 1024));
    }

    @Test
    void gifLimits()
    {
        assertTrue(MediaDecoding.gifAllowed(320, 240, 100, 320 * 240, 512, 256, 16777216L));
        assertFalse(MediaDecoding.gifAllowed(600, 240, 10, 600 * 240, 512, 256, 16777216L));   // 宽超
        assertFalse(MediaDecoding.gifAllowed(320, 240, 257, 320 * 240, 512, 256, 16777216L));  // 帧数超
        assertFalse(MediaDecoding.gifAllowed(320, 240, 0, 0, 512, 256, 16777216L));            // 没有帧
        assertFalse(MediaDecoding.gifAllowed(512, 512, 65, 512 * 512, 512, 256, 16777216L));   // 总像素超（64 帧刚好）
        assertTrue(MediaDecoding.gifAllowed(512, 512, 64, 512 * 512, 512, 256, 16777216L));
        assertFalse(MediaDecoding.gifAllowed(16, 16, 1, 65535L * 65535L, 512, 256, 16777216L)); // 单帧自称极大
    }

    @Test
    void readAtMost() throws IOException
    {
        byte[] data = new byte[10000];
        Arrays.fill(data, (byte) 7);
        assertArrayEquals(data, MediaDecoding.readAtMost(new ByteArrayInputStream(data), 10000));
        assertNull(MediaDecoding.readAtMost(new ByteArrayInputStream(data), 9999));
        assertEquals(0, MediaDecoding.readAtMost(new ByteArrayInputStream(new byte[0]), 10).length);
    }

    @Test
    void decodeImageWithinLimit() throws Exception
    {
        BufferedImage img = MediaDecoding.decodeImage(png(32, 16), 64);
        assertEquals(32, img.getWidth());
        assertEquals(16, img.getHeight());
    }

    @Test
    void decodeImageRejectsLargeDimensions() throws IOException
    {
        MediaDecoding.Rejected e = assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.decodeImage(png(65, 8), 64));
        assertEquals(MediaDecoding.Reason.TOO_LARGE, e.reason);
    }

    @Test
    void decodeImageRejectsHugeHeaderWithoutDecoding()
    {
        // 50000×50000 若解码需要约 10 GB；只读文件头就应拒绝
        MediaDecoding.Rejected e = assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.decodeImage(pngHeaderOnly(50000, 50000), 1024));
        assertEquals(MediaDecoding.Reason.TOO_LARGE, e.reason);
    }

    @Test
    void decodeImageRejectsGarbageAndTruncated() throws IOException
    {
        assertEquals(MediaDecoding.Reason.INVALID, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.decodeImage(new byte[]{1, 2, 3, 4}, 1024)).reason);
        assertEquals(MediaDecoding.Reason.INVALID, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.decodeImage(new byte[0], 1024)).reason);
        byte[] full = png(64, 64);
        byte[] truncated = Arrays.copyOf(full, full.length / 2);
        assertEquals(MediaDecoding.Reason.INVALID, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.decodeImage(truncated, 1024)).reason);
        assertEquals(MediaDecoding.Reason.INVALID, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.decodeImage(pngHeaderOnly(16, 16), 1024)).reason);
    }

    @Test
    void readGifWithinLimits() throws Exception
    {
        GifDecoder.GifImage g = MediaDecoding.readGif(gif(20, 10, 3), 512, 256, 16777216L);
        assertEquals(20, g.getWidth());
        assertEquals(3, g.getFrameCount());
        assertEquals(20 * 10, g.getMaxFramePixels());
        assertNotNull(g.getFrame(2));
    }

    @Test
    void readGifRejectsTooManyFramesOrTooLarge() throws IOException
    {
        byte[] data = gif(20, 10, 5);
        assertEquals(MediaDecoding.Reason.TOO_LARGE, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.readGif(data, 512, 4, 16777216L)).reason);
        assertEquals(MediaDecoding.Reason.TOO_LARGE, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.readGif(data, 16, 256, 16777216L)).reason);
        assertEquals(MediaDecoding.Reason.TOO_LARGE, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.readGif(data, 512, 256, 999L)).reason);
    }

    @Test
    void readGifRejectsGarbage()
    {
        assertEquals(MediaDecoding.Reason.INVALID, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.readGif(new byte[]{'G', 'I', 'F'}, 512, 256, 16777216L)).reason);
        assertEquals(MediaDecoding.Reason.INVALID, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.readGif(new byte[0], 512, 256, 16777216L)).reason);
        byte[] noFrames = {'G', 'I', 'F', '8', '9', 'a', 10, 0, 10, 0, 0, 0, 0, 0x3B};
        assertEquals(MediaDecoding.Reason.INVALID, assertThrows(MediaDecoding.Rejected.class, () -> MediaDecoding.readGif(noFrames, 512, 256, 16777216L)).reason);
    }
}
