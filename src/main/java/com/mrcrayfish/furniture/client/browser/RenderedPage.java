package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.PageParser;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 渲染好的一页：整页的图（宽是页面宽 × 倍数，高按内容、不超过上限）、各个编号元素在页面上的位置（CSS 像素） */
public final class RenderedPage
{
    public final PageDocument document;
    public final BufferedImage image;
    /** 图上 1 个 CSS 像素对应几个图像素 */
    public final double scale;
    public final int width;
    /** 画出来的高度（CSS 像素，不超过上限） */
    public final int height;
    /** 内容本来的高度 */
    public final int fullHeight;
    public final Map<Integer, List<Rect>> regions;
    /** 这一页是从什么画出来的（{@link ComputerViews#render} 填；按固定倍数再画一张屏幕缩略图时用） */
    @Nullable
    volatile Source source;

    /** 画这一页用的原文、解析方式、地址、图片 */
    static final class Source
    {
        final byte[] html;
        final PageParser.Mode mode;
        final String url;
        final Map<String, byte[]> images;

        Source(byte[] html, PageParser.Mode mode, String url, Map<String, byte[]> images)
        {
            this.html = html;
            this.mode = mode;
            this.url = url;
            this.images = images;
        }
    }

    public static final class Rect
    {
        public final int x, y, w, h;

        public Rect(int x, int y, int w, int h)
        {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(double px, double py)
        {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    RenderedPage(PageDocument document, BufferedImage image, double scale, int width, int height, int fullHeight, Map<Integer, List<Rect>> regions)
    {
        this.document = document;
        this.image = image;
        this.scale = scale;
        this.width = width;
        this.height = height;
        this.fullHeight = fullHeight;
        this.regions = Collections.unmodifiableMap(regions);
    }

    public boolean truncated()
    {
        return fullHeight > height;
    }

    public List<Rect> rects(int n)
    {
        List<Rect> r = regions.get(n);
        return r == null ? Collections.<Rect>emptyList() : r;
    }

    /** (x, y) 处最里层的编号元素；没有为 -1 */
    public int hit(double x, double y)
    {
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for(Map.Entry<Integer, List<Rect>> e : regions.entrySet())
        {
            for(Rect r : e.getValue())
            {
                if(r.contains(x, y))
                {
                    long area = (long) r.w * r.h;
                    if(area < bestArea)
                    {
                        bestArea = area;
                        best = e.getKey();
                    }
                }
            }
        }
        return best;
    }
}
