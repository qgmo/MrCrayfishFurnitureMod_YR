package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.client.AnimatedTexture;
import com.mrcrayfish.furniture.client.GifCache;
import com.mrcrayfish.furniture.client.GifDownloadThread;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.TvChannel;
import com.mrcrayfish.furniture.tv.TvClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.DoubleBuffer;
import java.util.List;
import java.util.Random;

/**
 * 画电视屏幕的内容（悠然服务器魔改）：世界里的电视和大屏共用。坐标系是“屏幕画布”：原点在观看者看到的左上角，
 * x 向右、y 向下，宽 w、高 h；往观看者方向是 −z。画面（视频、GIF、封面、噪点、提示文字）、弹幕、进度条都在这里画。
 */
public final class TvScreenPainter
{
    private static final ResourceLocation NOISE = new ResourceLocation("cfm:textures/noise.png");
    private static final Random RAND = new Random();
    private static final DoubleBuffer PLANE = BufferUtils.createDoubleBuffer(4);
    /** 弹幕：屏幕高分成几行，占上面几行 */
    private static final int DANMAKU_ROWS = 10;
    private static final int DANMAKU_LANES = 7;
    private static final int PINK = 0xFFFB7299;

    private TvScreenPainter() {}

    /** 画一台电视的整块屏幕。fontScale 是提示文字的大小（画布单位 / 字体像素） */
    public static void paint(TileEntityTV tv, float w, float h, float fontScale, boolean fullscreen)
    {
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.color(1F, 1F, 1F, 1F);
        TvChannel channel = TvChannel.parse(tv.getCurrentChannel());
        VideoSession session = null;
        long now = System.currentTimeMillis();
        long duration = 0;
        TvChannel.Kind kind = channel.getKind();
        if(kind != TvChannel.Kind.GIF && kind != TvChannel.Kind.EMPTY)
        {
            // 视频、番剧（暂不支持）、写法不对的频道
            TvVideoManager.Screen sc = TvVideoManager.INSTANCE.screen(tv);
            session = sc.session;
            if(session != null)
                duration = session.getDurationMs();
            switch(sc.kind)
            {
                case VIDEO:
                case COVER:
                    rect(0, 0, w, h, 0, 0xFF000000);
                    picture(sc.texture, sc.width, sc.height, w, h, tv.isStretched(), -0.1F);
                    break;
                case TEXT:
                    rect(0, 0, w, h, 0, 0xFF000000);
                    String text = sc.text.equals("cfm.tv.video.downloading") ? I18n.format(sc.text, Math.round(NativeLibs.progress() * 100)) : I18n.format(sc.text);
                    message(text, w, h, fontScale);
                    break;
                default:
                    noise(w, h);
            }
            if(session != null && sc.kind == TvVideoManager.Screen.Kind.VIDEO && tv.isDanmakuOn() && !TvClientConfig.get().alwaysHideDanmaku)
            {
                danmaku(session, tv.clientClock(TvVideoManager.latencyMs(Minecraft.getMinecraft())).targetAt(now), w, h);
            }
        }
        else
        {
            gif(tv, w, h, fontScale);
        }
        if(tv.isClientOsdShown())
        {
            osd(tv, tv.clientClock(TvVideoManager.latencyMs(Minecraft.getMinecraft())), now, duration, w, h, channel.isVideo());
        }
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }

    /** GIF 频道（原来的画法，挪到画布坐标里） */
    private static void gif(TileEntityTV tv, float w, float h, float fontScale)
    {
        GifDownloadThread.ImageDownloadResult result = tv.getResult();
        if(result != null && result != GifDownloadThread.ImageDownloadResult.SUCCESS)
        {
            rect(0, 0, w, h, 0, 0xFF000000);
            message(I18n.format(result.getKey()), w, h, fontScale);
            return;
        }
        if(tv.isLoading())
        {
            noise(w, h);
            return;
        }
        if(tv.isLoaded())
        {
            AnimatedTexture texture = GifCache.INSTANCE.get(tv.getCurrentChannel());
            if(texture != null)
            {
                rect(0, 0, w, h, 0, 0xFF000000);
                texture.bind();
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                fitted(texture.getWidth(), texture.getHeight(), w, h, tv.isStretched(), -0.1F);
            }
            else
            {
                String current = tv.getCurrentChannel();
                if(current != null)
                    tv.loadUrl(current);
            }
        }
    }

    /** 画一张纹理，保持比例居中（stretch 时铺满） */
    private static void picture(int texture, int tw, int th, float w, float h, boolean stretch, float z)
    {
        GlStateManager.bindTexture(texture);
        fitted(tw, th, w, h, stretch, z);
    }

    private static void fitted(int tw, int th, float w, float h, boolean stretch, float z)
    {
        float x0 = 0, y0 = 0, pw = w, ph = h;
        if(!stretch && tw > 0 && th > 0)
        {
            float scale = Math.min(w / tw, h / th);
            pw = tw * scale;
            ph = th * scale;
            x0 = (w - pw) / 2;
            y0 = (h - ph) / 2;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.color(1F, 1F, 1F, 1F);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        b.pos(x0, y0, z).tex(0, 0).endVertex();
        b.pos(x0, y0 + ph, z).tex(0, 1).endVertex();
        b.pos(x0 + pw, y0 + ph, z).tex(1, 1).endVertex();
        b.pos(x0 + pw, y0, z).tex(1, 0).endVertex();
        t.draw();
    }

    /** 噪点：每帧取噪点图上随机的一块 */
    public static void noise(float w, float h)
    {
        Minecraft.getMinecraft().getTextureManager().bindTexture(NOISE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        double span = 64.0 / 256;
        double u = RAND.nextDouble() * (1 - span);
        double v = RAND.nextDouble() * (1 - span);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1F, 1F, 1F, 1F);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        b.pos(0, 0, 0).tex(u, v).endVertex();
        b.pos(0, h, 0).tex(u, v + span).endVertex();
        b.pos(w, h, 0).tex(u + span, v + span).endVertex();
        b.pos(w, 0, 0).tex(u + span, v).endVertex();
        t.draw();
    }

    /** 提示文字，按屏幕宽换行 */
    private static void message(String text, float w, float h, float fontScale)
    {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        GlStateManager.pushMatrix();
        GlStateManager.translate(w * 0.06F, h * 0.08F, -0.3F);
        GlStateManager.scale(fontScale, fontScale, 1F);
        List<String> lines = font.listFormattedStringToWidth(text, (int) (w * 0.88F / fontScale));
        for(int i = 0; i < lines.size(); i++)
            font.drawString(lines.get(i), 0, font.FONT_HEIGHT * i, 0xFFFFFF);
        GlStateManager.popMatrix();
    }

    /** 填色矩形（不带纹理） */
    static void rect(float x0, float y0, float x1, float y1, float z, int argb)
    {
        GlStateManager.disableTexture2D();
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, bl = argb & 255;
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        b.pos(x0, y0, z).color(r, g, bl, a).endVertex();
        b.pos(x0, y1, z).color(r, g, bl, a).endVertex();
        b.pos(x1, y1, z).color(r, g, bl, a).endVertex();
        b.pos(x1, y0, z).color(r, g, bl, a).endVertex();
        t.draw();
        GlStateManager.enableTexture2D();
    }

    /** 弹幕：按视频进度滚动，裁到屏幕范围里（剪裁平面） */
    private static void danmaku(VideoSession session, long timeMs, float w, float h)
    {
        List<Danmaku.Comment> comments = session.danmaku();
        if(comments == null || comments.isEmpty())
            return;
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        float lineHeight = h / DANMAKU_ROWS;
        float scale = lineHeight / (font.FONT_HEIGHT + 1);
        int canvasW = Math.max(1, (int) (w / scale));
        int canvasH = Math.max(1, (int) (h / scale));
        List<Danmaku.Placed> placed = session.danmakuLayout(canvasW, () -> Danmaku.layout(comments, canvasW, DANMAKU_LANES, font::getStringWidth));
        List<Danmaku.Visible> visible = Danmaku.visibleAt(placed, timeMs, canvasW, canvasH, font.FONT_HEIGHT + 1);
        if(visible.isEmpty())
            return;
        clip(w, h, true);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, -0.2F);
        GlStateManager.scale(scale, scale, 1F);
        for(Danmaku.Visible v : visible)
            font.drawString(v.text, v.x, v.y, 0xFF000000 | v.color, true);
        GlStateManager.popMatrix();
        clip(w, h, false);
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    private static void clip(float w, float h, boolean on)
    {
        if(!on)
        {
            for(int i = 0; i < 4; i++)
                GL11.glDisable(GL11.GL_CLIP_PLANE0 + i);
            return;
        }
        plane(0, 1, 0, 0);
        plane(1, -1, 0, w);
        plane(2, 0, 1, 0);
        plane(3, 0, -1, h);
    }

    private static void plane(int index, double a, double b, double d)
    {
        PLANE.clear();
        PLANE.put(a).put(b).put(0).put(d).flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + index, PLANE);
        GL11.glEnable(GL11.GL_CLIP_PLANE0 + index);
    }

    /** 进度条（像现代电视的菜单，只有进度条和一行状态），在场的人都看得到 */
    private static void osd(TileEntityTV tv, TvClock clock, long now, long durationMs, float w, float h, boolean video)
    {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        float band = Math.max(h * 0.2F, 1F);
        float top = h - band;
        rect(0, top, w, h, -0.4F, 0xB0000000);
        long pos = clock.targetAt(now);
        if(durationMs > 0)
            pos = Math.min(pos, durationMs);
        float margin = w * 0.04F;
        float barY = top + band * 0.68F;
        float barH = Math.max(band * 0.12F, 0.5F);
        if(video)
        {
            rect(margin, barY, w - margin, barY + barH, -0.5F, 0xFF555555);
            if(durationMs > 0)
            {
                float frac = Math.min(1F, pos / (float) durationMs);
                rect(margin, barY, margin + (w - 2 * margin) * frac, barY + barH, -0.6F, PINK);
            }
        }
        StringBuilder line = new StringBuilder();
        if(video)
        {
            line.append(clock.isPlaying() ? "▶ " : "❙❙ ");
            line.append(time(pos));
            if(durationMs > 0)
                line.append(" / ").append(time(durationMs));
            line.append("   ");
        }
        line.append(I18n.format("cfm.tv.osd.volume", tv.getVolume()));
        if(video)
            line.append("   ").append(I18n.format(tv.isDanmakuOn() ? "cfm.tv.osd.danmaku_on" : "cfm.tv.osd.danmaku_off"));
        String text = line.toString();
        float textScale = band * 0.4F / font.FONT_HEIGHT;
        float maxWidth = (w - 2 * margin) / textScale;
        if(font.getStringWidth(text) > maxWidth)
            textScale *= maxWidth / font.getStringWidth(text);
        GlStateManager.pushMatrix();
        GlStateManager.translate(margin, top + band * 0.14F, -0.7F);
        GlStateManager.scale(textScale, textScale, 1F);
        font.drawString(text, 0, 0, 0xFFFFFF);
        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    static String time(long ms)
    {
        long s = Math.max(0, ms / 1000);
        long hh = s / 3600, mm = (s / 60) % 60, ss = s % 60;
        return hh > 0 ? String.format("%d:%02d:%02d", hh, mm, ss) : String.format("%d:%02d", mm, ss);
    }
}
