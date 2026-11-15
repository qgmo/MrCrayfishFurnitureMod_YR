package com.mrcrayfish.furniture.util;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 服务端翻译（悠然服务器魔改，规则与 YouranAPI 的 Translations 相同）。同一个键按优先级从高到低查：
 * <ol>
 * <li>语言目录：服主放的 {@code .lang} 文件与资源包（文件夹或 zip，读其中的 {@code assets/*}{@code /lang/zh_cn.lang}）；
 * 目录里按文件名排序，同一个键以后面的为准；</li>
 * <li>模组 jar（或开发环境的文件夹）自带的 {@code zh_cn.lang}；</li>
 * <li>英文：模组 jar 与服务端自带的 {@code en_us.lang}；</li>
 * <li>都没有时调用方显示键名。</li>
 * </ol>
 * 只读 zh_cn：繁体玩家看到的仍由客户端按玩家的语言翻译，这里的中文只用于搜索、排序和没有翻译键的地方。
 * 对象建好之后只读，可以在线程间共享。
 */
public final class ServerTranslations
{
    private static final Pattern LANG_ENTRY = Pattern.compile("^assets/[^/]+/lang/(zh_cn|en_us)\\.lang$", Pattern.CASE_INSENSITIVE);
    private static final ServerTranslations EMPTY = new ServerTranslations(Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(), "no lang files loaded");

    private final Map<String, String> dir;
    private final Map<String, String> mods;
    private final Map<String, String> english;
    private final String summary;

    private ServerTranslations(Map<String, String> dir, Map<String, String> mods, Map<String, String> english, String summary)
    {
        this.dir = dir;
        this.mods = mods;
        this.english = english;
        this.summary = summary;
    }

    public static ServerTranslations empty()
    {
        return EMPTY;
    }

    /** 测试用：直接给出三层 */
    public static ServerTranslations of(Map<String, String> dir, Map<String, String> mods, Map<String, String> english)
    {
        return new ServerTranslations(new HashMap<>(dir), new HashMap<>(mods), new HashMap<>(english), "");
    }

    /** 中文（语言目录，其次模组自带）；没有时为 null */
    @Nullable
    public String local(String key)
    {
        String v = dir.get(key);
        return v != null ? v : mods.get(key);
    }

    @Nullable
    public String english(String key)
    {
        return english.get(key);
    }

    /** 中文 → 英文 → null（调用方显示键名） */
    @Nullable
    public String format(String key)
    {
        String v = local(key);
        return v != null ? v : english.get(key);
    }

    public boolean isEmpty()
    {
        return dir.isEmpty() && mods.isEmpty() && english.isEmpty();
    }

    /** 各来源的条数（日志用） */
    public String summary()
    {
        return summary;
    }

    /**
     * 加载。语言目录不存在时当作空的（不创建）。单个文件读不了时记一条警告、跳过，不影响其他来源。
     *
     * @param langDir 语言目录，可以是 null
     * @param mods    模组与服务端的 jar 或文件夹（读其中的 zh_cn.lang 与 en_us.lang）；按文件名排序
     * @param warn    警告日志
     */
    public static ServerTranslations load(@Nullable Path langDir, List<Path> mods, Consumer<String> warn)
    {
        Map<String, String> dir = new HashMap<>();
        int dirSources = 0;
        int dirEntries = 0;
        if(langDir != null && Files.isDirectory(langDir))
        {
            Map<String, Path> entries = new TreeMap<>();
            try(DirectoryStream<Path> ds = Files.newDirectoryStream(langDir))
            {
                for(Path p : ds)
                    entries.put(p.getFileName().toString(), p);
            }
            catch(IOException | RuntimeException e)
            {
                warn.accept("cannot list lang directory " + langDir + ": " + e);
            }
            for(Map.Entry<String, Path> e : entries.entrySet())
            {
                Path p = e.getValue();
                String lower = e.getKey().toLowerCase(Locale.ROOT);
                try
                {
                    int n;
                    if(Files.isDirectory(p))
                        n = readPackDir(p, dir);
                    else if(lower.endsWith(".lang"))
                        n = readFile(p, dir);
                    else if(lower.endsWith(".zip") || lower.endsWith(".jar"))
                        n = readZip(p, dir, null)[0];
                    else
                        continue;
                    dirSources++;
                    dirEntries += n;
                }
                catch(IOException | RuntimeException ex)
                {
                    warn.accept("cannot read " + p + ", skipped: " + ex);
                }
            }
        }

        Map<String, String> zh = new HashMap<>();
        Map<String, String> en = new HashMap<>();
        Map<String, Path> sorted = new TreeMap<>();
        for(Path j : mods)
        {
            Path norm = j.toAbsolutePath().normalize();
            sorted.put(norm.getFileName() + "|" + norm, norm);
        }
        int modEntries = 0;
        for(Path j : sorted.values())
        {
            try
            {
                if(Files.isDirectory(j))
                {
                    modEntries += readPackDir(j, zh);
                    readPackDirEnglish(j, en);
                }
                else if(Files.isRegularFile(j))
                {
                    int[] c = readZip(j, zh, en);
                    modEntries += c[0];
                }
            }
            catch(IOException | RuntimeException ex)
            {
                warn.accept("cannot read lang files of " + j.getFileName() + ", skipped: " + ex);
            }
        }
        String summary = "lang dir " + langDir + ": " + dirSources + " sources, " + dirEntries + " entries; mods zh_cn " + modEntries + " entries; en_us " + en.size() + " entries";
        return new ServerTranslations(dir, zh, en, summary);
    }

    private static int readFile(Path p, Map<String, String> into) throws IOException
    {
        try(InputStream in = Files.newInputStream(p))
        {
            Map<String, String> m = LangFile.read(in);
            into.putAll(m);
            return m.size();
        }
    }

    /** 资源包文件夹：读 assets/域/lang/zh_cn.lang（按域名排序） */
    private static int readPackDir(Path pack, Map<String, String> into) throws IOException
    {
        return readPackDir(pack, "zh_cn.lang", into);
    }

    private static void readPackDirEnglish(Path pack, Map<String, String> into) throws IOException
    {
        readPackDir(pack, "en_us.lang", into);
    }

    private static int readPackDir(Path pack, String fileName, Map<String, String> into) throws IOException
    {
        Path assets = pack.resolve("assets");
        if(!Files.isDirectory(assets))
            return 0;
        Map<String, Path> files = new TreeMap<>();
        try(Stream<Path> domains = Files.list(assets))
        {
            for(Path d : (Iterable<Path>) domains::iterator)
            {
                Path lang = d.resolve("lang");
                if(!Files.isDirectory(lang))
                    continue;
                try(Stream<Path> fs = Files.list(lang))
                {
                    for(Path f : (Iterable<Path>) fs::iterator)
                    {
                        if(f.getFileName().toString().equalsIgnoreCase(fileName) && Files.isRegularFile(f))
                            files.put(d.getFileName().toString(), f);
                    }
                }
            }
        }
        int n = 0;
        for(Path f : files.values())
        {
            try
            {
                n += readFile(f, into);
            }
            catch(IOException | RuntimeException e)
            {
                // 单个文件坏了不影响其他域
            }
        }
        return n;
    }

    /** zip / jar：读 assets/域/lang/zh_cn.lang（按条目名排序）；english 不为 null 时同时读 en_us.lang。返回 {中文条数, 英文条数} */
    private static int[] readZip(Path zip, Map<String, String> zh, @Nullable Map<String, String> english) throws IOException
    {
        int[] counts = new int[2];
        try(ZipFile z = new ZipFile(zip.toFile()))
        {
            Map<String, ZipEntry> entries = new TreeMap<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while(en.hasMoreElements())
            {
                ZipEntry e = en.nextElement();
                if(!e.isDirectory() && LANG_ENTRY.matcher(e.getName()).matches())
                    entries.put(e.getName(), e);
            }
            for(ZipEntry e : entries.values())
            {
                boolean isZh = e.getName().toLowerCase(Locale.ROOT).endsWith("/zh_cn.lang");
                if(!isZh && english == null)
                    continue;
                try(InputStream in = z.getInputStream(e))
                {
                    Map<String, String> m = LangFile.read(in);
                    if(isZh)
                    {
                        zh.putAll(m);
                        counts[0] += m.size();
                    }
                    else
                    {
                        english.putAll(m);
                        counts[1] += m.size();
                    }
                }
                catch(IOException | RuntimeException ex)
                {
                    // 压缩包里单个语言文件坏了：跳过它，其余照读
                }
            }
        }
        return counts;
    }
}
