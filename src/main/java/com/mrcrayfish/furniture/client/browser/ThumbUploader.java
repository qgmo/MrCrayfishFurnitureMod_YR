package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.computer.ScreenThumb;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerThumb;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 开着电脑界面时，把当前看到的页面画成屏幕缩略图传给服务端（服主 11-16；操作者、旁观者都传）。
 * <ul>
 * <li>界面里的页面按各人窗口大小定的倍数渲染，倍数不同画出来的字粗细略有不同；为了大家的缩略图一样，按固定倍数
 * {@value #THUMB_SCALE} 用同样的原文和图片另画一张（在浏览器的渲染线程上，排在界面要的渲染后面），缩略图从这张上取。</li>
 * <li>页面（渲染结果、上传编号）或滚动位置变了（含旁观者取到图片后重画）就在后台线程上缩一张，每 {@code intervalMs}（默认 1 秒）最多传一张；
 * 同一页面、同一滚动位置缩出来和上次一样的不再传。</li>
 * </ul>
 * 只读渲染结果，不碰地址栏、表单里的东西。只在主线程调。
 */
final class ThumbUploader
{
    /** 缩略图用的渲染倍数（1 个 CSS 像素 = 1 个图像素） */
    static final double THUMB_SCALE = 1.0;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "cfm 屏幕缩略图");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** 一个状态：哪张渲染结果、哪一页（上传编号）、滚到哪里、按什么规格 */
    private static final class Key
    {
        final RenderedPage page;
        final int pageId;
        final int scroll;
        final ScreenThumb.Spec spec;

        Key(RenderedPage page, int pageId, int scroll, ScreenThumb.Spec spec)
        {
            this.page = page;
            this.pageId = pageId;
            this.scroll = scroll;
            this.spec = spec;
        }

        boolean same(@Nullable Key k)
        {
            return k != null && k.page == page && k.pageId == pageId && k.scroll == scroll && k.spec.equals(spec);
        }
    }

    private final BlockPos pos;
    /** 界面里的那张渲染结果，和按固定倍数另画的那张（还没画好为 null） */
    @Nullable
    private RenderedPage base;
    @Nullable
    private RenderedPage fixed;
    private int fixedRequest;
    @Nullable
    private Key wanted;
    /** 正在后台画的 */
    @Nullable
    private Key computing;
    /** 画好、还没传的 */
    @Nullable
    private Key readyKey;
    @Nullable
    private byte[] ready;
    private long lastSent = Long.MIN_VALUE;
    private int sentPage = -1, sentScroll = -1;
    @Nullable
    private byte[] sentPixels;
    private boolean closed;

    ThumbUploader(BlockPos pos)
    {
        this.pos = pos.toImmutable();
    }

    /**
     * 每 tick 调。
     *
     * @param page   现在显示的渲染结果（没有为 null：关机、还没画好）
     * @param pageId 它在服务端的上传编号（还没对上为 -1）
     * @param scroll 服务端知道的滚动位置（还没报上去为 -1）
     */
    void tick(@Nullable RenderedPage page, int pageId, int scroll, ScreenThumb.Spec spec, long now)
    {
        if(page == null || pageId < 0 || scroll < 0 || !spec.enabled)
        {
            wanted = null;
            return;
        }
        if(page != base)
        {
            base = page;
            fixed = null;
            requestFixed(page);
        }
        if(fixed == null)
            return;
        Key k = new Key(fixed, pageId, scroll, spec);
        if(!k.same(wanted))
            wanted = k;
        if(computing == null && !wanted.same(readyKey))
            start(wanted);
        if(ready != null && wanted.same(readyKey) && (lastSent == Long.MIN_VALUE || now - lastSent >= spec.intervalMs))
        {
            byte[] px = ready;
            ready = null;
            if(pageId == sentPage && scroll == sentScroll && Arrays.equals(px, sentPixels))
                return;
            lastSent = now;
            sentPage = pageId;
            sentScroll = scroll;
            sentPixels = px;
            PacketHandler.INSTANCE.sendToServer(new MessageComputerThumb(pos, pageId, scroll, spec.width, spec.height, ScreenThumb.pack(px)));
        }
    }

    /** 关界面：后台画好的丢掉 */
    void close()
    {
        closed = true;
        wanted = null;
        ready = null;
        base = null;
        fixed = null;
        fixedRequest++;
    }

    /** 按固定倍数另画一张（界面那张正好是这个倍数时直接用；另画失败时退回用界面那张） */
    private void requestFixed(RenderedPage page)
    {
        int req = ++fixedRequest;
        RenderedPage.Source src = page.source;
        if(page.scale == THUMB_SCALE || src == null)
        {
            fixed = page;
            return;
        }
        ComputerViews.render(src.html, src.mode, src.url, src.images, THUMB_SCALE, p ->
        {
            if(req == fixedRequest && !closed && base == page)
                fixed = p != null ? p : page;
        });
    }

    private void start(Key k)
    {
        computing = k;
        WORKER.execute(() ->
        {
            byte[] px = null;
            try
            {
                px = make(k.page.image, k.page.scale, k.scroll, k.spec);
            }
            catch(Throwable t)
            {
                // 画不了就不传，不能带崩游戏
                MrCrayfishFurnitureMod.logger().debug("Browser: screen thumbnail failed: {}", t.toString());
            }
            byte[] result = px;
            Minecraft.getMinecraft().addScheduledTask(() ->
            {
                computing = null;
                if(closed || result == null)
                    return;
                readyKey = k;
                ready = result;
            });
        });
    }

    /** 从渲染好的整页图上取景、缩小、取色（后台线程） */
    static byte[] make(BufferedImage image, double scale, int scroll, ScreenThumb.Spec spec)
    {
        int w = image.getWidth(), h = image.getHeight();
        int[] rows = ScreenThumb.rowsNeeded(h, scale, scroll, spec);
        int[] rgb = rows[1] == 0 ? new int[0] : image.getRGB(0, rows[0], w, rows[1], null, 0, w);
        return ScreenThumb.make(rgb, w, h, rows[0], rows[1], scale, scroll, spec);
    }
}
