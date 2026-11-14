package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FFmpeg 本地库（悠然服务器魔改）：不打进模组 jar，玩家第一次用到视频频道时按平台下载 JavaCPP 与 FFmpeg（LGPL 版）
 * 的本地库 jar，按写死在这里的 SHA-256 校验后才加载。每次启动加载前都再校验一遍。
 * <p>
 * 下载源依次是服主自己的源、阿里云的 Maven 镜像、Maven Central；路径都是 Maven 仓库的布局。
 * 加载：把两个 jar 加进 Forge 的类加载器（JavaCPP 从类路径里找本地库），本地库解压到游戏目录的 {@code cfm-tv/native-cache}。
 * 加载失败（例如 glibc 太老、32 位系统、没有这个平台的库）时标成“不支持”，GIF 不受影响。
 */
public final class NativeLibs
{
    public static final String JAVACPP_VERSION = "1.5.14";
    public static final String FFMPEG_VERSION = "8.1.2-" + JAVACPP_VERSION;

    /** 下载源（末尾带 /，后面接 Maven 路径） */
    public static final List<String> SOURCES = Collections.unmodifiableList(java.util.Arrays.asList(
            "https://dl-yr.qg.mo.cn/maven/",
            "https://maven.aliyun.com/repository/central/",
            "https://repo1.maven.org/maven2/"));

    /** 一个要下载的文件 */
    public static final class LibFile
    {
        public final String path;
        public final String sha256;
        public final long size;

        LibFile(String path, String sha256, long size)
        {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
        }

        public String fileName()
        {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /** 各平台要下的两个 jar（SHA-256 与大小是 2026-11-14 从 Maven Central 下载后算的，与其 .sha1 核对过） */
    static final Map<String, List<LibFile>> FILES = new LinkedHashMap<>();

    static
    {
        add("linux-x86_64",
                "1efab617735a44529bb85daaaefabe46c8686582989c46593c36569e9786fdaa", 47603,
                "6d0f000c4ddede3b669aa7c5c585e9b71f69b7f621fed7ab28ec01045dc336d0", 26915834);
        add("linux-arm64",
                "30f63f17bb05cba4bdf488b77a3d9785b2ec261d4d528cf219b6c7081d20d636", 42779,
                "c8729978c862b0e2e5643ade1b41266c5ba8c34b791f41104e5794ad3cefd0bf", 26304735);
        add("macosx-x86_64",
                "ce2e642c0317d08f08fc97ba44b2c18d2a22a85516fdec6b9854f3a930c5127b", 39995,
                "b5a8f5124fb2d04412bd01eb5ca469d153d1c396fd0c67a33d3f9a1501c94c0c", 23977215);
        add("macosx-arm64",
                "75d755656d3ddafaa51c5d2d8d340c731a0bf2d742b033a37641a905aa2a7332", 38712,
                "af57468c0bb7b2e9c8c93547e6d599e2b4faa4117118555be92675850972420c", 20582183);
        add("windows-x86_64",
                "69bb4b0322aa807199485a9bf394ffe2117bb9ef5c34762b7d6567d7053087b5", 2031831,
                "67ebc3e6940add83b1b8a9dfd337a5c67556f2b72cb3225efe91a26874445c8e", 30441678);
    }

    private static void add(String platform, String javacppSha, long javacppSize, String ffmpegSha, long ffmpegSize)
    {
        List<LibFile> list = new ArrayList<>();
        list.add(new LibFile("org/bytedeco/javacpp/" + JAVACPP_VERSION + "/javacpp-" + JAVACPP_VERSION + "-" + platform + ".jar", javacppSha, javacppSize));
        list.add(new LibFile("org/bytedeco/ffmpeg/" + FFMPEG_VERSION + "/ffmpeg-" + FFMPEG_VERSION + "-" + platform + ".jar", ffmpegSha, ffmpegSize));
        FILES.put(platform, Collections.unmodifiableList(list));
    }

    public enum State
    {
        NOT_STARTED, DOWNLOADING, LOADING, READY, FAILED, UNSUPPORTED
    }

    private static volatile State state = State.NOT_STARTED;
    private static volatile long progressDone;
    private static volatile long progressTotal;
    private static volatile String failure = "";
    private static volatile long failedAt;

    private NativeLibs() {}

    /**
     * JavaCPP 的平台名（与它自己的判断一致），没有本地库的平台返回 null。
     */
    @Nullable
    public static String platform(@Nullable String osName, @Nullable String osArch)
    {
        if(osName == null || osArch == null)
            return null;
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        String o;
        if(os.startsWith("windows"))
            o = "windows";
        else if(os.startsWith("mac") || os.startsWith("darwin"))
            o = "macosx";
        else if(os.startsWith("linux"))
            o = "linux";
        else
            return null;
        String a;
        if(arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x86-64") || arch.equals("em64t") || arch.equals("x64"))
            a = "x86_64";
        else if(arch.equals("aarch64") || arch.equals("arm64"))
            a = "arm64";
        else
            return null;
        String p = o + "-" + a;
        return FILES.containsKey(p) ? p : null;
    }

    @Nullable
    static List<LibFile> filesFor(@Nullable String platform)
    {
        return platform == null ? null : FILES.get(platform);
    }

    public static State getState()
    {
        return state;
    }

    /** 下载进度 0–1 */
    public static float progress()
    {
        long total = progressTotal;
        return total <= 0 ? 0 : Math.min(1F, progressDone / (float) total);
    }

    /** 下载失败、不支持时的说明（日志用，界面上用语言键） */
    public static String getFailure()
    {
        return failure;
    }

    /** 要用视频时调：还没开始（或下载失败过 60 秒）就在后台开始下载、加载。返回是否已经能用 */
    public static synchronized boolean ensure()
    {
        if(state == State.READY)
            return true;
        if(state == State.NOT_STARTED || (state == State.FAILED && System.currentTimeMillis() - failedAt > 60_000L))
        {
            state = State.DOWNLOADING;
            Thread t = new Thread(NativeLibs::prepare, "cfm-tv-natives");
            t.setDaemon(true);
            t.start();
        }
        return false;
    }

    private static void prepare()
    {
        try
        {
            String platform = TvDev.platformOverride();
            if(platform == null)
                platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
            List<LibFile> files = filesFor(platform);
            if(files == null)
            {
                unsupported("no FFmpeg native libraries for " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
                return;
            }
            File dir = new File(TvFiles.root(), "lib");
            if(!dir.isDirectory() && !dir.mkdirs())
                throw new IOException("cannot create " + dir);
            long total = 0;
            for(LibFile f : files)
                total += f.size;
            progressTotal = total;
            progressDone = 0;
            List<File> ready = new ArrayList<>();
            for(LibFile f : files)
            {
                File file = new File(dir, f.fileName());
                if(file.isFile() && file.length() == f.size && f.sha256.equals(sha256(file)))
                {
                    progressDone += f.size;
                }
                else
                {
                    Files.deleteIfExists(file.toPath());
                    download(f, file);
                }
                ready.add(file);
            }
            state = State.LOADING;
            load(ready);
            state = State.READY;
            MrCrayfishFurnitureMod.logger().info("TV video: FFmpeg {} loaded for {}", FFMPEG_VERSION, platform);
        }
        catch(IOException e)
        {
            failure = e.getMessage();
            failedAt = System.currentTimeMillis();
            state = State.FAILED;
            MrCrayfishFurnitureMod.logger().warn("TV video: could not download FFmpeg native libraries: {}", e.getMessage());
        }
        catch(Throwable t)
        {
            // 加载本地库失败（UnsatisfiedLinkError 等）：这台电脑不支持，不再重试
            unsupported(t.toString());
        }
    }

    private static void unsupported(String why)
    {
        failure = why;
        state = State.UNSUPPORTED;
        MrCrayfishFurnitureMod.logger().warn("TV video is not supported on this computer: {}", why);
    }

    /** 依次从各个源下载，边下边算 SHA-256，大小、哈希都对上才改名为正式文件 */
    private static void download(LibFile f, File target) throws IOException
    {
        File part = new File(target.getPath() + ".part");
        long base = progressDone;
        StringBuilder errors = new StringBuilder();
        for(String source : TvDev.librarySources(SOURCES))
        {
            progressDone = base;
            try
            {
                URI uri = URI.create(source + f.path);
                try(TvHttp.Response res = TvHttp.get(uri, HostPolicy.Purpose.LIBRARY, null, -1, -1, f.size, 20_000))
                {
                    if(res.status != 200)
                        throw new IOException("HTTP " + res.status);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    long n = 0;
                    try(InputStream in = res.body(); OutputStream out = Files.newOutputStream(part.toPath()))
                    {
                        byte[] buf = new byte[64 * 1024];
                        int k;
                        while((k = in.read(buf)) > 0)
                        {
                            n += k;
                            if(n > f.size)
                                throw new IOException("larger than expected");
                            md.update(buf, 0, k);
                            out.write(buf, 0, k);
                            progressDone = base + n;
                        }
                    }
                    if(n != f.size)
                        throw new IOException("size " + n + ", expected " + f.size);
                    String hash = hex(md.digest());
                    if(!hash.equals(f.sha256))
                        throw new IOException("SHA-256 mismatch");
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            catch(IOException | NoSuchAlgorithmException | IllegalArgumentException e)
            {
                Files.deleteIfExists(part.toPath());
                errors.append(' ').append(source).append(": ").append(e.getMessage()).append(';');
            }
        }
        throw new IOException(f.fileName() + " from all sources failed:" + errors);
    }

    /** 加进类加载器，触发 JavaCPP 加载 FFmpeg（失败抛错） */
    private static void load(List<File> jars) throws Exception
    {
        File cache = new File(TvFiles.root(), "native-cache");
        if(!cache.isDirectory() && !cache.mkdirs())
            throw new IOException("cannot create " + cache);
        System.setProperty("org.bytedeco.javacpp.cachedir", cache.getAbsolutePath());
        ClassLoader loader = NativeLibs.class.getClassLoader();
        Method addUrl = findAddUrl(loader.getClass());
        if(addUrl == null)
            throw new IllegalStateException("class loader " + loader.getClass().getName() + " cannot add URLs");
        addUrl.setAccessible(true);
        for(File jar : jars)
        {
            URL url = jar.toURI().toURL();
            addUrl.invoke(loader, url);
        }
        FfmpegBridge.load();
    }

    @Nullable
    private static Method findAddUrl(Class<?> type)
    {
        for(Class<?> c = type; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredMethod("addURL", URL.class);
            }
            catch(NoSuchMethodException e)
            {
                // 往父类找
            }
        }
        return null;
    }

    static String sha256(File file) throws IOException
    {
        try(InputStream in = new FileInputStream(file))
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int k;
            while((k = in.read(buf)) > 0)
                md.update(buf, 0, k);
            return hex(md.digest());
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IOException(e);
        }
    }

    static String hex(byte[] d)
    {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for(byte b : d)
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }
}
