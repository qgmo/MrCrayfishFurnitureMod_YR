package com.mrcrayfish.furniture.client.browser;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 排版检查工具（{@code gradlew browserLayoutShots -PlayoutSamples=样例目录 -PlayoutShots=出图目录}）：
 * 先画仓库里自带的样例（浏览器自己的页面、MineBay、照各网站写法做的几页），再画样例目录里的（写 {@code -} 为不要）：
 * 样例目录下每个子目录是一个网站（目录名是主机名，不带点的补上 {@code .yr}），里面每个 {@code .html} 是一页；
 * 每页渲染成 PNG（与游戏里画在屏幕上的是同一张图），再做 {@link LayoutProbe} 的检查，问题写进出图目录的 {@code report.txt}。
 */
public final class LayoutShots
{
    private LayoutShots() {}

    public static void main(String[] args) throws Exception
    {
        if(args.length < 2)
        {
            System.err.println("用法：LayoutShots 样例目录 出图目录 [倍数]");
            System.exit(2);
        }
        File samples = new File(args[0]);
        File out = new File(args[1]);
        double scale = args.length > 2 ? Double.parseDouble(args[2]) : 1.5;
        if(!out.isDirectory() && !out.mkdirs())
            throw new IOException("建不了 " + out);
        int pages = 0, bad = 0;
        try(PrintStream report = new PrintStream(new File(out, "report.txt"), "UTF-8"))
        {
            // 仓库里自带的样例（浏览器自己的页面、MineBay、照各网站写法做的几页），再加上样例目录里的
            Map<String, byte[]> all = new java.util.LinkedHashMap<>();
            for(Map.Entry<String, byte[]> e : BrowserLayoutTest.allSamples().entrySet())
            {
                String url = BrowserLayoutTest.url(e.getKey());
                String host = url.startsWith("http://localhost/") ? "builtin" : url.startsWith("https://minebay.yr/") ? "minebay" : "patterns";
                all.put(host + "\n" + url, e.getValue());
            }
            File[] sites = samples.getName().equals("-") ? new File[0] : samples.listFiles(File::isDirectory);
            if(sites == null)
                throw new IOException("没有样例目录 " + samples);
            Arrays.sort(sites);
            for(File site : sites)
            {
                File[] files = site.listFiles((d, n) -> n.endsWith(".html"));
                if(files == null)
                    continue;
                Arrays.sort(files);
                String host = site.getName().contains(".") ? site.getName() : site.getName() + ".yr";
                for(File f : files)
                    all.put(site.getName() + "\nhttp://" + host + "/" + f.getName().substring(0, f.getName().length() - 5), Files.readAllBytes(f.toPath()));
            }
            for(Map.Entry<String, byte[]> e : all.entrySet())
            {
                String dir = e.getKey().substring(0, e.getKey().indexOf('\n'));
                String url = e.getKey().substring(dir.length() + 1);
                String name = url.substring(url.lastIndexOf('/') + 1);
                pages++;
                List<LayoutProbe.Problem> problems;
                try
                {
                    LayoutProbe.Result r = BrowserLayoutTest.check(e.getValue(), url, scale);
                    LayoutSamples.save(r.page, new File(out, dir + "/" + name + ".png"));
                    problems = r.problems;
                }
                catch(Exception ex)
                {
                    problems = new ArrayList<>();
                    problems.add(new LayoutProbe.Problem(null, "整页", "渲染失败：" + ex));
                }
                if(!problems.isEmpty())
                    bad++;
                report.println(String.format(Locale.ROOT, "== %s/%s（%d 个问题）", dir, name, problems.size()));
                for(LayoutProbe.Problem p : problems)
                    report.println("  " + p);
            }
            report.println();
            report.println(pages + " 页，有问题的 " + bad + " 页");
        }
        System.out.println(pages + " 页，有问题的 " + bad + " 页；见 " + new File(out, "report.txt"));
    }
}
