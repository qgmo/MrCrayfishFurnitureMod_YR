package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 画板内容同步给旁观者（同“操作者正在打的字”，服主 11-15）：操作者画的东西整张压缩发给服务端，服务端检查后转给旁观者。纯逻辑，两端都用。
 * <ul>
 * <li>操作者每 {@value #INTERVAL_MS} 毫秒最多发一张（{@link Outbox}），没改过页面给的初始图时不发；服务端再按玩家限频；</li>
 * <li>服务端只收“服务端自己解析的当前页面上的画板”、尺寸对得上、解压后正好宽 × 高个像素且每个值 0–16 的（{@link #check}）。</li>
 * </ul>
 */
public final class CanvasSync
{
    public static final long INTERVAL_MS = 400;
    /** 服务端：每个玩家每秒最多几张、最多攒几张 */
    public static final double SERVER_RATE = 3;
    public static final double SERVER_BURST = 6;

    private CanvasSync() {}

    /**
     * 服务端：检查操作者发来的一张；合格返回 null，不合格返回原因。
     *
     * @param facts       服务端自己解析的当前页面
     * @param currentPage 服务端当前页面的上传编号
     */
    @Nullable
    public static String check(PageFacts facts, int currentPage, int page, int field, int width, int height, byte[] data)
    {
        if(page != currentPage)
            return "canvas for a page that is not shown (" + page + " != " + currentPage + ")";
        int[] wh = facts.canvases.get(field);
        if(wh == null)
            return "canvas " + field + " is not a canvas on the page";
        if(wh[0] != width || wh[1] != height)
            return "canvas " + field + " size " + width + "x" + height + " does not match the page (" + wh[0] + "x" + wh[1] + ")";
        if(data.length > PixelCanvas.MAX_SYNC_BYTES || PixelCanvas.fromSync(data, width, height) == null)
            return "canvas " + field + " data is malformed (" + data.length + " bytes)";
        return null;
    }

    /** 操作者这边：记下每张画板发出去的版本，变了就发（每 {@value #INTERVAL_MS} 毫秒最多一张） */
    public static final class Outbox
    {
        private final Map<Integer, Integer> sent = new HashMap<>();
        private long last = Long.MIN_VALUE;

        /** 换页面了 */
        public void reset()
        {
            sent.clear();
        }

        /** 这张要不要发：内容变了（没画过、和页面初始图一样的不发），且到时间了 */
        public boolean due(int field, PixelCanvas c, long now)
        {
            Integer v = sent.get(field);
            if(v == null ? !c.isModified() : v == c.version())
                return false;
            return last == Long.MIN_VALUE || now - last >= INTERVAL_MS;
        }

        public void sent(int field, PixelCanvas c, long now)
        {
            sent.put(field, c.version());
            last = now;
        }
    }
}
