package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.UrlInput;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.java2d.Java2DRenderer;
import com.openhtmltopdf.java2d.api.FSPage;
import com.openhtmltopdf.java2d.api.FSPageProcessor;
import com.openhtmltopdf.java2d.api.Java2DRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.InlineLayoutBox;
import com.openhtmltopdf.util.XRLog;
import org.w3c.dom.Element;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 openhtmltopdf（Java2D 输出）把一页画成图（纯 Java，不碰 Minecraft；在浏览器的渲染线程上跑）。
 * <ul>
 * <li>页面宽固定 {@value #VIEW_WIDTH} CSS 像素，高按内容，最多画 {@value #MAX_HEIGHT}；超出的部分不画（界面上提示）。</li>
 * <li>字体只用模组自带的中文字体（所有字体名都落到它上面），不用系统字体。</li>
 * <li>外部资源：只给预先取好、检查过的图片（{@code images}，按绝对网址）；别的（{@code file:}、外网、样式表、字体……）一律拒绝。</li>
 * <li>渲染后按 {@link PageDocument#N_ATTR} 找到链接、表单控件、物品图标在页面上的位置，点击按位置分发。</li>
 * </ul>
 */
public final class PageRenderer
{
    public static final int VIEW_WIDTH = 600;
    public static final int MAX_HEIGHT = 4096;
    public static final String FONT_FAMILY = "YR Sans SC";
    static final String[] FONT_ALIASES = {FONT_FAMILY, "Serif", "SansSerif", "Monospaced", "Dialog", "DialogInput"};

    private static File regularFile;
    private static File boldFile;
    private static Font baseFont;
    private static Field rootField;

    private PageRenderer() {}

    /** 第一次用时把字体从模组资源里拷到临时文件（内核只认文件或流，用文件免得每页都复制一份） */
    static synchronized void init() throws IOException
    {
        if(regularFile != null)
            return;
        XRLog.setLoggingEnabled(false);
        regularFile = extract("/assets/cfm/fonts/YRSansSC-Regular.ttf");
        boldFile = extract("/assets/cfm/fonts/YRSansSC-Bold.ttf");
        try
        {
            baseFont = Font.createFont(Font.TRUETYPE_FONT, regularFile);
            rootField = Java2DRenderer.class.getDeclaredField("_root");
            rootField.setAccessible(true);
        }
        catch(Exception e)
        {
            regularFile = null;
            throw new IOException("字体或内核初始化失败：" + e, e);
        }
    }

    private static File extract(String resource) throws IOException
    {
        try(InputStream in = PageRenderer.class.getResourceAsStream(resource))
        {
            if(in == null)
                throw new IOException("模组里没有 " + resource);
            File f = File.createTempFile("cfm-browser-font", ".ttf");
            f.deleteOnExit();
            try(OutputStream out = new FileOutputStream(f))
            {
                byte[] b = new byte[64 * 1024];
                int n;
                while((n = in.read(b)) > 0)
                    out.write(b, 0, n);
            }
            return f;
        }
    }

    /**
     * @param images 绝对网址 → 检查过的图片（PNG 字节）
     * @param scale  图上 1 个 CSS 像素对应几个图像素（1–3）
     */
    public static RenderedPage render(PageDocument doc, Map<String, byte[]> images, double scale) throws IOException
    {
        init();
        double s = Math.max(1.0, Math.min(3.0, scale));
        CappedProcessor processor = new CappedProcessor(s);
        Java2DRendererBuilder b = new Java2DRendererBuilder();
        b.withW3cDocument(doc.dom, doc.baseUrl.startsWith("http") ? doc.baseUrl : "http://localhost/");
        for(String family : FONT_ALIASES)
        {
            b.useFont(regularFile, family, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(boldFile, family, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
        }
        b.useEnvironmentFonts(false);
        b.useFastMode();
        b.toSinglePage(processor);
        b.useUriResolver((base, uri) -> UrlInput.resolve(base, uri));
        b.useProtocolsStreamImplementation(url ->
        {
            byte[] data = images.get(url);
            return new FSStream()
            {
                @Override
                public InputStream getStream()
                {
                    return data == null ? null : new ByteArrayInputStream(data);
                }

                @Override
                public Reader getReader()
                {
                    return null;
                }
            };
        }, "http", "https");
        b.useExternalResourceAccessControl((uri, type) -> type == ExternalResourceType.IMAGE_RASTER && uri != null && images.containsKey(uri),
                ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
        b.useExternalResourceAccessControl((uri, type) -> type == ExternalResourceType.IMAGE_RASTER, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
        b.withDiagnosticConsumer(d -> { });
        try(Java2DRenderer r = b.buildJava2DRenderer())
        {
            // 不认识的字体名最后落到这里（内核的兜底是名叫 Serif 的系统字体）
            r.getFontResolver().setFontMapping("Serif", baseFont);
            r.layout();
            BlockBox root;
            try
            {
                root = (BlockBox) rootField.get(r);
            }
            catch(IllegalAccessException e)
            {
                throw new IOException(e);
            }
            int full = root.getHeight();
            r.writeSinglePage();
            if(processor.image == null)
                throw new IOException("没有画出页面");
            Map<Integer, List<RenderedPage.Rect>> regions = new HashMap<>();
            collect(root, regions, 0);
            int h = Math.min(full, MAX_HEIGHT);
            return new RenderedPage(doc, processor.image, s, VIEW_WIDTH, Math.max(1, h), full, regions);
        }
    }

    private static void collect(Object o, Map<Integer, List<RenderedPage.Rect>> out, int depth)
    {
        if(!(o instanceof Box) || depth > 400)
            return;
        Box b = (Box) o;
        Element e = b.getElement();
        if(e != null && e.hasAttribute(PageDocument.N_ATTR))
        {
            int w = b instanceof InlineLayoutBox ? ((InlineLayoutBox) b).getInlineWidth() : b.getWidth();
            int h = b.getHeight();
            if(w > 0 && h > 0 && b.getAbsY() < MAX_HEIGHT)
            {
                try
                {
                    int n = Integer.parseInt(e.getAttribute(PageDocument.N_ATTR));
                    out.computeIfAbsent(n, k -> new ArrayList<>()).add(new RenderedPage.Rect(b.getAbsX(), b.getAbsY(), w, h));
                }
                catch(NumberFormatException ignored)
                {
                    // 网站自己写了这个属性：不理
                }
            }
        }
        for(Box c : b.getChildren())
            collect(c, out, depth + 1);
        if(b instanceof InlineLayoutBox)
        {
            for(Object c : ((InlineLayoutBox) b).getInlineChildren())
                collect(c, out, depth + 1);
        }
    }

    /** 只开一张图，高不超过 {@link #MAX_HEIGHT}（超出的部分画到图外面，自然被裁掉） */
    private static final class CappedProcessor implements FSPageProcessor
    {
        final double scale;
        BufferedImage image;
        Graphics2D g;

        CappedProcessor(double scale)
        {
            this.scale = scale;
        }

        @Override
        public FSPage createPage(int zeroBasedPageNumber, int width, int height)
        {
            if(image == null)
            {
                int w = Math.max(1, (int) Math.ceil(Math.min(width, VIEW_WIDTH) * scale));
                int h = Math.max(1, (int) Math.ceil(Math.min(height, MAX_HEIGHT) * scale));
                image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                g = image.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.setClip(0, 0, w, h);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.scale(scale, scale);
            }
            Graphics2D gg = g;
            return () -> gg;
        }

        @Override
        public void finishPage(FSPage pg)
        {
            if(g != null)
            {
                g.dispose();
                g = null;
            }
        }
    }
}
