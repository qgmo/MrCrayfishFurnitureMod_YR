package com.mrcrayfish.furniture.web;

import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * 开发测试用的样例网站（悠然服务器魔改）：只在开发环境（未混淆）里、并且用 JVM 参数
 * {@code -Dcfm.browser.devSamples=<目录>} 打开时登记 {@code samples.yr}，把目录里的样例页面原样给浏览器看（排版检查用，
 * 与单元测试的排版检查用同一套样例：目录下每个子目录是一个网站，里面每个 {@code .html} 是一页）。
 * <ul>
 * <li>{@code /}：所有样例的链接；</li>
 * <li>{@code /网站/页面}：那一页。以 {@code <?xml} 开头的按 {@code application/xhtml+xml} 给，别的按 {@code text/html}。</li>
 * </ul>
 * 正式包里不生效（混淆环境、没设参数时什么都不做）；路径只认字母数字与 {@code ._-}，不出这个目录。
 */
public final class DevSamples
{
    static final String PROPERTY = "cfm.browser.devSamples";
    static final String DOMAIN = "samples.yr";

    private DevSamples() {}

    public static void registerIfEnabled()
    {
        String dir = System.getProperty(PROPERTY);
        Object deobf = Launch.blackboard == null ? null : Launch.blackboard.get("fml.deobfuscatedEnvironment");
        if(dir == null || dir.isEmpty() || !Boolean.TRUE.equals(deobf))
            return;
        File root = new File(dir);
        LogManager.getLogger("cfm").warn("Browser DEVELOPMENT samples: http://{}/ serves {}", DOMAIN, root.getAbsolutePath());
        Websites.register("cfm:dev-samples", DOMAIN, (request, context) -> CompletableFuture.completedFuture(handle(root, request.path)));
    }

    static WebResponse handle(File root, String path)
    {
        try
        {
            if(path.equals("/"))
                return WebResponse.xhtml(index(root));
            String[] parts = path.substring(1).split("/");
            if(parts.length != 2 || !safe(parts[0]) || !safe(parts[1]))
                return WebResponse.error(404, "Not Found");
            File f = new File(new File(root, parts[0]), parts[1].endsWith(".html") ? parts[1] : parts[1] + ".html");
            if(!f.isFile())
                return WebResponse.error(404, "Not Found");
            byte[] body = Files.readAllBytes(f.toPath());
            String head = new String(body, 0, Math.min(body.length, 64), StandardCharsets.UTF_8).trim();
            String type = head.startsWith("<?xml") ? WebResponse.XHTML : "text/html; charset=utf-8";
            return new WebResponse(200, "OK", type, body, Collections.<String, String>emptyMap());
        }
        catch(Exception e)
        {
            return WebResponse.error(500, "Internal Server Error");
        }
    }

    private static boolean safe(String s)
    {
        return !s.isEmpty() && !s.startsWith(".") && s.matches("[A-Za-z0-9._-]{1,80}");
    }

    private static String index(File root)
    {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>samples</title></head><body>\n");
        File[] sites = root.listFiles(File::isDirectory);
        if(sites != null)
        {
            Arrays.sort(sites);
            for(File site : sites)
            {
                if(!safe(site.getName()))
                    continue;
                b.append("<h3>").append(Html.escape(site.getName())).append("</h3>\n<p>");
                File[] pages = site.listFiles((d, n) -> n.endsWith(".html"));
                if(pages == null)
                    continue;
                Arrays.sort(pages);
                for(File p : pages)
                {
                    String name = p.getName().substring(0, p.getName().length() - 5);
                    if(safe(name))
                        b.append("<a href=\"/").append(Html.escape(site.getName())).append('/').append(Html.escape(name)).append("\">")
                                .append(Html.escape(name)).append("</a> ");
                }
                b.append("</p>\n");
            }
        }
        return b.append("</body></html>\n").toString();
    }
}
