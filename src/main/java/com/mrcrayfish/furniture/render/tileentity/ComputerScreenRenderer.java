package com.mrcrayfish.furniture.render.tileentity;

import com.mrcrayfish.furniture.blocks.BlockFurnitureTile;
import com.mrcrayfish.furniture.computer.ScreenThumb;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 电脑在世界里的屏幕（悠然服务器魔改，服主 11-16）：把服务端定下的页面缩略图贴在模型的屏幕上（最近邻放大，不受光照影响，像亮着的屏幕）。
 * 没有画面（没人开过、关机、没电、换了人）时什么也不画，还是模型原来的黑屏。
 * <p>
 * 屏幕的位置取自 {@code models/block/computer.json} 的黑色屏幕块（{@code from [4.8, 7.4, 3.0] to [6.4, 15.0, 13.0]}，贴 {@code concrete_black}）
 * 朝 -X 的那一面：x = 4.8，y 7.4–15.0，z 3.0–13.0（模型像素）。方块状态按朝向转模型（east 不转，south 90°，west 180°，north 270°），这里照着转。
 * 每台电脑一张贴图，换帧时重传；方块实体卸载、换世界时删掉。
 */
public class ComputerScreenRenderer extends TileEntitySpecialRenderer<TileEntityComputer>
{
    private static final double PX = 1.0 / 16.0;
    /** 屏幕面在模型里的位置（模型像素，facing=east 时） */
    static final double SCREEN_X = 4.8, SCREEN_Y0 = 7.4, SCREEN_Y1 = 15.0, SCREEN_Z0 = 3.0, SCREEN_Z1 = 13.0;
    /** 往屏幕外挪一点，免得和模型的黑色面抢深度 */
    private static final double LIFT = 0.002;

    private static final Map<TileEntityComputer, Texture> TEXTURES = new IdentityHashMap<>();

    private static final class Texture
    {
        final DynamicTexture texture;
        final int width, height;
        int version = Integer.MIN_VALUE;

        Texture(int width, int height)
        {
            this.width = width;
            this.height = height;
            this.texture = new DynamicTexture(width, height);
            GlStateManager.bindTexture(texture.getGlTextureId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }
    }

    @Override
    public void render(TileEntityComputer te, double x, double y, double z, float partialTicks, int destroyStage, float alpha)
    {
        ScreenThumb.Frame frame = te.getScreenFrame();
        if(frame == null || !te.isScreenOn() || te.getWorld() == null)
            return;
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if(!state.getPropertyKeys().contains(BlockFurnitureTile.FACING))
            return;
        Texture t = texture(te, frame);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 0.5, y, z + 0.5);
        GlStateManager.rotate(modelRotation(state.getValue(BlockFurnitureTile.FACING)), 0, 1, 0);
        GlStateManager.translate(-0.5, 0, -0.5);

        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1F, 1F, 1F, 1F);
        float lastX = OpenGlHelper.lastBrightnessX, lastY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        GlStateManager.bindTexture(t.texture.getGlTextureId());

        double sx = SCREEN_X * PX - LIFT;
        double y0 = SCREEN_Y0 * PX, y1 = SCREEN_Y1 * PX, z0 = SCREEN_Z0 * PX, z1 = SCREEN_Z1 * PX;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        // 从 -X 看过去：右手边是 +Z，上是 +Y；逆时针：左下、右下、右上、左上
        buf.pos(sx, y0, z0).tex(0, 1).endVertex();
        buf.pos(sx, y0, z1).tex(1, 1).endVertex();
        buf.pos(sx, y1, z1).tex(1, 0).endVertex();
        buf.pos(sx, y1, z0).tex(0, 0).endVertex();
        tess.draw();

        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastX, lastY);
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    /** 方块状态 JSON 里各朝向的 y 转角（east 0、south 90、west 180、north 270，顺时针）换成 GL 的转角（逆时针为正） */
    static float modelRotation(EnumFacing facing)
    {
        switch(facing)
        {
            case SOUTH:
                return -90F;
            case WEST:
                return -180F;
            case NORTH:
                return -270F;
            default:
                return 0F;
        }
    }

    private static Texture texture(TileEntityComputer te, ScreenThumb.Frame frame)
    {
        Texture t = TEXTURES.get(te);
        if(t != null && (t.width != frame.width || t.height != frame.height))
        {
            t.texture.deleteGlTexture();
            t = null;
        }
        if(t == null)
        {
            t = new Texture(frame.width, frame.height);
            TEXTURES.put(te, t);
        }
        if(t.version != te.getScreenVersion())
        {
            t.version = te.getScreenVersion();
            int[] data = t.texture.getTextureData();
            System.arraycopy(frame.argb, 0, data, 0, Math.min(data.length, frame.argb.length));
            t.texture.updateDynamicTexture();
        }
        return t;
    }

    /** 方块实体卸载（区块卸载、拆掉）：删贴图 */
    public static void release(TileEntityComputer te)
    {
        Texture t = TEXTURES.remove(te);
        if(t != null)
            t.texture.deleteGlTexture();
    }

    private static void releaseAll()
    {
        for(Texture t : TEXTURES.values())
            t.texture.deleteGlTexture();
        TEXTURES.clear();
    }

    /** 换世界、断线时全删；平时每 5 秒扫一次已经不在当前世界的（漏掉卸载通知的） */
    public static final class Events
    {
        private int ticks;

        @SubscribeEvent
        public void onWorldUnload(WorldEvent.Unload event)
        {
            if(event.getWorld().isRemote)
                releaseAll();
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event)
        {
            if(event.phase != TickEvent.Phase.END || ++ticks % 100 != 0 || TEXTURES.isEmpty())
                return;
            Minecraft mc = Minecraft.getMinecraft();
            Iterator<Map.Entry<TileEntityComputer, Texture>> it = TEXTURES.entrySet().iterator();
            while(it.hasNext())
            {
                Map.Entry<TileEntityComputer, Texture> e = it.next();
                TileEntityComputer te = e.getKey();
                if(te.isInvalid() || te.getWorld() != mc.world || te.getScreenFrame() == null)
                {
                    e.getValue().texture.deleteGlTexture();
                    it.remove();
                }
            }
        }
    }
}
