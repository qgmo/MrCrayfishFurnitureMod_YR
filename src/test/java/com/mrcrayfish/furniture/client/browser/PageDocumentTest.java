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
        assertEquals(PageLimits.Problem.MALFORMED, assertThrows(PageDocument.Invalid.class, () -> parse("<html><p>没关上</html>")).problem);
        assertEquals(PageLimits.Problem.MALFORMED, assertThrows(PageDocument.Invalid.class, () -> parse("<svg/>")).problem);
    }

    @Test
    void noExternalEntities()
    {
        // 外部实体不会被读出来（解析失败或者为空，都不能出现文件内容）
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE html [<!ENTITY x SYSTEM \"file:///etc/hostname\">]><html><body><p>&x;</p></body></html>";
        try
        {
            PageDocument d = parse(xxe);
            assertEquals("", d.dom.getElementsByTagNameNS("*", "p").item(0).getTextContent().trim());
        }
        catch(PageDocument.Invalid ok)
        {
            // 也可以
        }
    }

    @Test
    void namedEntities()
    {
        assertEquals("a&#160;b &#169; &unknown; &amp;", PageDocument.replaceNamedEntities("a&nbsp;b &copy; &unknown; &amp;"));
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
    void unknownFontsFallBackToBundledFont() throws Exception
    {
        PageDocument d = parse("<html><body style=\"font-family: Arial, 'Comic Sans MS'\"><p style=\"font-family: monospace\">等宽 abc</p><p>中文</p></body></html>");
        assertNotNull(PageRenderer.render(d, new HashMap<>(), 1.0));
    }
}
