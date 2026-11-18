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
 * 频道设置界面的搜索结果（服主 11-18）也用这里按地址取封面（{@link #byUrl}）：同时最多 {@value #URL_THREADS} 个在下载，
 * 纹理最多留 {@value #MAX_URL_TEXTURES} 张（最久没用的先删）。
 */
public final class CoverCache
{
    private static final int MAX_WIDTH = 480;
    /** 搜索结果的封面：宽 320 够用 */
    private static final int URL_MAX_WIDTH = 320;
    static final int URL_THREADS = 3;
    static final int MAX_URL_TEXTURES = 160;
    private static final java.util.concurrent.ExecutorService URL_POOL = java.util.concurrent.Executors.newFixedThreadPool(URL_THREADS, r ->
    {
        Thread t = new Thread(r, "cfm-tv-cover-url");
        t.setDaemon(true);
        return t;
    });
    /** 只在渲染线程用；按最近使用排序 */
    private static final java.util.LinkedHashMap<String, DynamicTexture> URL_TEXTURES = new java.util.LinkedHashMap<>(16, 0.75F, true);
    private static final Map<String, int[]> URL_SIZES = new HashMap<>();

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

    /** 渲染线程：按地址取封面纹理（地址要已经过 {@link HostPolicy} 的封面白名单），还没有时在后台开始取并返回 null */
    @Nullable
    public static DynamicTexture byUrl(String url)
    {
        String key = "url:" + url;
        DynamicTexture t = URL_TEXTURES.get(key);
        if(t != null)
            return t;
        BufferedImage image = LOADED.remove(key);
        if(image != null)
        {
            t = new DynamicTexture(image);
            URL_TEXTURES.put(key, t);
            URL_SIZES.put(key, new int[]{image.getWidth(), image.getHeight()});
            while(URL_TEXTURES.size() > MAX_URL_TEXTURES)
            {
                String oldest = URL_TEXTURES.keySet().iterator().next();
                URL_TEXTURES.remove(oldest).deleteGlTexture();
                URL_SIZES.remove(oldest);
                // 删掉的以后再要时重新下载
                PENDING.remove(oldest);
            }
            return t;
        }
        if(PENDING.putIfAbsent(key, Boolean.TRUE) == null)
        {
            URL_POOL.execute(() ->
            {
                BufferedImage img = BiliApi.cover(url, URL_MAX_WIDTH);
                if(img != null)
                    LOADED.put(key, img);
            });
        }
        return null;
    }

    @Nullable
    public static int[] sizeByUrl(String url)
    {
        return URL_SIZES.get("url:" + url);
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
        for(DynamicTexture t : URL_TEXTURES.values())
            t.deleteGlTexture();
        URL_TEXTURES.clear();
        URL_SIZES.clear();
        LOADED.clear();
        PENDING.clear();
    }
}
