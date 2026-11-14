package com.mrcrayfish.furniture.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Author: MrCrayfish
 * <p>
 * 悠然服务器魔改：解码改在 MediaDownloader 的线程池里、先按尺寸上限检查再解码（见 {@link MediaDecoding}），
 * 这里只接收已经解码好的图片，GL 上传仍在主线程。
 */
public class Texture
{
    private ByteBuffer buffer;
    private int textureId = -1;
    protected int width, height;
    protected int counter;
    protected boolean delete;

    protected Texture() {}

    /**
     * 在工作线程里调用：把解码好的图片转成缓冲区，并安排在主线程上传。
     */
    public Texture(BufferedImage image)
    {
        this.width = image.getWidth();
        this.height = image.getHeight();
        int[] imageData = new int[this.width * this.height];
        image.getRGB(0, 0, this.width, this.height, imageData, 0, this.width);
        this.buffer = createBuffer(imageData);
        Minecraft.getMinecraft().addScheduledTask(() ->
        {
            GlStateManager.bindTexture(getTextureId());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        });
    }

    public void update()
    {
        if(counter++ >= 600)
        {
            delete = true;
            GlStateManager.deleteTexture(getTextureId());
        }
    }

    protected ByteBuffer createBuffer(int[] data)
    {
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = height - 1; y >= 0; y--)
        {
            for (int x = width - 1; x >= 0; x--)
            {
                int color = data[x + y * width];
                buffer.put((byte) ((color >> 16) & 0xff));
                buffer.put((byte) ((color >> 8) & 0xff));
                buffer.put((byte) (color & 0xff));
                buffer.put((byte) ((color >> 24) & 0xff));
            }
        }
        buffer.flip();
        return buffer;
    }

    public void bind()
    {
        counter = 0;
        GlStateManager.bindTexture(getTextureId());
    }

    public int getTextureId()
    {
        if(textureId == -1 || !GL11.glIsTexture(textureId))
        {
            textureId = GlStateManager.generateTexture();
        }
        return textureId;
    }

    public int getWidth()
    {
        return width;
    }

    public int getHeight()
    {
        return height;
    }

    public boolean isPendingDeletion()
    {
        return delete;
    }
}
