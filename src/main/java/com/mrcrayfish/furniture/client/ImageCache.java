package com.mrcrayfish.furniture.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Author: MrCrayfish
 */
@SideOnly(Side.CLIENT)
public final class ImageCache
{
    public static final ImageCache INSTANCE = new ImageCache();

    private final File cache;
    private Map<String, Texture> cacheMap = new HashMap<>();

    private ImageCache()
    {
        cache = new File(Minecraft.getMinecraft().gameDir, "photo-frame-cache");
        cache.mkdir();
        this.init();
    }

    private void init()
    {
        try
        {
            FileUtils.writeStringToFile(new File(cache, "!read-me.txt"), "This is a cache for GIFs that are played on the TV (in MrCrayfish's Furniture Mod) in order to speed up load time.\nIt is safe to delete the entire folder in case you are running out of space, however it will mean that all GIFs will have to be downloaded again.", "UTF-8");
        }
        catch(IOException e)
        {
            e.printStackTrace();
        }
    }

    @Nullable
    public Texture get(String url)
    {
        if(url == null)
            return null;

        synchronized(this)
        {
            Texture texture = cacheMap.get(url);
            if(texture != null)
            {
                return texture;
            }
        }
        return null;
    }

    /**
     * 放入已解码好的纹理（工作线程调用）。
     */
    public void put(String url, Texture texture)
    {
        synchronized(this)
        {
            cacheMap.put(url, texture);
        }
    }

    private File fileFor(String url)
    {
        return new File(getCache(), DigestUtils.sha1Hex(url.getBytes()));
    }

    /**
     * 读磁盘缓存（工作线程调用）；没有缓存或文件超过下载上限时返回 null。
     */
    @Nullable
    public byte[] readCachedFile(String url)
    {
        File file = fileFor(url);
        if(!file.isFile() || file.length() > MediaDownloader.MAX_FILE_SIZE)
            return null;
        try
        {
            return FileUtils.readFileToByteArray(file);
        }
        catch(IOException e)
        {
            return null;
        }
    }

    /**
     * 写磁盘缓存（工作线程调用，不持有锁，不挡渲染线程）。
     */
    public void writeCachedFile(String url, byte[] data)
    {
        try
        {
            FileUtils.writeByteArrayToFile(fileFor(url), data);
        }
        catch(IOException ignored) {}
    }

    private void tick()
    {
        synchronized(this)
        {
            cacheMap.values().forEach(Texture::update);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event)
    {
        if(event.phase == TickEvent.Phase.START)
        {
            this.tick();
        }
    }

    public boolean isCached(String url)
    {
        synchronized(this)
        {
            return cacheMap.containsKey(url);
        }
    }

    public File getCache()
    {
        cache.mkdir();
        return cache;
    }
}
