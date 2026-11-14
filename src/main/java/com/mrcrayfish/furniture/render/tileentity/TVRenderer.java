package com.mrcrayfish.furniture.render.tileentity;

import com.mrcrayfish.furniture.blocks.BlockFurnitureTile;
import com.mrcrayfish.furniture.client.tv.TvScreenPainter;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.EnumFacing;

/**
 * Author: MrCrayfish
 * <p>
 * 悠然服务器魔改：屏幕内容（GIF、bilibili 视频、封面、噪点、提示、弹幕、进度条）由 {@link TvScreenPainter} 画，
 * 这里只把坐标系摆到屏幕上：原点在观看者看到的左上角，x 向右、y 向下，1 个单位 = 0.01 格（同原来的提示文字）。
 */
public class TVRenderer extends TileEntitySpecialRenderer<TileEntityTV>
{
    /** 画布单位：每格 100 个单位，屏幕 1 像素（1/16 格）= 6.25 个单位 */
    private static final float UNITS_PER_PIXEL = 6.25F;

    @Override
    public void render(TileEntityTV te, double x, double y, double z, float partialTicks, int destroyStage, float alpha)
    {
        // 关着或没电（悠然服务器魔改）：黑屏
        if(!te.isScreenOn())
            return;

        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if(!state.getPropertyKeys().contains(BlockFurnitureTile.FACING))
            return;

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.translate(8 * 0.0625, te.getScreenYOffset() * 0.0625, 8 * 0.0625);
        EnumFacing facing = state.getValue(BlockFurnitureTile.FACING);
        GlStateManager.rotate(facing.getHorizontalIndex() * -90F, 0, 1, 0);
        GlStateManager.translate(-te.getWidth() / 2 * 0.0625, 0, 0);
        GlStateManager.translate(0, 0, te.getScreenZOffset() * 0.0625 - 0.01 * 0.0625);
        // 屏幕左上角（观看者看来），翻成 x 向右、y 向下
        GlStateManager.translate(te.getWidth() * 0.0625, te.getHeight() * 0.0625, 0);
        GlStateManager.scale(1, -1, -1);
        GlStateManager.scale(0.01F, 0.01F, 0.01F);
        GlStateManager.rotate(180F, 0, 1, 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);

        float w = te.getWidth() * UNITS_PER_PIXEL;
        float h = te.getHeight() * UNITS_PER_PIXEL;
        // 提示文字按屏幕大小放大一些（小电视也认得出）
        TvScreenPainter.paint(te, w, h, Math.max(0.5F, h / 110F), false);
        GlStateManager.popMatrix();
    }
}
