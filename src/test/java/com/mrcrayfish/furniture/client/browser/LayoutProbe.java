package com.mrcrayfish.furniture.client.browser;

import com.openhtmltopdf.java2d.Java2DRenderer;
import com.openhtmltopdf.layout.LayoutContext;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.InlineLayoutBox;
import com.openhtmltopdf.render.InlineText;
import com.openhtmltopdf.render.LineBox;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.awt.RenderingHints;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 排版检查（单元测试用，不碰 OpenGL）：浏览器内核排好版以后，在盒子树上找排版问题。
 * <ul>
 * <li>{@link Kind#TEXT_OUT_OF_BOX}：一段字伸出了它所在的盒子（内容区）右边或下边；</li>
 * <li>{@link Kind#BOX_OUT_OF_PARENT}：一个块（表格、按钮、图片、定宽的框……）伸出了父盒子的内容区；</li>
 * <li>{@link Kind#TEXT_OVERLAP}：同一行里一段字画出来盖到了后一段字上（字之间的空格被吃掉，像“Silk TouchI”）；</li>
 * <li>{@link Kind#TEXT_MEASURE}：排版量的字宽比画出来的宽出一截（后面的字被推开，中间空出一块）；</li>
 * <li>{@link Kind#OUT_OF_PAGE}：字或盒子伸出了页面（宽 {@value PageRenderer#VIEW_WIDTH}）；</li>
 * <li>{@link Kind#CONTROL_HEIGHT}：同一行的按钮、输入框、下拉框高度不一样；</li>
 * <li>{@link Kind#CONTROL_ALIGN}：同一行的按钮、输入框上下没对齐（上边不齐）。</li>
 * </ul>
 * 被 {@code overflow: hidden} 的祖先裁掉的部分看不见，不算伸出（截断另有省略号与悬停提示）。允许 1 像素的取整误差。
 */
final class LayoutProbe
{
    enum Kind
    {
        TEXT_OUT_OF_BOX, TEXT_OVERLAP, TEXT_MEASURE, BOX_OUT_OF_PARENT, OUT_OF_PAGE, CONTROL_HEIGHT, CONTROL_ALIGN
    }

    static final class Problem
    {
        final Kind kind;
        final String where;
        final String detail;

        Problem(Kind kind, String where, String detail)
        {
            this.kind = kind;
            this.where = where;
            this.detail = detail;
        }

        @Override
        public String toString()
        {
            return kind + " " + where + "：" + detail;
        }
    }

    static final int TOLERANCE = 1;

    final List<Problem> problems = new ArrayList<>();
    private final int pageWidth;
    private LayoutContext ctx;

    /** 画成图的倍数（量“画出来多宽”用；0 为不量） */
    private final double scale;
    private java.awt.Graphics2D paint;
    private Object fractional;
    /** 画字时按排版量的字宽摆字（{@link PageRenderer.LayoutMatchedTextRenderer}） */
    private boolean matched;

    LayoutProbe(int pageWidth)
    {
        this(pageWidth, 0);
    }

    LayoutProbe(int pageWidth, double scale)
    {
        this.pageWidth = pageWidth;
        this.scale = scale;
    }

    /** 渲染一页并检查，返回发现的问题（同时给出渲染好的页面，存图用） */
    static Result check(PageDocument doc, Map<String, byte[]> images, double scale) throws IOException
    {
        LayoutProbe p = new LayoutProbe(PageRenderer.VIEW_WIDTH, Math.max(1.0, Math.min(3.0, scale)));
        RenderedPage page = PageRenderer.render(doc, images, scale, p::inspect);
        return new Result(page, p.problems);
    }

    static final class Result
    {
        final RenderedPage page;
        final List<Problem> problems;

        Result(RenderedPage page, List<Problem> problems)
        {
            this.page = page;
            this.problems = problems;
        }
    }

    void inspect(Java2DRenderer renderer, BlockBox root) throws IOException
    {
        try
        {
            Method m = Java2DRenderer.class.getDeclaredMethod("newLayoutContext");
            m.setAccessible(true);
            ctx = (LayoutContext) m.invoke(renderer);
        }
        catch(ReflectiveOperationException e)
        {
            throw new IOException(e);
        }
        // 量“画出来多宽”要和内核画字时一样：它的文字绘制器自己定小数字宽开不开
        fractional = RenderingHints.VALUE_FRACTIONALMETRICS_OFF;
        try
        {
            java.lang.reflect.Field sc = Java2DRenderer.class.getDeclaredField("_sharedContext");
            sc.setAccessible(true);
            Object tr = ((com.openhtmltopdf.layout.SharedContext) sc.get(renderer)).getTextRenderer();
            matched = tr instanceof PageRenderer.LayoutMatchedTextRenderer;
            if(!matched)
            {
                java.lang.reflect.Field fm = com.openhtmltopdf.java2d.Java2DTextRenderer.class.getDeclaredField("fractionalFontMetricsHint");
                fm.setAccessible(true);
                fractional = fm.get(tr);
            }
        }
        catch(ReflectiveOperationException e)
        {
            throw new IOException(e);
        }
        walk(root, null, Integer.MAX_VALUE, 0, false);
    }

    /** 上外边距（inspect 之后可用） */
    int marginTop(Box b)
    {
        return (int) b.getMargin(ctx).top();
    }

    /** 盒子的内容区（绝对坐标）：x、y、宽、高 */
    private int[] content(Box b)
    {
        int left = b.getMargin(ctx).left() > 0 ? (int) b.getMargin(ctx).left() : 0;
        int x = b.getAbsX() + left + (int) b.getBorder(ctx).left() + (int) b.getPadding(ctx).left();
        int top = b.getMargin(ctx).top() > 0 ? (int) b.getMargin(ctx).top() : 0;
        int y = b.getAbsY() + top + (int) b.getBorder(ctx).top() + (int) b.getPadding(ctx).top();
        int h = b.getHeight() - top - (int) (b.getBorder(ctx).top() + b.getBorder(ctx).bottom() + b.getPadding(ctx).top() + b.getPadding(ctx).bottom())
                - Math.max(0, (int) b.getMargin(ctx).bottom());
        return new int[]{x, y, b.getContentWidth(), h};
    }

    /** 边框盒的左、右（绝对坐标） */
    private int[] borderX(Box b)
    {
        int left = (int) b.getMargin(ctx).left();
        int right = (int) b.getMargin(ctx).right();
        int x1 = b.getAbsX() + Math.max(0, left);
        int w = b instanceof InlineLayoutBox ? ((InlineLayoutBox) b).getInlineWidth(ctx) : b.getWidth() - Math.max(0, left) - Math.max(0, right);
        return new int[]{x1, x1 + w};
    }

    /**
     * @param cb       包含块（最近的块）
     * @param clipRight 祖先里 overflow: hidden 的右边界（看不见的部分不算）
     */
    private void walk(Box b, Box cb, int clipRight, int depth, boolean quiet)
    {
        if(depth > 400)
            return;
        boolean blockLike = b instanceof BlockBox;
        if(blockLike && cb != null && !(b instanceof LineBox) && !quiet)
            quiet = checkBox(b, cb, clipRight);
        int clip = clipRight;
        if(blockLike && b.getStyle() != null && !b.getStyle().isOverflowVisible() && b.getStyle().isOverflowApplies())
        {
            int[] bx = borderX(b);
            clip = Math.min(clip, bx[1]);
        }
        Box nextCb = blockLike && !(b instanceof LineBox) ? b : cb;
        if(b instanceof LineBox)
        {
            checkLine((LineBox) b, cb, clip);
            if(!quiet)
                checkOverlap((LineBox) b, cb);
        }
        if(b instanceof InlineLayoutBox)
        {
            for(Object o : ((InlineLayoutBox) b).getInlineChildren())
            {
                if(o instanceof InlineText)
                {
                    if(!quiet)
                        checkText((InlineText) o, (InlineLayoutBox) b, cb, clip);
                }
                else if(o instanceof Box)
                    walk((Box) o, cb, clip, depth + 1, quiet);
            }
        }
        for(Box c : b.getChildren())
            walk(c, nextCb, clip, depth + 1, quiet);
    }

    /** @return 报了问题（里面的盒子、字就不再重复报） */
    private boolean checkBox(Box b, Box cb, int clipRight)
    {
        if(b.getStyle() != null && (b.getStyle().isAbsolute() || b.getStyle().isFixed()))
            return false;
        int[] bx = borderX(b);
        int[] c = content(cb);
        int right = Math.min(bx[1], clipRight);
        if(right > pageWidth + TOLERANCE)
        {
            problems.add(new Problem(Kind.OUT_OF_PAGE, path(b), "盒子右边到 " + right + "（页面宽 " + pageWidth + "）"));
            return true;
        }
        if(right > c[0] + c[2] + TOLERANCE && cb.getStyle() != null && cb.getStyle().isOverflowVisible())
        {
            problems.add(new Problem(Kind.BOX_OUT_OF_PARENT, path(b), "右边 " + right + " 超出父盒子内容区右边 " + (c[0] + c[2]) + "（父：" + path(cb) + "）"));
            return true;
        }
        return false;
    }

    private void checkText(InlineText t, InlineLayoutBox parent, Box cb, int clipRight)
    {
        String s = t.getSubstring();
        if(s == null || s.trim().isEmpty() || cb == null)
            return;
        int x1 = parent.getAbsX() + t.getX();
        // 字实际画出来的宽（按画图的倍数与绘制设置量），比排版量的宽时以画出来的为准
        int drawn = drawnWidth(s.replaceAll("\\s+$", ""), parent);
        if(drawn > 0 && t.getLetterSpacing() == 0 && t.getWidth() - drawnWidth(s, parent) > Math.max(2, t.getWidth() / 100))
            problems.add(new Problem(Kind.TEXT_MEASURE, path(cb), "“" + clip(s.trim()) + "”排版量的宽 " + t.getWidth() + "，画出来 " + drawnWidth(s, parent)));
        int x2 = Math.min(x1 + Math.max(t.getWidth(), drawn), clipRight);
        int[] c = content(cb);
        String what = "“" + clip(s.trim()) + "”";
        if(x2 > pageWidth + TOLERANCE)
            problems.add(new Problem(Kind.OUT_OF_PAGE, path(cb), what + " 右边到 " + x2));
        else if(x2 > c[0] + c[2] + TOLERANCE)
            problems.add(new Problem(Kind.TEXT_OUT_OF_BOX, path(cb), what + " 右边到 " + x2 + "，盒子内容区右边 " + (c[0] + c[2])));
        if(!cb.getStyle().isAutoHeight() && cb.getStyle().isOverflowVisible())
        {
            int bottom = parent.getAbsY() + parent.getHeight();
            if(bottom > c[1] + c[3] + TOLERANCE)
                problems.add(new Problem(Kind.TEXT_OUT_OF_BOX, path(cb), what + " 下边到 " + bottom + "，定高盒子内容区下边 " + (c[1] + c[3])));
        }
    }

    /**
     * 这段字按画页面时的倍数和绘制设置画出来有多宽（CSS 像素，向上取整）；不量时为 0。
     * 内核排版时在倍数 1 下量字宽，画的时候在放大后的图上画，两边字形的取整不一样时，长串字画出来会比排版量的宽。
     */
    int drawnWidth(String text, InlineLayoutBox parent)
    {
        if(scale <= 0 || text.trim().isEmpty())
            return 0;
        if(paint == null)
        {
            paint = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB).createGraphics();
            PageRenderer.paintHints(paint);
            paint.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fractional);
            paint.scale(scale, scale);
        }
        com.openhtmltopdf.render.FSFont fs = parent.getStyle().getFSFont(ctx);
        if(!(fs instanceof com.openhtmltopdf.java2d.Java2DFont))
            return 0;
        java.util.List<java.awt.Font> fonts = ((com.openhtmltopdf.java2d.Java2DFont) fs).getAWTFonts();
        if(matched)
            return (int) Math.ceil(PageRenderer.LayoutMatchedTextRenderer.advance(fonts, text) - 1e-3);
        // 内核自己的绘制器：在放大的图上用按字体分段的 AttributedString（TextLayout）画字，这里用同样的办法量
        java.text.AttributedString as = new java.text.AttributedString(text);
        int start = 0;
        for(Object[] run : PageRenderer.LayoutMatchedTextRenderer.runs(fonts, text))
        {
            int end = start + ((String) run[1]).length();
            as.addAttribute(java.awt.font.TextAttribute.FONT, run[0], start, end);
            start = end;
        }
        java.awt.font.TextLayout tl = new java.awt.font.TextLayout(as.getIterator(), paint.getFontRenderContext());
        return (int) Math.ceil(tl.getAdvance());
    }

    /** 同一行里前后两段字：前一段画出来的右边不超过后一段的左边（字之间的空格没被吃掉） */
    private void checkOverlap(LineBox line, Box cb)
    {
        if(scale <= 0)
            return;
        List<int[]> runs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        collectTexts(line, runs, texts, 0);
        for(int i = 0; i + 1 < runs.size(); i++)
        {
            int[] a = runs.get(i), b = runs.get(i + 1);
            if(b[0] >= a[0] && a[1] > b[0] + TOLERANCE)
                problems.add(new Problem(Kind.TEXT_OVERLAP, path(cb), "“" + clip(texts.get(i).trim()) + "”画到 " + a[1] + "，后面的“" + clip(texts.get(i + 1).trim())
                        + "”从 " + b[0] + " 开始"));
        }
    }

    /** 一行里的字（按排版顺序）：左边（排版位置，去掉开头的空格）、画出来的右边 */
    private void collectTexts(Box b, List<int[]> runs, List<String> texts, int depth)
    {
        if(depth > 40)
            return;
        if(b instanceof InlineLayoutBox)
        {
            InlineLayoutBox ib = (InlineLayoutBox) b;
            for(Object o : ib.getInlineChildren())
            {
                if(o instanceof InlineText)
                {
                    InlineText t = (InlineText) o;
                    String s = t.getSubstring();
                    if(s == null || s.trim().isEmpty())
                        continue;
                    String lead = s.substring(0, s.length() - s.replaceAll("^\\s+", "").length());
                    String body = s.trim();
                    int x1 = ib.getAbsX() + t.getX();
                    int start = x1 + (lead.isEmpty() ? 0 : drawnWidth("x" + lead, ib) - drawnWidth("x", ib));
                    // letter-spacing（如 MineBay 标志的 -2px）：每个字后面加上
                    int spacing = (int) Math.round(t.getLetterSpacing() * body.codePointCount(0, body.length()));
                    runs.add(new int[]{start, start + drawnWidth(body, ib) + spacing});
                    texts.add(s);
                }
                else if(o instanceof Box)
                    collectTexts((Box) o, runs, texts, depth + 1);
            }
        }
        else if(b instanceof LineBox)
        {
            for(Box c : b.getChildren())
                collectTexts(c, runs, texts, depth + 1);
        }
    }

    /** 同一行里的表单控件（按钮、文本框、下拉框）：高度一样、上边对齐 */
    private void checkLine(LineBox line, Box cb, int clipRight)
    {
        List<Box> controls = new ArrayList<>();
        collectControls(line, controls, 0);
        if(controls.size() < 2)
            return;
        int minH = Integer.MAX_VALUE, maxH = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for(Box c : controls)
        {
            int h = height(c);
            int y = c.getAbsY() + Math.max(0, (int) c.getMargin(ctx).top());
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        StringBuilder names = new StringBuilder();
        for(Box c : controls)
            names.append(names.length() == 0 ? "" : "、").append(describe(c.getElement())).append(" 高 ").append(height(c));
        if(maxH - minH > TOLERANCE)
            problems.add(new Problem(Kind.CONTROL_HEIGHT, path(cb), names.toString()));
        else if(maxY - minY > TOLERANCE)
            problems.add(new Problem(Kind.CONTROL_ALIGN, path(cb), names + "，上边相差 " + (maxY - minY)));
    }

    private int height(Box c)
    {
        return c.getHeight() - Math.max(0, (int) c.getMargin(ctx).top()) - Math.max(0, (int) c.getMargin(ctx).bottom());
    }

    private void collectControls(Box b, List<Box> out, int depth)
    {
        if(depth > 20)
            return;
        Element e = b.getElement();
        if(e != null && b != null && LayoutFixes.isSizedControl(e) && !(b instanceof LineBox))
        {
            out.add(b);
            return;
        }
        if(b instanceof InlineLayoutBox)
        {
            for(Object o : ((InlineLayoutBox) b).getInlineChildren())
            {
                if(o instanceof Box)
                    collectControls((Box) o, out, depth + 1);
            }
        }
        for(Box c : b.getChildren())
        {
            if(c instanceof LineBox)
                continue;
            collectControls(c, out, depth + 1);
        }
    }

    static String describe(Element e)
    {
        if(e == null)
            return "?";
        StringBuilder b = new StringBuilder(PageDocument.local(e));
        if(e.hasAttribute("class") && !e.getAttribute("class").isEmpty())
            b.append('.').append(e.getAttribute("class").trim().replace(' ', '.'));
        if(e.hasAttribute("name") && !e.getAttribute("name").isEmpty())
            b.append("[name=").append(e.getAttribute("name")).append(']');
        String text = e.getTextContent() == null ? "" : e.getTextContent().trim();
        if(!text.isEmpty() && text.length() < 20)
            b.append("“").append(text).append("”");
        return b.toString();
    }

    static String path(Box b)
    {
        List<String> parts = new ArrayList<>();
        Node n = b == null ? null : b.getElement();
        while(n instanceof Element && parts.size() < 4)
        {
            Element e = (Element) n;
            String s = PageDocument.local(e);
            if(e.hasAttribute("class") && !e.getAttribute("class").isEmpty())
                s += "." + e.getAttribute("class").trim().replace(' ', '.');
            parts.add(0, s);
            n = n.getParentNode();
        }
        if(parts.isEmpty())
            return "(匿名盒子)";
        return String.join(" > ", parts);
    }

    private static String clip(String s)
    {
        s = s.replaceAll("\\s+", " ");
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
