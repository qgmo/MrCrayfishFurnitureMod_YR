package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.PageLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageDocumentTest
{
    static final String PAGE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>  测试&nbsp;页 </title><script>alert(1)</script>"
            + "<link rel=\"stylesheet\" href=\"file:///etc/x.css\"/></head><body>\n"
            + "<h1>标题</h1><p><a href=\"/next\">下一页</a> <a href=\"file:///etc/passwd\">坏链接</a></p>\n"
            + "<form action=\"/buy\" method=\"post\"><p><input type=\"text\" name=\"n\" value=\"1\" maxlength=\"3\"/>"
            + "<input type=\"password\" name=\"pw\"/><input type=\"hidden\" name=\"t\" value=\"tok\"/>"
            + "<input type=\"checkbox\" name=\"c\" value=\"yes\" checked=\"checked\"/>"
            + "<input type=\"radio\" name=\"r\" value=\"a\"/><input type=\"radio\" name=\"r\" value=\"b\" checked=\"checked\"/>"
            + "<select name=\"s\"><option value=\"1\">一</option><option selected=\"selected\">二</option></select>"
            + "<textarea name=\"ta\">多行</textarea>"
            + "<input type=\"submit\" name=\"go\" value=\"购买\"/><button type=\"submit\" name=\"go\" value=\"b2\">另一个</button></p></form>\n"
            + "<form><p><input type=\"text\" name=\"q\"/></p></form>\n"
            + "<p><span data-yr-item=\"minecraft:diamond\" style=\"display:inline-block;width:32px;height:32px\"></span>"
            + "<span data-yr-item-name=\"minecraft:diamond\">x</span><img src=\"/a.png\"/><img src=\"file:///etc/passwd\"/></p>\n"
            + "</body></html>";

    static PageDocument parse(String s) throws PageDocument.Invalid
    {
        return PageDocument.parse(s.getBytes(StandardCharsets.UTF_8), "https://minebay.yr/shop/", id -> id.equals("minecraft:diamond") ? "钻石" : null, 600);
    }

    @Test
    void preprocesses() throws Exception
    {
        PageDocument d = parse(PAGE);
        assertEquals("测试 页", d.title);
        assertEquals(0, d.dom.getElementsByTagNameNS("*", "script").getLength(), "脚本去掉");
        assertEquals(0, d.dom.getElementsByTagNameNS("*", "link").getLength(), "外部样式表去掉");
        assertTrue(d.links.containsValue("https://minebay.yr/next"));
        assertTrue(d.links.containsValue(null), "file: 链接点了打不开");
        assertEquals(Collections.singletonList("https://minebay.yr/a.png"), d.images, "只收能取的图");
        assertEquals(1, d.items.size());
        assertTrue(d.dom.getDocumentElement().getTextContent().contains("钻石"));
        assertEquals(2, d.forms.size());
        assertEquals("https://minebay.yr/buy", d.forms.get(0).action);
        assertTrue(d.forms.get(0).post);
        assertEquals("https://minebay.yr/shop/", d.forms.get(1).action, "没写 action 时提交到本页");
        assertFalse(d.forms.get(1).post);
        // submit 输入框改写成 button，文字是 value
        assertEquals(2, d.dom.getElementsByTagNameNS("*", "button").getLength());
    }

    @Test
    void formFields() throws Exception
    {
        PageDocument d = parse(PAGE);
        FormState f = new FormState();
        f.init(d);
        PageDocument.Control text = null, submit = null, checkbox = null, radioA = null, select = null;
        for(PageDocument.Control c : d.controls)
        {
            if(c.name.equals("n")) text = c;
            if(c.kind == PageDocument.Kind.SUBMIT && submit == null) submit = c;
            if(c.kind == PageDocument.Kind.CHECKBOX) checkbox = c;
            if(c.kind == PageDocument.Kind.RADIO && c.value.equals("a")) radioA = c;
            if(c.kind == PageDocument.Kind.SELECT) select = c;
        }
        assertEquals(3, text.maxLength);
        f.text(text.n).setText("12345");
        assertEquals("123", f.text(text.n).getText(), "maxlength");
        f.text(text.n).setText("7");
        List<Map.Entry<String, String>> fields = f.fields(d, 0, submit.n);
        assertEquals("[n=7, pw=, t=tok, c=yes, r=b, s=二, ta=多行, go=购买]", fields.toString());
        f.toggle(d, checkbox);
        f.toggle(d, radioA);
        f.toggle(d, select);
        assertEquals("[n=7, pw=, t=tok, r=a, s=1, ta=多行]", f.fields(d, 0, -1).toString());
        f.reset(d, 0);
        assertEquals("1", f.text(text.n).getText(), "提交成功后清空 = 回到页面上的初始值");
        assertTrue(f.isChecked(checkbox.n));
    }

    @Test
    void limitsBeforeParsing()
    {
        StringBuilder sb = new StringBuilder("<html><body>");
        for(int i = 0; i < 2100; i++)
            sb.append("<br/>");
        sb.append("</body></html>");
        PageDocument.Invalid e = assertThrows(PageDocument.Invalid.class, () -> parse(sb.toString()));
        assertEquals(PageLimits.Problem.TOO_MANY_ELEMENTS, e.problem);
        // 第二轮（服主 11-14）起宽松解析：没关上的标签、根元素不是 html 的也能显示（以前是“页面格式不对”）
        assertEquals("没关上", assertDoesNotThrow(() -> parse("<html><p>没关上</html>")).dom.getElementsByTagNameNS("*", "p").item(0).getTextContent());
        assertEquals("html", PageDocument.local(assertDoesNotThrow(() -> parse("<svg/>")).dom.getDocumentElement()));
        // 空页面仍然是“页面格式不对”
        assertEquals(PageLimits.Problem.MALFORMED, assertThrows(PageDocument.Invalid.class, () -> parse("  ")).problem);
    }

    static PageDocument parseHtml(String s) throws PageDocument.Invalid
    {
        return PageDocument.parse(s.getBytes(StandardCharsets.UTF_8), com.mrcrayfish.furniture.computer.PageParser.Mode.HTML, "http://test.yr/",
                id -> null, 600);
    }

    @Test
    void sloppyHtmlIsShown() throws Exception
    {
        // 不闭合的段落、列表、表格，大写标签，没加引号的属性，没有 html/head/body
        PageDocument d = parseHtml("<TITLE>乱写</TITLE><P>第一段<P>第二段<ul><li>一<li>二</ul><table><tr><td>格<td>子</table>"
                + "<a href=/next>下一页</a><img src=pic.png><form action=/s><input name=q value=搜><input type=password name=pw></form>&copy; &nbsp;x");
        assertEquals("乱写", d.title);
        assertEquals(com.mrcrayfish.furniture.computer.PageParser.XHTML_NS, d.dom.getDocumentElement().getNamespaceURI(), "元素在 XHTML 命名空间里");
        assertEquals(2, d.dom.getElementsByTagNameNS("*", "p").getLength());
        assertEquals(2, d.dom.getElementsByTagNameNS("*", "li").getLength());
        assertEquals(2, d.dom.getElementsByTagNameNS("*", "td").getLength());
        assertTrue(d.links.containsValue("http://test.yr/next"));
        assertEquals(Collections.singletonList("http://test.yr/pic.png"), d.images);
        assertEquals("http://test.yr/s", d.forms.get(0).action);
        assertEquals(2, d.controls.size());
        assertEquals(PageDocument.Kind.PASSWORD, d.controls.get(1).kind);
        assertTrue(d.dom.getDocumentElement().getTextContent().contains("\u00a9 \u00a0x"), "命名实体解码");
        RenderedPage p = PageRenderer.render(d, new HashMap<>(), 1.0);
        assertTrue(p.height > 50, "画出来了");
        // 脚本照样去掉
        assertEquals(0, parseHtml("<p>a<script>alert(1)</script><iframe src=x></iframe>").dom.getElementsByTagNameNS("*", "script").getLength());
    }

    @Test
    void limitsAreCheckedAfterLenientParsing()
    {
        StringBuilder deep = new StringBuilder();
        for(int i = 0; i < 70; i++)
            deep.append("<div>");
        assertEquals(PageLimits.Problem.TOO_DEEP, assertThrows(PageDocument.Invalid.class, () -> parseHtml(deep.toString())).problem, "没关上的 div 也一层层算");
        StringBuilder many = new StringBuilder();
        for(int i = 0; i < 2100; i++)
            many.append("<p>x");
        assertEquals(PageLimits.Problem.TOO_MANY_ELEMENTS, assertThrows(PageDocument.Invalid.class, () -> parseHtml(many.toString())).problem);
        StringBuilder big = new StringBuilder();
        while(big.length() <= PageLimits.MAX_BYTES)
            big.append("<b>xxxxxxxx</b>");
        assertEquals(PageLimits.Problem.TOO_LARGE, assertThrows(PageDocument.Invalid.class, () -> parseHtml(big.toString())).problem);
    }

    @Test
    void imagesOnlyFromSameHost() throws Exception
    {
        PageDocument d = parseHtml("<img src=/a.png><img src=http://test.yr:8080/b.png><img src=https://test.yr/c.png><img src=http://other.yr/d.png>"
                + "<img src=http://[fd00::1]/e.png>");
        assertEquals(java.util.Arrays.asList("http://test.yr/a.png", "http://test.yr:8080/b.png", "https://test.yr/c.png"), d.images);
    }

    @Test
    void captchaImagesFromAnySite() throws Exception
    {
        // captcha.yr 的验证码图片任何网站都能放（服主 11-15）；captcha.yr 上别的路径、仿冒的主机不行
        PageDocument d = parseHtml("<img src=https://captcha.yr/img/0123456789abcdef0123456789abcdef.png><img src=http://captcha.yr/img/x.png>"
                + "<img src=https://captcha.yr/other.png><img src=https://captcha.yr.evil.yr/img/x.png><img src=https://evilcaptcha.yr/img/x.png>");
        assertEquals(java.util.Arrays.asList("https://captcha.yr/img/0123456789abcdef0123456789abcdef.png", "http://captcha.yr/img/x.png"), d.images);
    }

    @Test
    void noExternalEntities()
    {
        // 外部实体不会被读出来（解析失败或者为空，都不能出现文件内容）
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE html [<!ENTITY x SYSTEM \"file:///etc/hostname\">]><html><body><p>&x;</p></body></html>";
        try
        {
            PageDocument d = parse(xxe);
            // jsoup 不读 DTD：实体引用原样当文字（第一轮的 XML 解析器是去掉它）
            String text = d.dom.getElementsByTagNameNS("*", "p").item(0).getTextContent().trim();
            assertTrue(text.isEmpty() || text.equals("&x;"), text);
        }
        catch(PageDocument.Invalid ok)
        {
            // 也可以
        }
    }

    @Test
    void namedEntities() throws Exception
    {
        // jsoup 按 HTML 规则解码命名实体；不认识的原样留着
        PageDocument d = parse("<html><body><p>a&nbsp;b &copy; &unknown; &amp;</p></body></html>");
        assertEquals("a\u00a0b \u00a9 &unknown; &", d.dom.getElementsByTagNameNS("*", "p").item(0).getTextContent());
    }

    @Test
    void builtinPagesAreValid() throws Exception
    {
        BuiltinPages.Tr tr = (k, a) -> k + (a.length > 0 ? ":" + a[0] : "");
        parse(new String(BuiltinPages.home(tr), StandardCharsets.UTF_8));
        for(BuiltinPages.Error e : BuiltinPages.Error.values())
            parse(new String(BuiltinPages.error(tr, e, "http://x.yr/<&>", "细节 <b>"), StandardCharsets.UTF_8));
        parse(new String(BuiltinPages.plainText("t", "a < b & c"), StandardCharsets.UTF_8));
        PageDocument img = parse(new String(BuiltinPages.image("https://minebay.yr/a.png"), StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList("https://minebay.yr/a.png"), img.images);
    }

    @Test
    void rendersWithBundledFontAndFindsElements() throws Exception
    {
        PageDocument d = parse(PAGE);
        RenderedPage p = PageRenderer.render(d, new HashMap<>(), 2.0);
        assertEquals(1200, p.image.getWidth());
        assertTrue(p.height > 100 && p.height < 1000, "高 " + p.height);
        assertFalse(p.truncated());
        // 每个链接、控件、物品图标都找到了位置，点在它中间能点到它
        for(Integer n : d.links.keySet())
            assertHit(p, n);
        for(PageDocument.Control c : d.controls)
        {
            if(c.kind != PageDocument.Kind.HIDDEN)
                assertHit(p, c.n);
        }
        for(Integer n : d.items.keySet())
        {
            RenderedPage.Rect r = assertHit(p, n);
            assertEquals(32, r.w);
            assertEquals(32, r.h);
        }
        // 中文用模组字体画出来了：标题那一行有深色像素
        int dark = 0;
        for(int y = 0; y < 80; y++)
        {
            for(int x = 0; x < 400; x++)
            {
                if((p.image.getRGB(x, y) & 0xFF) < 100)
                    dark++;
            }
        }
        assertTrue(dark > 200, "画出了文字：" + dark);
    }

    static RenderedPage.Rect assertHit(RenderedPage p, int n)
    {
        List<RenderedPage.Rect> rects = p.rects(n);
        assertFalse(rects.isEmpty(), "编号 " + n + " 没有位置");
        RenderedPage.Rect r = rects.get(0);
        assertEquals(n, p.hit(r.x + r.w / 2.0, r.y + r.h / 2.0), "点不到编号 " + n);
        return r;
    }

    @Test
    void tallPagesAreCapped() throws Exception
    {
        PageDocument d = parse("<html><body><div style=\"height: 100000px\">高</div><a href=\"/x\">底下</a></body></html>");
        RenderedPage p = PageRenderer.render(d, new HashMap<>(), 2.0);
        assertTrue(p.truncated());
        assertEquals(PageRenderer.MAX_HEIGHT, p.height);
        assertEquals(PageRenderer.MAX_HEIGHT * 2, p.image.getHeight(), "图的高度有上限");
        assertTrue(p.regions.isEmpty(), "画不到的地方不能点");
    }

    @Test
    void imagesOnlyFromPrefetch() throws Exception
    {
        PageDocument d = parse("<html><body><p><img src=\"/a.png\" width=\"10\" height=\"10\"/><img src=\"file:///etc/passwd\"/>"
                + "<img src=\"http://evil.example/x.png\"/></p></body></html>");
        // 没取到任何图：照样渲染，不去读文件、不连外网
        RenderedPage p = PageRenderer.render(d, new HashMap<>(), 1.0);
        assertNotNull(p);
    }

    static byte[] png(int w, int h, int rgb) throws Exception
    {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y = 0; y < h; y++)
            for(int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void checkedImagesAreDrawn() throws Exception
    {
        byte[] red = Browser.checkedImage(png(20, 20, 0xFF0000));
        assertNotNull(red);
        assertNull(Browser.checkedImage(png(Browser.MAX_IMAGE_SIDE + 1, 4, 0)), "尺寸超过上限");
        assertNull(Browser.checkedImage("不是图片".getBytes(StandardCharsets.UTF_8)));
        assertNull(Browser.checkedImage(new byte[Browser.MAX_IMAGE_BYTES + 1]), "字节数超过上限");
        PageDocument d = parse("<html><body style=\"margin:0\"><p style=\"margin:0\"><img src=\"/a.png\" width=\"20\" height=\"20\"/></p></body></html>");
        Map<String, byte[]> images = new HashMap<>();
        images.put("https://minebay.yr/a.png", red);
        RenderedPage p = PageRenderer.render(d, images, 1.0);
        assertEquals(0xFF0000, p.image.getRGB(10, 10) & 0xFFFFFF, "图画出来了");
        RenderedPage none = PageRenderer.render(d, new HashMap<>(), 1.0);
        assertNotEquals(0xFF0000, none.image.getRGB(10, 10) & 0xFFFFFF, "没取到的图不画");
    }

    @Test
    void bundledFontCoversGb2312AndBig5CommonCharacters() throws Exception
    {
        java.awt.Font f;
        try(java.io.InputStream in = PageRenderer.class.getResourceAsStream("/assets/cfm/fonts/YRSans-Regular.ttf"))
        {
            f = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
        }
        assertEquals(PageRenderer.FONT_FAMILY, f.getFamily(java.util.Locale.ROOT));
        int missingGb = 0, missingBig5 = 0;
        for(int hi = 0xA1; hi < 0xF8; hi++)
        {
            for(int lo = 0xA1; lo < 0xFF; lo++)
            {
                String c = decode(new byte[]{(byte) hi, (byte) lo}, "GB2312");
                if(c != null && !f.canDisplay(c.codePointAt(0)))
                    missingGb++;
            }
        }
        // Big5 一级字（常用字）
        for(int code = 0xA440; code <= 0xC67E; code++)
        {
            int lo = code & 0xFF;
            if(!(lo >= 0x40 && lo <= 0x7E || lo >= 0xA1 && lo <= 0xFE))
                continue;
            String c = decode(new byte[]{(byte) (code >> 8), (byte) lo}, "Big5");
            if(c != null && !f.canDisplay(c.codePointAt(0)))
                missingBig5++;
        }
        assertEquals(0, missingGb);
        assertEquals(0, missingBig5);
        assertEquals(-1, f.canDisplayUpTo("繁體中文：這個網頁說明們與體驗 臺灣"), "常用繁体字");
    }

    static String decode(byte[] b, String charset)
    {
        String s = new String(b, java.nio.charset.Charset.forName(charset));
        return s.length() == 1 && s.charAt(0) != '\uFFFD' ? s : null;
    }

    @Test
    void missingGlyphsFallBackToSystemFonts() throws Exception
    {
        PageRenderer.init();
        // 字体清单：页面写的 → 自带的 → 系统的逻辑字体 Dialog（按系统的回退链找能显示的）
        com.openhtmltopdf.layout.SharedContext ctx = new com.openhtmltopdf.layout.SharedContext();
        PageRenderer.FallbackFontResolver r = new PageRenderer.FallbackFontResolver(ctx);
        r.setFontMapping("Serif", new java.awt.Font("Serif", java.awt.Font.PLAIN, 1));
        com.openhtmltopdf.java2d.Java2DFont f = (com.openhtmltopdf.java2d.Java2DFont) r.resolveFont(ctx, new String[]{"Arial"}, 14f,
                com.openhtmltopdf.css.constants.IdentValue.FONT_WEIGHT_700, com.openhtmltopdf.css.constants.IdentValue.NORMAL, null);
        java.awt.Font last = f.getAWTFonts().get(f.getAWTFonts().size() - 1);
        assertEquals(java.awt.Font.DIALOG, last.getName());
        assertTrue(last.isBold());
        assertEquals(14f, last.getSize2D());
        // 整页渲染：繁体字、自带字体里没有的字（谚文、生僻字）都不出错
        PageDocument d = parse("<html><body><p style=\"font-size:40px\">繁體網頁</p><p>한국어 𠀀 ㄅㄆㄇ</p></body></html>");
        RenderedPage p = PageRenderer.render(d, new HashMap<>(), 1.0);
        int dark = 0;
        for(int y = 0; y < 60; y++)
            for(int x = 0; x < 200; x++)
                if((p.image.getRGB(x, y) & 0xFF) < 100)
                    dark++;
        assertTrue(dark > 300, "繁体字画出来了：" + dark);
    }

    @Test
    void unknownFontsFallBackToBundledFont() throws Exception
    {
        PageDocument d = parse("<html><body style=\"font-family: Arial, 'Comic Sans MS'\"><p style=\"font-family: monospace\">等宽 abc</p><p>中文</p></body></html>");
        assertNotNull(PageRenderer.render(d, new HashMap<>(), 1.0));
    }

    @Test
    void translatesLangKeys() throws Exception
    {
        String page = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p><span data-yr-lang=\"enchantment.untouching\">Silk Touch</span>|"
                + "<span data-yr-lang=\"no.such.key\">原文</span>|<span data-yr-lang=\"bad key&lt;\">坏键</span>|"
                + "<span data-yr-lang=\"enchantment.untouching\"><b>有子元素</b></span></p></body></html>";
        Map<String, String> lang = new HashMap<>();
        lang.put("enchantment.untouching", "精准采集");
        lang.put("bad key<", "不该用");
        PageDocument d = PageDocument.parse(page.getBytes(StandardCharsets.UTF_8), "https://minebay.yr/", id -> null, lang::get, 600);
        assertEquals("精准采集|原文|坏键|有子元素", d.dom.getElementsByTagNameNS("*", "p").item(0).getTextContent());
    }

    @Test
    void locationField() throws Exception
    {
        String page = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title></head><body>"
                + "<form action=\"https://shop.yr/checkout\" method=\"post\"><p>"
                + "<input type=\"x-yr-location\" name=\"loc\" value=\"nonce-12345678\"/>"
                + "<input type=\"hidden\" name=\"fake\" value=\"v\" data-yr-location=\"1\"/>"
                + "<input type=\"text\" name=\"q\"/><input type=\"submit\" value=\"go\"/></p></form></body></html>";
        PageDocument d = parse(page);
        PageDocument.Control loc = null;
        PageDocument.Control fake = null;
        for(PageDocument.Control c : d.controls)
        {
            if(c.name.equals("loc"))
                loc = c;
            if(c.name.equals("fake"))
                fake = c;
        }
        assertNotNull(loc);
        assertEquals(PageDocument.Kind.HIDDEN, loc.kind, "画成隐藏字段");
        assertTrue(loc.location);
        assertEquals("nonce-12345678", loc.value, "值是网站给的随机数");
        assertFalse(fake.location, "页面自己写的标记不算");
        assertTrue(d.dom.getDocumentElement().getTextContent().contains("位置"), "后面加了一句说明");
        FormState f = new FormState();
        f.init(d);
        assertEquals("[loc=nonce-12345678, fake=v, q=]", f.fields(d, 0, -1).toString(), "不换时照原值");
        Map<Integer, String> proof = new HashMap<>();
        proof.put(loc.n, "youran-location-proof v1\n...");
        assertEquals("[loc=youran-location-proof v1\n..., fake=v, q=]", f.fields(d, 0, -1, proof).toString());
    }
}
