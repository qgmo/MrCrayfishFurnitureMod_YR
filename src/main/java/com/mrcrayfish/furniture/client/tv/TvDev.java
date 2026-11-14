package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发测试用的开关（悠然服务器魔改），只能用 JVM 参数设置，配置文件、服务器都改不了：
 * <ul>
 * <li>{@code -Dcfm.tv.dev=http://127.0.0.1:端口}：bilibili 接口、本地库下载都改去这个本机地址（模拟接口），
 * 并放行 127.0.0.1 的 http；</li>
 * <li>{@code -Dcfm.tv.devPlatform=平台名}：假装是别的平台（例如 {@code windows-x86} 测“不支持”）。</li>
 * </ul>
 */
public final class TvDev
{
    private static final String BASE = clean(System.getProperty("cfm.tv.dev"));
    private static boolean warned;

    private TvDev() {}

    @Nullable
    private static String clean(@Nullable String base)
    {
        if(base == null || !base.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}"))
            return null;
        return base;
    }

    public static boolean enabled()
    {
        if(BASE != null && !warned)
        {
            warned = true;
            MrCrayfishFurnitureMod.logger().warn("TV video DEVELOPMENT mode: bilibili API and library downloads go to {}", BASE);
        }
        return BASE != null;
    }

    /** 接口地址（正式：https://api.bilibili.com） */
    public static String apiBase()
    {
        return enabled() ? BASE + "/api" : "https://api.bilibili.com";
    }

    /** 弹幕地址（正式：https://comment.bilibili.com） */
    public static String commentBase()
    {
        return enabled() ? BASE + "/comment" : "https://comment.bilibili.com";
    }

    public static List<String> librarySources(List<String> real)
    {
        if(!enabled())
            return real;
        List<String> list = new ArrayList<>();
        list.add(BASE + "/dl-yr/maven/");
        list.add(BASE + "/mirror/");
        list.add(BASE + "/central/");
        return list;
    }

    @Nullable
    public static String platformOverride()
    {
        String p = System.getProperty("cfm.tv.devPlatform");
        return p == null || p.isEmpty() ? null : p;
    }
}
