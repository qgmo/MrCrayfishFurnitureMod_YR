package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.tv.TvChannel;
import net.minecraft.client.renderer.texture.DynamicTexture;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频封面（悠然服务器魔改）：超出同时解码台数的电视显示封面。后台取视频信息与封面图，渲染线程建纹理。
 */
public final class CoverCache
{
    private static final int MAX_WIDTH = 480;

    private static final Map<String, Object> PENDING = new ConcurrentHashMap<>();
    /** 后台取到的图（等渲染线程建纹理） */
    private static final Map<String, BufferedImage> LOADED = new ConcurrentHashMap<>();
    /** 只在渲染线程用 */
    private static final Map<String, DynamicTexture> TEXTURES = new HashMap<>();
    private static final Map<String, int[]> SIZES = new HashMap<>();

    private CoverCache() {}

    /** 渲染线程：封面纹理，还没有时在后台开始取并返回 null */
    @Nullable
    public static DynamicTexture get(TvChannel channel)
    {
        String key = channel.key();
        DynamicTexture t = TEXTURES.get(key);
        if(t != null)
            return t;
        BufferedImage image = LOADED.remove(key);
        if(image != null)
        {
            t = new DynamicTexture(image);
            TEXTURES.put(key, t);
            SIZES.put(key, new int[]{image.getWidth(), image.getHeight()});
            return t;
        }
        if(PENDING.putIfAbsent(key, Boolean.TRUE) == null)
        {
            Thread th = new Thread(() ->
            {
                try
                {
                    BiliData.VideoInfo info = BiliApi.view(channel);
                    if(info.cover != null)
                    {
                        BufferedImage img = BiliApi.cover(info.cover, MAX_WIDTH);
                        if(img != null)
                            LOADED.put(key, img);
                    }
                }
                catch(BiliData.BiliException | RuntimeException e)
                {
                    // 没有封面就显示噪点
                }
            }, "cfm-tv-cover");
            th.setDaemon(true);
            th.start();
        }
        return null;
    }

    @Nullable
    public static int[] size(TvChannel channel)
    {
        return SIZES.get(channel.key());
    }

    /** 退出世界时释放 */
    public static void clear()
    {
        for(DynamicTexture t : TEXTURES.values())
            t.deleteGlTexture();
        TEXTURES.clear();
        SIZES.clear();
        LOADED.clear();
        PENDING.clear();
    }
}
