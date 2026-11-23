package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.UrlInput;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.css.constants.IdentValue;
import com.openhtmltopdf.css.style.CssContext;
import com.openhtmltopdf.java2d.Java2DFont;
import com.openhtmltopdf.java2d.Java2DFontResolver;
import com.openhtmltopdf.java2d.Java2DRenderer;
import com.openhtmltopdf.java2d.Java2DTextRenderer;
import com.openhtmltopdf.java2d.Java2DFontContext;
import com.openhtmltopdf.java2d.Java2DOutputDevice;
import com.openhtmltopdf.extend.FontContext;
import com.openhtmltopdf.extend.OutputDevice;
import com.openhtmltopdf.layout.SharedContext;
import com.openhtmltopdf.render.FSFont;
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

import javax.annotation.Nullable;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 openhtmltopdf（Java2D 输出）把一页画成图（纯 Java，不碰 Minecraft；在浏览器的渲染线程上跑）。
 * <ul>
 * <li>页面宽固定 {@value #VIEW_WIDTH} CSS 像素，高按内容，最多画 {@value #MAX_HEIGHT}；超出的部分不画（界面上提示）。</li>
 * <li>字体用模组自带的 YR Sans（GB2312 + Big5 常用字；所有字体名都落到它上面），自带字体里没有的字用玩家系统的字体兜底。</li>
 * <li>外部资源：只给预先取好、检查过的图片（{@code images}，按绝对网址）；别的（{@code file:}、外网、样式表、字体……）一律拒绝。</li>
 * <li>渲染后按 {@link PageDocument#N_ATTR} 找到链接、表单控件、物品图标在页面上的位置，点击按位置分发。</li>
 * </ul>
 */
public final class PageRenderer
{
    public static final int VIEW_WIDTH = 600;
    public static final int MAX_HEIGHT = 4096;
    public static final String FONT_FAMILY = "YR Sans";
    static final String[] FONT_ALIASES = {FONT_FAMILY, "Serif", "SansSerif", "Monospaced", "Dialog", "DialogInput"};

    private static File regularFile;
    private static File boldFile;
    private static Font baseFont;
    private static Field rootField;
    private static Field sharedContextField;
    @Nullable
    private static java.lang.reflect.Method newLayoutContext;

    private PageRenderer() {}

    /** 第一次用时把字体从模组资源里拷到临时文件（内核只认文件或流，用文件免得每页都复制一份） */
    static synchronized void init() throws IOException
    {
        if(regularFile != null)
            return;
        XRLog.setLoggingEnabled(false);
        regularFile = extract("/assets/cfm/fonts/YRSans-Regular.ttf");
        boldFile = extract("/assets/cfm/fonts/YRSans-Bold.ttf");
        try
        {
            baseFont = Font.createFont(Font.TRUETYPE_FONT, regularFile);
            rootField = Java2DRenderer.class.getDeclaredField("_root");
            rootField.setAccessible(true);
            try
            {
                sharedContextField = Java2DRenderer.class.getDeclaredField("_sharedContext");
                sharedContextField.setAccessible(true);
            }
            catch(Exception e)
            {
                // 内核版本不同：没有系统字体兜底，照样能用
                sharedContextField = null;
            }
            try
            {
                newLayoutContext = Java2DRenderer.class.getDeclaredMethod("newLayoutContext");
                newLayoutContext.setAccessible(true);
            }
            catch(Exception e)
            {
                // 内核版本不同：不做排版修正，照样能用
                newLayoutContext = null;
            }
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
        return render(doc, images, scale, null);
    }

    /** 排版之后、画之前看一眼盒子树（单元测试里的排版检查用） */
    interface Inspector
    {
        void inspect(Java2DRenderer renderer, BlockBox root) throws IOException;
    }

    static RenderedPage render(PageDocument doc, Map<String, byte[]> images, double scale, @Nullable Inspector inspector) throws IOException
    {
        return render(doc, images, scale, inspector, FIX_PASSES);
    }

    /** @param fixPasses 排版修正最多再排几遍（0 为不修，测试里看内核原样的排版用） */
    static RenderedPage render(PageDocument doc, Map<String, byte[]> images, double scale, @Nullable Inspector inspector, int fixPasses) throws IOException
    {
        init();
        double s = Math.max(1.0, Math.min(3.0, scale));
        for(int pass = 0; ; pass++)
        {
            CappedProcessor processor = new CappedProcessor(s);
            try(Java2DRenderer r = builder(doc, images, processor).buildJava2DRenderer())
            {
                installFallbackFonts(r);
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
                // 排版修正（LayoutFixes）：改了样式就重新排一遍，最多 FIX_PASSES 遍
                if(pass < fixPasses && fix(r, root))
                    continue;
                int full = root.getHeight();
                if(inspector != null)
                    inspector.inspect(r, root);
                r.writeSinglePage();
                if(processor.image == null)
                    throw new IOException("没有画出页面");
                Map<Integer, List<RenderedPage.Rect>> regions = new HashMap<>();
                collect(root, regions, 0);
                int h = Math.min(full, MAX_HEIGHT);
                return new RenderedPage(doc, processor.image, s, VIEW_WIDTH, Math.max(1, h), full, regions);
            }
        }
    }

    /** 排版修正最多再排几遍（每遍只会修得更少；一般一遍就够，嵌套的定宽盒子要两遍） */
    static final int FIX_PASSES = 3;

    /** 找出排版毛病、改样式（{@link LayoutFixes}）；拿不到内核的排版上下文时不修 */
    private static boolean fix(Java2DRenderer r, BlockBox root)
    {
        if(newLayoutContext == null)
            return false;
        try
        {
            CssContext ctx = (CssContext) newLayoutContext.invoke(r);
            return LayoutFixes.apply(root, ctx, VIEW_WIDTH);
        }
        catch(ReflectiveOperationException | RuntimeException e)
        {
            return false;
        }
    }

    private static Java2DRendererBuilder builder(PageDocument doc, Map<String, byte[]> images, CappedProcessor processor)
    {
        Java2DRendererBuilder b = new Java2DRendererBuilder();
        b.withW3cDocument(doc.dom, doc.baseUrl.startsWith("http") ? doc.baseUrl : "http://localhost/");
        for(String family : FONT_ALIASES)
        {
            b.useFont(regularFile, family, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(boldFile, family, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
        }
        b.useEnvironmentFonts(false);
        b.useLayoutGraphics(layoutGraphics());
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
        return b;
    }

    /**
     * 字体：页面写的字体名都落到模组自带的 YR Sans（GB2312 + Big5 常用字）上；自带字体里没有的字，再用玩家系统的字体兜底
     * （Java2D 的逻辑字体 Dialog，按系统的字体回退链找能显示的字体；内核按字符分段，一段一段换字体画）。
     * 换掉内核自己的字体解析器（它只认注册过的字体和最后一个名叫 Serif 的字体）；拿不到时只用自带字体。
     */
    private static void installFallbackFonts(Java2DRenderer r)
    {
        Java2DFontResolver resolver = null;
        if(sharedContextField != null)
        {
            try
            {
                SharedContext ctx = (SharedContext) sharedContextField.get(r);
                FallbackFontResolver f = new FallbackFontResolver(ctx);
                for(String family : FONT_ALIASES)
                {
                    f.addFontFile(regularFile, family, 400, IdentValue.NORMAL);
                    f.addFontFile(boldFile, family, 700, IdentValue.NORMAL);
                }
                ctx.setFontResolver(f);
                resolver = f;
            }
            catch(Exception | LinkageError e)
            {
                resolver = null;
            }
        }
        if(resolver == null)
            resolver = r.getFontResolver();
        // 不认识的字体名最后落到这里（内核的兜底是名叫 Serif 的字体）
        resolver.setFontMapping("Serif", baseFont);
        fractionalMetrics(r);
    }

    /**
     * 内核的文字绘制器（{@link Java2DTextRenderer}）量字宽、画字时都把小数字宽（{@code FRACTIONALMETRICS}）强制关掉：排版在倍数 1 下
     * 按整数字宽量，画在放大的图上时按放大后的整数字宽画，两边取整不同，长串字画出来比量的宽（全屏时一行长英文多出好几个像素，
     * 压到格子边框、伸出盒子）。换成 {@link FractionalTextRenderer}：量、画都用小数字宽，量出来的宽向上取整（宁可早一点断行，不会画出界）。
     */
    private static void fractionalMetrics(Java2DRenderer r)
    {
        if(sharedContextField == null)
            return;
        try
        {
            SharedContext ctx = (SharedContext) sharedContextField.get(r);
            if(ctx.getTextRenderer() instanceof Java2DTextRenderer)
                ctx.setTextRenderer(new FractionalTextRenderer());
        }
        catch(Exception | LinkageError e)
        {
            // 照旧（内核自己的绘制器）
        }
    }

    /** 量字宽、画字都用小数字宽的文字绘制器（见 {@link #fractionalMetrics}）；分段换字体的办法同内核：每个字用字体清单里第一个能显示它的 */
    static final class FractionalTextRenderer extends Java2DTextRenderer
    {
        @Override
        public int getWidth(FontContext fc, FSFont font, String s)
        {
            if(s.isEmpty() || !(font instanceof Java2DFont) || !(fc instanceof Java2DFontContext))
                return super.getWidth(fc, font, s);
            Graphics2D g = ((Java2DFontContext) fc).getGraphics();
            Object old = g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            try
            {
                java.awt.font.TextLayout tl = new java.awt.font.TextLayout(runs(((Java2DFont) font).getAWTFonts(), s).getIterator(), g.getFontRenderContext());
                return (int) Math.ceil(tl.getAdvance() - 1e-6);
            }
            finally
            {
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, old);
            }
        }

        @Override
        public void drawString(OutputDevice od, String s, float x, float y)
        {
            if(s.isEmpty() || !(od instanceof Java2DOutputDevice))
            {
                super.drawString(od, s, x, y);
                return;
            }
            Java2DOutputDevice out = (Java2DOutputDevice) od;
            Graphics2D g = out.getGraphics();
            Object aa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
            Object fm = g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            try
            {
                g.drawString(runs(out.getFont().getAWTFonts(), s).getIterator(), x, y);
            }
            finally
            {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fm);
            }
        }

        /** 按字体分段：每个字用清单里第一个能显示它的字体，都不能显示时用第一个 */
        static java.text.AttributedString runs(List<Font> fonts, String s)
        {
            java.text.AttributedString as = new java.text.AttributedString(s);
            int start = 0;
            Font current = null;
            for(int i = 0; i < s.length(); )
            {
                int cp = s.codePointAt(i);
                Font f = fonts.get(0);
                for(Font c : fonts)
                {
                    if(c.canDisplay(cp))
                    {
                        f = c;
                        break;
                    }
                }
                if(current != null && f != current)
                {
                    as.addAttribute(java.awt.font.TextAttribute.FONT, current, start, i);
                    start = i;
                }
                current = f;
                i += Character.charCount(cp);
            }
            as.addAttribute(java.awt.font.TextAttribute.FONT, current, start, s.length());
            return as;
        }
    }

    /** 每段文字的字体清单：页面写的 → 自带的 YR Sans → 系统字体（Dialog） */
    static final class FallbackFontResolver extends Java2DFontResolver
    {
        FallbackFontResolver(SharedContext ctx)
        {
            super(ctx, false);
        }

        @Override
        public FSFont resolveFont(SharedContext ctx, String[] families, float size, IdentValue weight, IdentValue style, IdentValue variant)
        {
            String[] all = families == null ? new String[1] : Arrays.copyOf(families, families.length + 1);
            all[all.length - 1] = FONT_FAMILY;
            FSFont f = super.resolveFont(ctx, all, size, weight, style, variant);
            if(!(f instanceof Java2DFont))
                return f;
            List<Font> fonts = new ArrayList<>(((Java2DFont) f).getAWTFonts());
            boolean bold = weight != null && (weight == IdentValue.BOLD || weight == IdentValue.BOLDER || weight == IdentValue.FONT_WEIGHT_600
                    || weight == IdentValue.FONT_WEIGHT_700 || weight == IdentValue.FONT_WEIGHT_800 || weight == IdentValue.FONT_WEIGHT_900);
            boolean italic = style == IdentValue.ITALIC || style == IdentValue.OBLIQUE;
            fonts.add(new Font(Font.DIALOG, (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0), 1).deriveFont(size));
            return new Java2DFont(fonts, size);
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

    /**
     * 画页面用的绘制设置，排版量字宽时也用同一套（{@link #layoutGraphics()}）。字宽用小数（{@code FRACTIONALMETRICS}）：
     * 内核在倍数 1 下量字宽、在放大的图上画字，按整数取整的字宽在两种大小下取得不一样，长串字画出来会比量的宽，
     * 压到格子边框、伸出盒子（全屏时一行长英文能多出好几个像素）；用小数字宽后画出来的宽正好是量的宽乘倍数。
     */
    static void paintHints(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /** 内核排版时量字宽用的画布（设置同 {@link #paintHints}） */
    static Graphics2D layoutGraphics()
    {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        paintHints(g);
        return g;
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
                paintHints(g);
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
