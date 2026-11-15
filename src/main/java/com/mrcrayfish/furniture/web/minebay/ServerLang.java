package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.handler.ConfigurationHandler;
import com.mrcrayfish.furniture.util.ServerTranslations;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MineBay 服务端用的翻译（见 {@link ServerTranslations}）：在后台线程加载，加载完成前用空表（名字回到内置的中文名与英文名），
 * 完成后换成只读的新表并让商品清单重建。
 */
public final class ServerLang
{
    private static final AtomicInteger VERSION = new AtomicInteger();
    private static volatile ServerTranslations current = ServerTranslations.empty();
    private static int generation;

    private ServerLang()
    {
    }

    static ServerTranslations get()
    {
        return current;
    }

    /** 加载好的次数；商品清单的缓存据此重建 */
    static int version()
    {
        return VERSION.get();
    }

    /** 语言目录：配置项留空时与 YouranAPI 的默认一致（{@code config/youranapi/lang}）；相对路径相对于服务器目录 */
    static Path langDir()
    {
        File config = Loader.instance().getConfigDir();
        String set = ConfigurationHandler.minebayLangDir.trim();
        if(set.isEmpty())
            return config.toPath().resolve("youranapi").resolve("lang");
        Path p = Paths.get(set);
        return p.isAbsolute() ? p : config.getParentFile().toPath().resolve(p);
    }

    /** 开始（重新）加载。可以在主线程上调用，读磁盘在后台线程上 */
    public static synchronized void start()
    {
        final int mine = ++generation;
        final Path dir = langDir();
        final List<Path> sources = new ArrayList<>();
        for(ModContainer m : Loader.instance().getModList())
        {
            File f = m.getSource();
            if(f != null && f.exists())
                sources.add(f.toPath());
        }
        Thread t = new Thread(() ->
        {
            try
            {
                ServerTranslations loaded = ServerTranslations.load(dir, sources, msg -> MrCrayfishFurnitureMod.logger().warn("MineBay lang: {}", msg));
                synchronized(ServerLang.class)
                {
                    if(mine != generation)
                        return;
                    current = loaded;
                    VERSION.incrementAndGet();
                }
                MrCrayfishFurnitureMod.logger().info("MineBay lang: {}", loaded.summary());
            }
            catch(Throwable e)
            {
                MrCrayfishFurnitureMod.logger().warn("MineBay lang: loading failed, using built-in names only: {}", e.toString());
            }
        }, "cfm-minebay-lang");
        t.setDaemon(true);
        t.start();
    }
}
