package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.FormEncoding;
import com.mrcrayfish.furniture.computer.PageFacts;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.PageParser;
import com.mrcrayfish.furniture.computer.PixelCanvas;
import moe.qingu.youran.api.image.DyePng;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 画板表单控件：解析、尺寸、初始值、表单字段（PNG 的 data URL）、表单编码、保留规则 */
class CanvasFieldTest
{
    static String page(String inputs)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title></head><body>"
                + "<form action=\"/post\" method=\"post\"><p>" + inputs + "<input type=\"text\" name=\"t\"/><input type=\"submit\" value=\"go\"/></p></form></body></html>";
    }

    static PageDocument.Control find(PageDocument d, String name)
    {
        for(PageDocument.Control c : d.controls)
        {
            if(c.name.equals(name))
                return c;
        }
        return null;
    }

    static String serialize(PageDocument d) throws Exception
    {
        java.io.StringWriter w = new java.io.StringWriter();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(new javax.xml.transform.dom.DOMSource(d.dom), new javax.xml.transform.stream.StreamResult(w));
        return w.toString();
    }

    @Test
    void parsesCanvas() throws Exception
    {
        PageDocument d = PageDocumentTest.parse(page("<input type=\"x-yr-canvas\" name=\"pic\" width=\"125\" height=\"125\"/>"
                + "<input type=\"X-YR-CANVAS\" name=\"def\"/>"
                + "<input type=\"x-yr-canvas\" name=\"big\" width=\"600\" height=\"10\"/>"
                + "<input type=\"x-yr-canvas\" name=\"huge\" width=\"512\" height=\"512\"/>"
                + "<span data-yr-canvas=\"5x5\" name=\"fake\">x</span>"));
        PageDocument.Control pic = find(d, "pic");
        assertEquals(PageDocument.Kind.CANVAS, pic.kind);
        assertEquals(125, pic.canvasWidth);
        assertEquals(125, pic.canvasHeight);
        assertEquals(0, pic.form);
        PageDocument.Control def = find(d, "def");
        assertEquals(64, def.canvasWidth, "没写尺寸：64×64");
        assertNull(find(d, "big"), "宽超过 512：不算控件");
        assertNull(find(d, "huge"), "超过 65536 个像素");
        assertNull(find(d, "fake"), "页面自己写的标记不算");
        assertTrue(d.dom.getDocumentElement().getTextContent().contains("画板尺寸不对"));
        // 在页面上的大小：125 × 2 倍
        String xml = serialize(d);
        assertTrue(xml.contains("width: 250px; height: 250px"), xml);
        assertEquals(2, PixelCanvas.previewZoom(125, 125));
        assertEquals(1, PixelCanvas.previewZoom(468, 60));
        assertEquals(4, PixelCanvas.previewZoom(88, 31));
    }

    @Test
    void submitsPngDataUrl() throws Exception
    {
        byte[] init = new byte[4 * 3];
        init[0] = 3;
        String initUrl = DyePng.toDataUrl(DyePng.encode(4, 3, init));
        PageDocument d = PageDocumentTest.parse(page("<input type=\"x-yr-canvas\" name=\"pic\" width=\"4\" height=\"3\" value=\"" + initUrl + "\"/>"
                + "<input type=\"x-yr-canvas\" name=\"empty\" width=\"2\" height=\"2\"/>"
                + "<input type=\"x-yr-canvas\" name=\"off\" width=\"2\" height=\"2\" disabled=\"disabled\"/>"));
        FormState f = new FormState();
        f.init(d);
        PageDocument.Control pic = find(d, "pic");
        PixelCanvas c = f.canvas(pic.n);
        assertEquals(3, c.get(0, 0), "初始值");
        assertFalse(f.isDirty(d), "没画过不算填过");
        c.beginStroke();
        c.set(3, 2, 16);
        assertTrue(f.isDirty(d), "画过就算填过（自动刷新不刷）");
        List<Map.Entry<String, String>> fields = f.fields(d, 0, -1);
        assertEquals("pic", fields.get(0).getKey());
        DyePng.Image img = DyePng.decode(DyePng.fromDataUrl(fields.get(0).getValue()), 4, 3);
        assertEquals(3, img.pixels[0]);
        assertEquals(16, img.pixels[11]);
        assertTrue(DyePng.decode(DyePng.fromDataUrl(fields.get(1).getValue()), 2, 2).isBlank(), "没画的是全透明");
        assertEquals("t", fields.get(2).getKey(), "禁用的不提交");
        // 表单编码：urlencoded 里放得下，网站解回来一样
        String body = FormEncoding.encode(fields);
        assertEquals(fields.get(0).getValue(), FormEncoding.decode(body).get(0).getValue());
        // 提交成功后清空：回到初始值
        f.reset(d, 0);
        f.init(d);
        assertEquals(0, f.canvas(pic.n).get(3, 2));
        assertEquals(3, f.canvas(pic.n).get(0, 0));
    }

    @Test
    void badInitialValueIsBlank() throws Exception
    {
        String wrongSize = DyePng.toDataUrl(DyePng.encode(5, 5, new byte[25]));
        PageDocument d = PageDocumentTest.parse(page("<input type=\"x-yr-canvas\" name=\"a\" width=\"4\" height=\"4\" value=\"" + wrongSize + "\"/>"
                + "<input type=\"x-yr-canvas\" name=\"b\" width=\"4\" height=\"4\" value=\"garbage\"/>"));
        FormState f = new FormState();
        f.init(d);
        assertTrue(f.canvas(find(d, "a").n).isBlank());
        assertTrue(f.canvas(find(d, "b").n).isBlank());
    }

    @Test
    void serverSideFactsKnowCanvases() throws Exception
    {
        String html = page("<input type=\"x-yr-canvas\" name=\"pic\" width=\"88\" height=\"31\"/>");
        PageSnapshot s = new PageSnapshot("http://platform.180.yr/buy", html.getBytes(StandardCharsets.UTF_8), PageParser.Mode.XHTML, null);
        PageFacts facts = PageFacts.of(s, n -> null);
        assertEquals(1, facts.canvases.size());
        int[] wh = facts.canvases.values().iterator().next();
        assertArrayEquals(new int[]{88, 31}, wh);
    }
}
