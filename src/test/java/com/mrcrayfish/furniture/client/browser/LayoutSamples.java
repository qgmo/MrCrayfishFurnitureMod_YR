package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.PageParser;
import com.mrcrayfish.furniture.computer.UrlInput;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 排版检查的样例页面：怎么解析（看开头）、图片用占位图（按 {@code <img>} 写的宽高画一块灰底斜线，网站的真图不在这里）。
 */
final class LayoutSamples
{
    private LayoutSamples() {}

    /** 以 {@code <?xml} 开头的按 XHTML（游戏内网站都回 application/xhtml+xml），其余按 HTML（面板回 text/html） */
    static PageParser.Mode modeOf(byte[] html)
    {
        String head = new String(html, 0, Math.min(html.length, 64), StandardCharsets.UTF_8).trim();
        return head.startsWith("<?xml") ? PageParser.Mode.XHTML : PageParser.Mode.HTML;
    }

    static PageDocument parse(byte[] html, String url) throws PageDocument.Invalid
    {
        return PageDocument.parse(html, modeOf(html), url, LayoutSamples::itemName, key -> null, PageRenderer.VIEW_WIDTH);
    }

    /** 物品名：测试里没有游戏，按注册名给一个像样的名字（长的也有） */
    static String itemName(String id)
    {
        return id.contains("long") ? "附魔金苹果（Enchanted_Golden_Apple_With_A_Very_Long_Name）" : "物品 " + id;
    }

    /** 页面里每张允许的图片都给一张占位图（宽高按 {@code <img>} 的属性，没写为 100×40） */
    static Map<String, byte[]> placeholders(PageDocument doc) throws IOException
    {
        Map<String, byte[]> out = new HashMap<>();
        NodeList imgs = doc.dom.getElementsByTagNameNS("*", "img");
        for(int i = 0; i < imgs.getLength(); i++)
        {
            Element e = (Element) imgs.item(i);
            String src = UrlInput.resolve(doc.baseUrl, e.getAttribute("src"));
            if(src == null || !doc.images.contains(src) || out.containsKey(src))
                continue;
            out.put(src, png(size(e.getAttribute("width"), 100), size(e.getAttribute("height"), 40)));
        }
        return out;
    }

    private static int size(String v, int def)
    {
        return v != null && v.matches("\\d{1,4}") ? Math.max(1, Math.min(1024, Integer.parseInt(v))) : def;
    }

    static byte[] png(int w, int h) throws IOException
    {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xD8DEE6));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x8A96A3));
        g.setStroke(new BasicStroke(1));
        g.drawRect(0, 0, w - 1, h - 1);
        g.drawLine(0, 0, w - 1, h - 1);
        g.drawLine(w - 1, 0, 0, h - 1);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    static void save(RenderedPage p, File file) throws IOException
    {
        File dir = file.getParentFile();
        if(dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("建不了目录 " + dir);
        ImageIO.write(p.image, "png", file);
    }
}
