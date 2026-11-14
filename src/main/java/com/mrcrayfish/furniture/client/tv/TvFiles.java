package com.mrcrayfish.furniture.client.tv;

import net.minecraft.client.Minecraft;

import java.io.File;

/**
 * 电视视频用到的本地文件都放在游戏目录的 {@code cfm-tv/} 下（悠然服务器魔改）：下载的本地库 {@code lib/}、
 * JavaCPP 解压出来的本地库 {@code native-cache/}、频道设置的草稿 {@code drafts.json}。不写系统目录，不缓存视频内容。
 */
public final class TvFiles
{
    private TvFiles() {}

    public static File root()
    {
        File dir = new File(Minecraft.getMinecraft().gameDir, "cfm-tv");
        if(!dir.isDirectory())
            dir.mkdirs();
        return dir;
    }
}
