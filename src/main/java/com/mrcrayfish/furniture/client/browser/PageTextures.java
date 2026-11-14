package com.mrcrayfish.furniture.client.browser;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** 渲染好的页面图切成几块贴图（每块不超过 {@value #TILE} 像素高），在主线程上传、用完删掉 */
final class PageTextures
{
    static final int TILE = 1024;

    final RenderedPage page;
    final List<DynamicTexture> tiles = new ArrayList<>();
    /** 每块从图上第几行开始、多高（图像素） */
    final List<int[]> rows = new ArrayList<>();

    PageTextures(RenderedPage page)
    {
        this.page = page;
        BufferedImage img = page.image;
        for(int y = 0; y < img.getHeight(); y += TILE)
        {
            int h = Math.min(TILE, img.getHeight() - y);
            BufferedImage part = img.getSubimage(0, y, img.getWidth(), h);
            BufferedImage copy = new BufferedImage(img.getWidth(), h, BufferedImage.TYPE_INT_ARGB);
            copy.getGraphics().drawImage(part, 0, 0, null);
            DynamicTexture t = new DynamicTexture(copy);
            GlStateManager.bindTexture(t.getGlTextureId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            tiles.add(t);
            rows.add(new int[]{y, h});
        }
    }

    void dispose()
    {
        for(DynamicTexture t : tiles)
            t.deleteGlTexture();
        tiles.clear();
        rows.clear();
    }
}
