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
        TEXT_OUT_OF_BOX, BOX_OUT_OF_PARENT, OUT_OF_PAGE, CONTROL_HEIGHT, CONTROL_ALIGN
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

    LayoutProbe(int pageWidth)
    {
        this.pageWidth = pageWidth;
    }

    /** 渲染一页并检查，返回发现的问题（同时给出渲染好的页面，存图用） */
    static Result check(PageDocument doc, Map<String, byte[]> images, double scale) throws IOException
    {
        LayoutProbe p = new LayoutProbe(PageRenderer.VIEW_WIDTH);
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
        walk(root, null, Integer.MAX_VALUE, 0, false);
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
            checkLine((LineBox) b, cb, clip);
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
        int x2 = Math.min(x1 + t.getWidth(), clipRight);
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
