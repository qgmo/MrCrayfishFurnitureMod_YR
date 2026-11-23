package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.web.minebay.MineBayLayoutSamples;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 浏览器排版：页面渲染以后没有字伸出盒子、伸出页面，同一行的按钮、输入框一样高、上下对齐（{@link LayoutProbe}）。
 * 样例：浏览器自己的页面、MineBay 的各页（名字挑长的），和照各游戏内网站写法做的几页（{@code browser-layout/*.html}：
 * 定宽的框、信息表格里的长词、深色标题栏里的退出按钮、搜索框与按钮、表单表格、代码块、比页面宽的图）。
 * 排版只看页面宽（固定 {@value PageRenderer#VIEW_WIDTH}），与窗口大小无关；窗口大小只改画成图的倍数，两种倍数都查。
 */
class BrowserLayoutTest
{
    /** 电脑界面在默认窗口（854×480，界面倍数 2）与全屏（1920×1080，倍数 4）时画页面用的倍数（见 GuiBrowser#layout） */
    static final double[] SCALES = {1.34, 2.5};

    static final String[] PATTERNS = {"fixed-width-box", "info-table-long-words", "header-bar-logout", "search-row", "form-table",
            "pre-and-long-text", "wide-images"};

    static byte[] resource(String name) throws IOException
    {
        try(InputStream in = BrowserLayoutTest.class.getResourceAsStream("/browser-layout/" + name + ".html"))
        {
            assertNotNull(in, name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while((n = in.read(b)) > 0)
                out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    static LayoutProbe.Result check(byte[] html, String url, double scale) throws Exception
    {
        PageDocument doc = LayoutSamples.parse(html, url);
        Map<String, byte[]> images = LayoutSamples.placeholders(doc);
        if(url.endsWith("/image"))
            images.put("https://minebay.yr/big.png", LayoutSamples.png(1000, 300));
        if(url.endsWith("/wide-images"))
            images.put("http://exp.yr/plain.png", LayoutSamples.png(900, 120));
        return LayoutProbe.check(doc, images, scale);
    }

    static Map<String, byte[]> allSamples() throws Exception
    {
        Map<String, byte[]> all = new LinkedHashMap<>();
        for(Map.Entry<String, byte[]> e : BuiltinLayoutSamples.pages().entrySet())
            all.put("about:" + e.getKey(), e.getValue());
        for(Map.Entry<String, String> e : MineBayLayoutSamples.pages().entrySet())
            all.put("https://minebay.yr/" + e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        for(String p : PATTERNS)
            all.put("http://exp.yr/" + p, resource(p));
        return all;
    }

    static String url(String key)
    {
        return key.startsWith("about:") ? "http://localhost/" + key.substring(6) : key;
    }

    @Test
    void noLayoutProblemsOnAnySample() throws Exception
    {
        List<String> failures = new ArrayList<>();
        for(Map.Entry<String, byte[]> e : allSamples().entrySet())
        {
            for(double scale : SCALES)
            {
                LayoutProbe.Result r = check(e.getValue(), url(e.getKey()), scale);
                for(LayoutProbe.Problem p : r.problems)
                    failures.add(e.getKey() + " @" + scale + "：" + p);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * 格子里断开的长英文词：按游戏里画页面的倍数（电脑屏幕、全屏）画出来也不压到格子右边框。
     * 内核在倍数 1 下量字宽、在放大的图上画字，字形取整不同时长串字会画得比量的宽（修之前全屏时超出 2–6 CSS 像素）。
     */
    @Test
    void brokenLongWordsAreDrawnInsideTheirCell() throws Exception
    {
        byte[] html = ("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style type=\"text/css\">"
                + "table { border-collapse: collapse; width: 100%; } th { width: 90px; } td, th { border: 1px solid #000; padding: 2px 6px; font-size: 14px; }"
                + "</style></head><body><table><tr><th>内容</th><td>附魔钻石剑（锋利 V、耐久 III、经验修补）×1；"
                + "Supercalifragilisticexpialidocious_Enchanted_Diamond_Sword_of_Doom_Supercalifragilisticexpialidocious_Enchanted ×2</td></tr>"
                + "<tr><th>网站</th><td><b>averyveryverylongdomainnamewithoutanybreaksaveryveryverylongdomainnamewithoutanybreaks.yr</b></td></tr>"
                + "</table></body></html>").getBytes(StandardCharsets.UTF_8);
        byte[] cart = MineBayLayoutSamples.pages().get("cart").getBytes(StandardCharsets.UTF_8);
        for(double scale : new double[]{1.0, 1.34, 1.5, 2.0, 2.5, 3.0})
        {
            LayoutProbe.Result r = check(html, "http://exp.yr/cell", scale);
            assertTrue(r.problems.isEmpty(), scale + "：" + r.problems);
            // 购物车：格子里的长商品名、右对齐的价格（修之前全屏时画出界 2–6 像素）
            LayoutProbe.Result c = check(cart, "https://minebay.yr/cart", scale);
            assertTrue(c.problems.isEmpty(), scale + "：" + c.problems);
        }
    }

    /**
     * “Silk Touch”与后面的“ I”之间的空格画得出来（修之前画出来的字比排版量的宽，把空格吃掉，看起来是“Silk TouchI”）：
     * 排版里空格那一段的像素全是白的。
     */
    @Test
    void spaceAfterInlineElementIsVisible() throws Exception
    {
        byte[] html = MineBayLayoutSamples.pages().get("item-en").getBytes(StandardCharsets.UTF_8);
        for(double scale : new double[]{1.34, 2.5})
        {
            PageDocument doc = LayoutSamples.parse(html, "https://minebay.yr/item-en");
            int[] gap = new int[4];
            RenderedPage page = PageRenderer.render(doc, new HashMap<>(), scale, (r, root) ->
            {
                List<com.openhtmltopdf.render.Box> spans = new ArrayList<>();
                find(root, "span", spans);
                for(com.openhtmltopdf.render.Box b : spans)
                {
                    if(b instanceof com.openhtmltopdf.render.InlineLayoutBox && "Silk Touch".equals(b.getElement().getTextContent()))
                    {
                        com.openhtmltopdf.render.InlineLayoutBox span = (com.openhtmltopdf.render.InlineLayoutBox) b;
                        List<Object[]> texts = new ArrayList<>();
                        texts(root, texts);
                        for(Object[] t : texts)
                        {
                            com.openhtmltopdf.render.InlineText it = (com.openhtmltopdf.render.InlineText) t[0];
                            com.openhtmltopdf.render.InlineLayoutBox p = (com.openhtmltopdf.render.InlineLayoutBox) t[1];
                            if(it.getSubstring().startsWith(" I") && p.getAbsY() == span.getAbsY())
                            {
                                // 空格在“ I”这段字的开头：从“Silk Touch”排版的右边起，空格至少 3 像素宽（14px 字的空格约 3.6）
                                gap[0] = span.getAbsX() + span.getInlineWidth();
                                gap[1] = p.getAbsX() + it.getX() + 3;
                                gap[2] = span.getAbsY();
                                gap[3] = span.getHeight();
                            }
                        }
                    }
                }
            });
            assertTrue(gap[1] > gap[0], "找到了空格");
            // 空格那一段（左右各让 1 个图像素给抗锯齿）里没有字的笔画
            for(int x = (int) Math.ceil(gap[0] * scale) + 1; x < (int) (gap[1] * scale) - 1; x++)
            {
                for(int y = (int) (gap[2] * scale); y < (int) ((gap[2] + gap[3]) * scale); y++)
                    assertTrue((page.image.getRGB(x, y) & 0xFF) > 0xC0, scale + "：空格处 (" + x + ", " + y + ") 有笔画");
            }
        }
    }

    /** MineBay 的搜索按钮没有上外边距（.go 的上外边距只给结算区的 div；以前也管到了 .btn.go，搜索按钮比搜索框低 6 像素） */
    @Test
    void mineBaySearchButtonHasNoTopMargin() throws Exception
    {
        byte[] html = MineBayLayoutSamples.pages().get("list").getBytes(StandardCharsets.UTF_8);
        PageDocument doc = LayoutSamples.parse(html, "https://minebay.yr/");
        LayoutProbe p = new LayoutProbe(PageRenderer.VIEW_WIDTH);
        int[] margin = {-1};
        PageRenderer.render(doc, new HashMap<>(), 1.0, (r, root) ->
        {
            p.inspect(r, root);
            List<com.openhtmltopdf.render.Box> buttons = new ArrayList<>();
            find(root, "button", buttons);
            for(com.openhtmltopdf.render.Box b : buttons)
            {
                if(b.getElement().getAttribute("class").equals("btn go") && b.getElement().getTextContent().equals("搜索"))
                    margin[0] = p.marginTop(b);
            }
        }, 0);
        assertEquals(0, margin[0]);
    }

    /** 文本框的 size：约 N 个字宽；没写的默认 160；网站写了宽度的按网站的 */
    @Test
    void inputSizeAttributeSetsWidth() throws Exception
    {
        byte[] html = ("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style type=\"text/css\">input.q { width: 250px; }</style></head><body>"
                + "<form action=\"/\"><div><input type=\"text\" name=\"a\" size=\"5\" /> <input type=\"text\" name=\"b\" /> <input type=\"password\" name=\"c\" size=\"20\" />"
                + " <input type=\"text\" class=\"q\" name=\"d\" size=\"5\" /> <input type=\"text\" name=\"e\" size=\"x\" /></div></form></body></html>").getBytes(StandardCharsets.UTF_8);
        PageDocument doc = LayoutSamples.parse(html, "http://exp.yr/");
        RenderedPage page = PageRenderer.render(doc, new HashMap<>(), 1.0);
        Map<String, Integer> w = new HashMap<>();
        for(PageDocument.Control c : doc.controls)
            w.put(c.name, page.rects(c.n).get(0).w);
        // 内容宽 + 左右内边距 3×2 + 边框 1×2；每个字 7.5 像素
        assertEquals(160 + 8, (int) w.get("b"));
        assertEquals(38 + 8, (int) w.get("a"), "size=5");
        assertEquals(150 + 8, (int) w.get("c"), "size=20");
        assertEquals(250 + 8, (int) w.get("d"), "网站写了宽度的按网站的");
        assertEquals(160 + 8, (int) w.get("e"), "写错的 size 不算");
    }

    /** 排版检查本身查得出问题：关掉修正时（直接用内核排版）这几页确实有问题 */
    @Test
    void probeFindsProblemsWithoutTheFixes() throws Exception
    {
        LayoutProbe.Result box = checkRaw(resource("fixed-width-box"), "http://exp.yr/fixed-width-box");
        assertTrue(box.problems.stream().anyMatch(p -> p.kind == LayoutProbe.Kind.OUT_OF_PAGE), box.problems.toString());
        LayoutProbe.Result row = checkRaw(resource("search-row"), "http://exp.yr/search-row");
        assertTrue(row.problems.stream().anyMatch(p -> p.kind == LayoutProbe.Kind.CONTROL_HEIGHT), row.problems.toString());
    }

    /** 不做排版修正、不注入新的默认样式时的排版（模拟改之前的浏览器） */
    static LayoutProbe.Result checkRaw(byte[] html, String url) throws Exception
    {
        PageDocument doc = LayoutSamples.parse(html, url);
        LayoutProbe p = new LayoutProbe(PageRenderer.VIEW_WIDTH);
        RenderedPage page = PageRenderer.render(doc, LayoutSamples.placeholders(doc), 1.0, p::inspect, 0);
        return new LayoutProbe.Result(page, p.problems);
    }

    /** 同一页画两遍，图一模一样（排版修正只看排版结果，不看时间、没有随机） */
    @Test
    void sameResultEveryTime() throws Exception
    {
        for(String p : new String[]{"info-table-long-words", "search-row", "fixed-width-box"})
        {
            RenderedPage a = check(resource(p), "http://exp.yr/" + p, 1.5).page;
            RenderedPage b = check(resource(p), "http://exp.yr/" + p, 1.5).page;
            assertEquals(a.image.getWidth(), b.image.getWidth());
            assertEquals(a.image.getHeight(), b.image.getHeight());
            int[] pa = a.image.getRGB(0, 0, a.image.getWidth(), a.image.getHeight(), null, 0, a.image.getWidth());
            int[] pb = b.image.getRGB(0, 0, b.image.getWidth(), b.image.getHeight(), null, 0, b.image.getWidth());
            assertArrayEquals(pa, pb, p);
            assertEquals(a.regions.keySet(), b.regions.keySet());
        }
    }

    /** 网站写给 input 的样式（input.go 的底色、div.bar input 的字号）照样管改写出来的按钮 */
    @Test
    void siteInputStylesApplyToConvertedButtons() throws Exception
    {
        byte[] html = ("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style type=\"text/css\">"
                + "input.go { background: #f0a020; color: #ffffff; border: 1px solid #b97811; padding: 3px 14px; }</style></head><body>"
                + "<form action=\"/pay\" method=\"post\"><div><input type=\"submit\" class=\"go\" value=\"付款\" /> <input type=\"submit\" value=\"取消\" /></div></form>"
                + "</body></html>").getBytes(StandardCharsets.UTF_8);
        PageDocument doc = LayoutSamples.parse(html, "http://pay.yr/");
        RenderedPage page = PageRenderer.render(doc, new HashMap<>(), 1.0);
        PageDocument.Control go = doc.controls.stream().filter(c -> "付款".equals(c.value)).findFirst().get();
        PageDocument.Control cancel = doc.controls.stream().filter(c -> "取消".equals(c.value)).findFirst().get();
        RenderedPage.Rect r = page.rects(go.n).get(0);
        // 按钮左上角往里几像素：橙色底（不是默认的灰色）
        int rgb = page.image.getRGB(r.x + 3, r.y + 2) & 0xFFFFFF;
        assertEquals(0xF0A020, rgb, Integer.toHexString(rgb));
        RenderedPage.Rect c = page.rects(cancel.n).get(0);
        assertEquals(0xEFEFEF, page.image.getRGB(c.x + 3, c.y + 2) & 0xFFFFFF, "没写 class 的还是默认灰色");
        assertEquals(r.h, c.h, "同一行一样高");
        assertEquals(r.y, c.y, "上边对齐");
    }

    /** 同一行的输入框、下拉框、按钮一样高、上边对齐（网站给输入框写了高度、按钮字号不同时） */
    @Test
    void controlsOnOneLineHaveTheSameHeight() throws Exception
    {
        PageDocument doc = LayoutSamples.parse(resource("search-row"), "http://exp.yr/search-row");
        RenderedPage page = PageRenderer.render(doc, new HashMap<>(), 1.0);
        Map<Integer, List<PageDocument.Control>> byForm = new HashMap<>();
        for(PageDocument.Control c : doc.controls)
        {
            if(c.kind != PageDocument.Kind.HIDDEN && c.form >= 0)
                byForm.computeIfAbsent(c.form, k -> new ArrayList<>()).add(c);
        }
        assertEquals(2, byForm.size());
        for(List<PageDocument.Control> line : byForm.values())
        {
            assertTrue(line.size() >= 2);
            RenderedPage.Rect first = page.rects(line.get(0).n).get(0);
            for(PageDocument.Control c : line)
            {
                RenderedPage.Rect r = page.rects(c.n).get(0);
                assertEquals(first.h, r.h, c.name + " 的高度");
                assertTrue(Math.abs(first.y - r.y) <= 1, c.name + " 的上边：" + first.y + " 与 " + r.y);
            }
        }
    }

    /** 写了高度的按钮：字竖直居中（上下空白一样，差 1 像素以内），高度不变 */
    @Test
    void buttonWithFixedHeightCentersItsText() throws Exception
    {
        byte[] html = ("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style type=\"text/css\">"
                + "button { height: 60px; background: #ffffff; border: 1px solid #000000; color: #000000; }</style></head><body>"
                + "<p><button type=\"button\">字</button></p></body></html>").getBytes(StandardCharsets.UTF_8);
        PageDocument doc = LayoutSamples.parse(html, "http://exp.yr/");
        RenderedPage page = PageRenderer.render(doc, new HashMap<>(), 1.0);
        RenderedPage.Rect r = page.rects(doc.controls.get(0).n).get(0);
        // 60 + 上下内边距 2×2 + 边框 2
        assertEquals(66, r.h);
        int top = -1, bottom = -1;
        for(int y = r.y + 2; y < r.y + r.h - 2; y++)
        {
            for(int x = r.x + 2; x < r.x + r.w - 2; x++)
            {
                if((page.image.getRGB(x, y) & 0xFF) < 128)
                {
                    if(top < 0)
                        top = y;
                    bottom = y;
                }
            }
        }
        assertTrue(top > 0);
        int above = top - r.y, below = r.y + r.h - 1 - bottom;
        assertTrue(Math.abs(above - below) <= 3, "上 " + above + "，下 " + below);
    }

    /** 定宽的框比页面宽：缩到页面里；表格里的短词（confirm）不断，长网址断开 */
    @Test
    void wideBoxesShrinkAndOnlyLongWordsBreak() throws Exception
    {
        PageDocument doc = LayoutSamples.parse(resource("fixed-width-box"), "http://exp.yr/fixed-width-box");
        LayoutProbe p = new LayoutProbe(PageRenderer.VIEW_WIDTH);
        PageRenderer.render(doc, new HashMap<>(), 1.0, (r, root) ->
        {
            p.inspect(r, root);
            List<com.openhtmltopdf.render.Box> codes = new ArrayList<>();
            find(root, "code", codes);
            for(com.openhtmltopdf.render.Box b : codes)
            {
                String text = b.getElement().getTextContent();
                if(text.equals("confirm") || text.equals("verify"))
                    assertTrue(b.getHeight() < 30, text + " 不该被断成两行，高 " + b.getHeight());
            }
        });
        assertTrue(p.problems.isEmpty(), p.problems.toString());
    }

    /** 盒子树里所有的字段：{InlineText, 所在的 InlineLayoutBox} */
    private static void texts(com.openhtmltopdf.render.Box b, List<Object[]> out)
    {
        if(b instanceof com.openhtmltopdf.render.InlineLayoutBox)
        {
            for(Object o : ((com.openhtmltopdf.render.InlineLayoutBox) b).getInlineChildren())
            {
                if(o instanceof com.openhtmltopdf.render.InlineText)
                    out.add(new Object[]{o, b});
                else if(o instanceof com.openhtmltopdf.render.Box)
                    texts((com.openhtmltopdf.render.Box) o, out);
            }
        }
        for(com.openhtmltopdf.render.Box c : b.getChildren())
            texts(c, out);
    }

    private static void find(com.openhtmltopdf.render.Box b, String tag, List<com.openhtmltopdf.render.Box> out)
    {
        if(b.getElement() != null && PageDocument.local(b.getElement()).equals(tag) && !out.contains(b))
            out.add(b);
        if(b instanceof com.openhtmltopdf.render.InlineLayoutBox)
        {
            for(Object o : ((com.openhtmltopdf.render.InlineLayoutBox) b).getInlineChildren())
            {
                if(o instanceof com.openhtmltopdf.render.Box)
                    find((com.openhtmltopdf.render.Box) o, tag, out);
            }
        }
        for(com.openhtmltopdf.render.Box c : b.getChildren())
            find(c, tag, out);
    }

    /** 比容器宽的图：宽高按比例一起缩 */
    @Test
    void wideImagesScaleKeepingAspect() throws Exception
    {
        PageDocument doc = LayoutSamples.parse(resource("wide-images"), "http://exp.yr/wide-images");
        Map<String, byte[]> images = LayoutSamples.placeholders(doc);
        images.put("http://exp.yr/plain.png", LayoutSamples.png(900, 120));
        PageRenderer.render(doc, images, 1.0);
        NodeList imgs = doc.dom.getElementsByTagNameNS("*", "img");
        Map<String, String> styles = new HashMap<>();
        for(int i = 0; i < imgs.getLength(); i++)
        {
            Element e = (Element) imgs.item(i);
            styles.put(e.getAttribute("src"), e.getAttribute("style"));
        }
        // 横幅 800×200 → 宽 584（页面 600 减去 body 的外边距 8×2），高按比例 146
        assertTrue(styles.get("/banner.png").contains("width: 584px") && styles.get("/banner.png").contains("height: 146px"), styles.get("/banner.png"));
        // 侧栏 200 宽的框里 300×300 的方块图 → 200×200
        assertTrue(styles.get("/square.png").contains("width: 200px !important") && styles.get("/square.png").contains("height: 200px !important"), styles.get("/square.png"));
        assertTrue(styles.get("/plain.png").contains("width: 584px") && styles.get("/plain.png").contains("height: 78px"), styles.get("/plain.png"));
    }

    @Test
    void inputSelectorsAreCopiedForConvertedButtons()
    {
        assertEquals("input.go, button[data-yr-input].go {a:b}", InputSelectors.adapt("input.go {a:b}"));
        assertEquals("div.bar input, button[data-yr-input].x, div.bar button[data-yr-input] {c:d}",
                InputSelectors.adapt("div.bar input, button[data-yr-input].x {c:d}"));
        assertEquals("p, input[type=\"submit\"], button[data-yr-input][type=\"submit\"] {x:1}\nh1{y:2}",
                InputSelectors.adapt("p, input[type=\"submit\"] {x:1}\nh1{y:2}"));
        // 类名、ID、属性值里的 input 不算；注释里的不算
        String same = ".input { a: b } #input { c: d } a[title=\"input\"] { e: f } textinput { g: h } /* input { } */ p { i: j }";
        assertEquals(same, InputSelectors.adapt(same));
        // @media 里的也改；字符串里的 { } 不乱
        assertEquals("@media screen { input:focus, button[data-yr-input]:focus {a:b} }",
                InputSelectors.adapt("@media screen { input:focus {a:b} }"));
        assertEquals("input::after, button[data-yr-input]::after { content: \"}{\"; }", InputSelectors.adapt("input::after { content: \"}{\"; }"));
        assertNull(InputSelectors.adapt(null));
        // 没配对的大括号：原样留着，不抛异常
        assertNotNull(InputSelectors.adapt("input { a: b"));
    }

    /** 网站自己写的 data-yr-input 不算（只有浏览器改写出来的按钮才有） */
    @Test
    void siteCannotForgeTheConvertedButtonMarker() throws Exception
    {
        byte[] html = ("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                + "<button data-yr-input=\"submit\">站点自己的按钮</button><input type=\"submit\" value=\"改写的\" /></body></html>").getBytes(StandardCharsets.UTF_8);
        PageDocument doc = LayoutSamples.parse(html, "http://exp.yr/");
        NodeList buttons = doc.dom.getElementsByTagNameNS("*", "button");
        List<String> marked = new ArrayList<>();
        for(int i = 0; i < buttons.getLength(); i++)
        {
            Element b = (Element) buttons.item(i);
            if(b.hasAttribute(InputSelectors.ATTR))
                marked.add(b.getTextContent());
        }
        assertEquals(Arrays.asList("改写的"), marked);
    }
}
