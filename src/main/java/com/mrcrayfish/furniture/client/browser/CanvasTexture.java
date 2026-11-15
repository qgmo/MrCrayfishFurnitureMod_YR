package com.mrcrayfish.furniture.client.browser;

import moe.qingu.youran.api.image.DyePng;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/** 一张画板的贴图（像素原样上传，按最近邻放大画）；内容变了才重新上传。只在主线程用 */
final class CanvasTexture
{
    private DynamicTexture texture;
    private Object owner;
    private int width, height;
    private int version = Integer.MIN_VALUE;

    /** 换成这张画板的这个版本（{@code owner} 是画板对象本身，换了对象就重做） */
    void update(Object owner, int width, int height, int version, byte[] pixels)
    {
        if(texture == null || owner != this.owner || width != this.width || height != this.height)
        {
            dispose();
            texture = new DynamicTexture(width, height);
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.version = Integer.MIN_VALUE;
        }
        if(version == this.version)
            return;
        this.version = version;
        int[] data = texture.getTextureData();
        for(int i = 0; i < pixels.length && i < data.length; i++)
            data[i] = DyePng.argb(pixels[i]);
        texture.updateDynamicTexture();
        GlStateManager.bindTexture(texture.getGlTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    /** 画在界面坐标 (x, y) 起、宽高 w × h 的地方（透明的像素透出底下） */
    void draw(double x, double y, double w, double h, double z)
    {
        if(texture == null)
            return;
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.bindTexture(texture.getGlTextureId());
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buf.pos(x, y + h, z).tex(0, 1).endVertex();
        buf.pos(x + w, y + h, z).tex(1, 1).endVertex();
        buf.pos(x + w, y, z).tex(1, 0).endVertex();
        buf.pos(x, y, z).tex(0, 0).endVertex();
        tess.draw();
        GlStateManager.disableBlend();
    }

    void dispose()
    {
        if(texture != null)
        {
            texture.deleteGlTexture();
            texture = null;
        }
        owner = null;
    }
}
