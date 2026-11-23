package com.mrcrayfish.furniture.client.browser;

import com.openhtmltopdf.css.constants.CSSName;
import com.openhtmltopdf.css.constants.IdentValue;
import com.openhtmltopdf.css.style.CalculatedStyle;
import com.openhtmltopdf.css.style.CssContext;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.InlineLayoutBox;
import com.openhtmltopdf.render.InlineText;
import com.openhtmltopdf.render.LineBox;
import com.openhtmltopdf.newtable.TableBox;
import com.openhtmltopdf.newtable.TableCellBox;
import com.openhtmltopdf.newtable.TableRowBox;
import com.openhtmltopdf.newtable.TableSectionBox;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 排版修正（纯 Java，在渲染线程上跑）：内核排好第一遍以后找出几类排版毛病，改元素的样式后再排一遍（最多几遍，见
 * {@link PageRenderer}）。只改样式、不改内容和结构；同样的页面每次改得一样（只看排版结果，没有随机、不看时间），
 * 所以操作者、旁观者、屏幕缩略图画出来的都一样。
 * <ul>
 * <li><b>定宽的盒子比容器宽</b>（网站写了 {@code width: 600px} 的框、{@code width="700"} 的表格、大图片……）：
 * 缩到正好放得下（图片宽高一起缩）。不缩的话右边伸出页面，页面又不能横着滚，就看不到了。</li>
 * <li><b>同一行的按钮、输入框、下拉框不一样高</b>：矮的加上下内边距（字仍居中），和这一行最高的一样高。</li>
 * <li><b>表格被格子里断不开的长词撑宽</b>：有长词的格子改成可以在词中间断行。</li>
 * <li><b>按钮里的字不居中</b>（网站给按钮写了高度，内核把字放在顶上）：高度不变，改成上下内边距，字竖直居中。</li>
 * </ul>
 */
final class LayoutFixes
{
    /** 比容器宽这么多像素以内的不管（取整误差） */
    static final int TOLERANCE = 1;
    /** 缩出来的盒子最窄这么宽（比这还窄说明容器本身有问题，不缩） */
    static final int MIN_WIDTH = 24;

    private final CssContext ctx;
    private final int pageWidth;
    /** 元素 → 要追加的样式（同一元素的多条合在一起） */
    private final Map<Element, Map<String, String>> changes = new LinkedHashMap<>();
    private final Map<Element, Boolean> seen = new IdentityHashMap<>();

    private LayoutFixes(CssContext ctx, int pageWidth)
    {
        this.ctx = ctx;
        this.pageWidth = pageWidth;
    }

    /**
     * 找出要改的地方并改到 DOM 上（元素的 {@code style} 属性后面追加，带 {@code !important}）。
     *
     * @return 改了没有（改了要重新排版）
     */
    static boolean apply(BlockBox root, CssContext ctx, int pageWidth)
    {
        LayoutFixes f = new LayoutFixes(ctx, pageWidth);
        f.walk(root, null, 0, false, pageWidth);
        for(Map.Entry<Element, Map<String, String>> e : f.changes.entrySet())
        {
            StringBuilder style = new StringBuilder(e.getKey().getAttribute("style").trim());
            if(style.length() > 0 && style.charAt(style.length() - 1) != ';')
                style.append(';');
            for(Map.Entry<String, String> d : e.getValue().entrySet())
                style.append(' ').append(d.getKey()).append(": ").append(d.getValue()).append(" !important;");
            e.getKey().setAttribute("style", style.toString().trim());
        }
        return !f.changes.isEmpty();
    }

    private void set(Element e, String property, String value)
    {
        changes.computeIfAbsent(e, k -> new LinkedHashMap<>()).put(property, value);
    }

    /**
     * @param inWideTable 在被撑宽的表格里（这一遍的位置、宽度不作数，等表格修好了下一遍再看）
     * @param limitRight  最右能到哪（祖先里没被撑宽的那些盒子的内容区右边，最多到页面右边）：格子被里面的东西撑宽时，
     *                    格子的右边不作数，要看表格外面的盒子
     */
    private void walk(Box b, Box cb, int depth, boolean inWideTable, int limitRight)
    {
        if(depth > 400)
            return;
        this.limitRight = limitRight;
        if(b instanceof LineBox)
            line((LineBox) b);
        else if(b instanceof BlockBox && cb != null && !inWideTable)
        {
            if(b instanceof TableBox && table((TableBox) b, cb))
                inWideTable = true;
            else
                width((BlockBox) b, cb);
        }
        if(b instanceof BlockBox && !(b instanceof LineBox))
        {
            centerButton((BlockBox) b);
            int[] c = content(b);
            if(c[0] + c[1] <= limitRight + TOLERANCE)
                limitRight = Math.min(limitRight, c[0] + c[1]);
        }
        Box nextCb = b instanceof BlockBox && !(b instanceof LineBox) ? b : cb;
        if(b instanceof InlineLayoutBox)
        {
            for(Object o : ((InlineLayoutBox) b).getInlineChildren())
            {
                if(o instanceof Box)
                    walk((Box) o, cb, depth + 1, inWideTable, limitRight);
            }
        }
        for(Box c : b.getChildren())
            walk(c, nextCb, depth + 1, inWideTable, limitRight);
    }

    /** 走到当前盒子时的 limitRight（见 walk） */
    private int limitRight = Integer.MAX_VALUE;

    // ---------------------------------------------------------------- 定宽的盒子比容器宽

    private void width(BlockBox b, Box cb)
    {
        Element e = b.getElement();
        CalculatedStyle st = b.getStyle();
        if(e == null || st == null || seen.containsKey(e) || b instanceof TableCellBox || b instanceof TableRowBox || b instanceof TableSectionBox)
            return;
        if(st.isAbsolute() || st.isFixed() || st.isFloated())
            return;
        boolean replaced = b.isReplaced();
        // 只管写死了宽度的（px、em 等）；百分比、自动宽度的比容器宽是里面的东西撑的，缩它没用
        if(!replaced && (st.isAutoWidth() || !st.hasAbsoluteUnit(CSSName.WIDTH)))
            return;
        int marginLeft = Math.max(0, (int) b.getMargin(ctx).left());
        int marginRight = Math.max(0, (int) b.getMargin(ctx).right());
        int left = b.getAbsX() + marginLeft;
        int borderWidth = b.getWidth() - marginLeft - marginRight;
        int right = left + borderWidth;
        int[] c = content(cb);
        int limit = Math.min(Math.min(c[0] + c[1], pageWidth), limitRight);
        if(right <= limit + TOLERANCE)
            return;
        int frame = (int) (b.getBorder(ctx).left() + b.getBorder(ctx).right() + b.getPadding(ctx).left() + b.getPadding(ctx).right());
        int newBorderWidth = limit - Math.max(left, c[0]);
        int newContent = newBorderWidth - frame;
        if(newContent < MIN_WIDTH)
            return;
        seen.put(e, Boolean.TRUE);
        boolean borderBox = st.isIdent(CSSName.BOX_SIZING, IdentValue.BORDER_BOX);
        set(e, "width", (borderBox ? newBorderWidth : newContent) + "px");
        set(e, "max-width", "none");
        set(e, "min-width", "0");
        if(left < c[0])
            set(e, "margin-left", "0");
        if(replaced)
        {
            int oldContent = Math.max(1, b.getContentWidth());
            int contentHeight = b.getHeight() - (int) (b.getBorder(ctx).top() + b.getBorder(ctx).bottom() + b.getPadding(ctx).top() + b.getPadding(ctx).bottom())
                    - Math.max(0, (int) b.getMargin(ctx).top()) - Math.max(0, (int) b.getMargin(ctx).bottom());
            int h = Math.max(1, (int) Math.round(contentHeight * (double) newContent / oldContent));
            set(e, "height", (borderBox ? h + (int) (b.getBorder(ctx).top() + b.getBorder(ctx).bottom() + b.getPadding(ctx).top() + b.getPadding(ctx).bottom()) : h) + "px");
        }
    }

    // ---------------------------------------------------------------- 表格被长单词撑宽

    /**
     * 表格比容器宽（格子里有长网址、长物品名这类断不开的词，表格按最长的词算列宽）：这些格子改成可以在词中间断行
     * （{@code word-wrap: break-word}）。不是所有格子都改：短词（如 {@code confirm}）照旧不断，列宽照旧按它算
     * （内核算列宽时把能断的词当成只有一个字宽，全表都改会把短词也挤断）。
     */
    /** @return 表格比容器宽，标了要断的格子（这一遍里面的东西先不看） */
    private boolean table(TableBox t, Box cb)
    {
        Element e = t.getElement();
        if(e == null || t.getStyle() == null || t.getStyle().isFloated() || t.getStyle().isAbsolute() || t.getStyle().isFixed())
            return false;
        int marginLeft = Math.max(0, (int) t.getMargin(ctx).left());
        int marginRight = Math.max(0, (int) t.getMargin(ctx).right());
        int right = t.getAbsX() + t.getWidth() - marginRight;
        int[] c = content(cb);
        int limit = Math.min(Math.min(c[0] + c[1], pageWidth), limitRight);
        if(right <= limit + TOLERANCE)
            return false;
        // 先只断很长的词（容器宽的四分之一以上）；没有这么长的再放宽到 LONG_WORD
        int marked = longWords(t, Math.max(LONG_WORD, c[1] / 4), 0);
        if(marked == 0)
            marked = longWords(t, LONG_WORD, 0);
        return marked > 0;
    }

    /** 比这宽的词算长词（像素） */
    static final int LONG_WORD = 60;

    /** 找宽度超过 threshold 的一段字，所在的格子改成可以在词中间断行；返回新标了几个格子 */
    private int longWords(Box b, int threshold, int depth)
    {
        if(depth > 400)
            return 0;
        int n = 0;
        if(b instanceof InlineLayoutBox)
        {
            for(Object o : ((InlineLayoutBox) b).getInlineChildren())
            {
                if(o instanceof InlineText && ((InlineText) o).getWidth() > threshold)
                {
                    TableCellBox cell = cellOf(b);
                    Element e = cell == null ? null : cell.getElement();
                    if(e != null && !cell.getStyle().isIdent(CSSName.WORD_WRAP, IdentValue.BREAK_WORD)
                            && !"break-word".equals(changes.getOrDefault(e, java.util.Collections.<String, String>emptyMap()).get("word-wrap")))
                    {
                        set(e, "word-wrap", "break-word");
                        n++;
                    }
                }
                else if(o instanceof Box)
                    n += longWords((Box) o, threshold, depth + 1);
            }
        }
        for(Box c : b.getChildren())
            n += longWords(c, threshold, depth + 1);
        return n;
    }

    private static TableCellBox cellOf(Box b)
    {
        for(Box p = b; p != null; p = p.getParent())
        {
            if(p instanceof TableCellBox)
                return (TableCellBox) p;
        }
        return null;
    }

    /** 包含块的内容区：左边、宽（绝对坐标） */
    private int[] content(Box cb)
    {
        int x = cb.getAbsX() + Math.max(0, (int) cb.getMargin(ctx).left()) + (int) cb.getBorder(ctx).left() + (int) cb.getPadding(ctx).left();
        return new int[]{x, cb.getContentWidth()};
    }

    // ---------------------------------------------------------------- 同一行的控件一样高

    private void line(LineBox line)
    {
        List<BlockBox> controls = new ArrayList<>();
        collectControls(line, controls, 0);
        if(controls.size() < 2)
            return;
        int max = 0, min = Integer.MAX_VALUE, minTop = Integer.MAX_VALUE, maxTop = Integer.MIN_VALUE;
        for(BlockBox c : controls)
        {
            int h = borderHeight(c);
            int top = c.getAbsY() + Math.max(0, (int) c.getMargin(ctx).top());
            max = Math.max(max, h);
            min = Math.min(min, h);
            minTop = Math.min(minTop, top);
            maxTop = Math.max(maxTop, top);
        }
        if(max - min <= TOLERANCE && maxTop - minTop <= TOLERANCE)
            return;
        // 一样高了还没对齐（控件在字号不同的行内元素里，各按各的 x 高度居中）：都贴着行顶；不一样高的先加高、居中
        String align = max - min <= TOLERANCE ? "top" : "middle";
        for(BlockBox c : controls)
        {
            int h = borderHeight(c);
            Element e = c.getElement();
            if(max - h > TOLERANCE && !seen.containsKey(e))
                grow(c, max - h);
            if(!c.getStyle().isIdent(CSSName.VERTICAL_ALIGN, align.equals("top") ? IdentValue.TOP : IdentValue.MIDDLE))
                set(e, "vertical-align", align);
            // 上下外边距会把控件挤得上下错开（网站的类名撞了，如给别处写的 .go { margin-top } 也管到了搜索按钮）
            if((int) c.getMargin(ctx).top() != 0 || (int) c.getMargin(ctx).bottom() != 0)
            {
                set(e, "margin-top", "0");
                set(e, "margin-bottom", "0");
            }
        }
    }

    /** 加高 d 像素：上下内边距各加一半（字仍居中）；写了高度的（输入框）改高度 */
    private void grow(BlockBox c, int d)
    {
        Element e = c.getElement();
        seen.put(e, Boolean.TRUE);
        CalculatedStyle st = c.getStyle();
        int top = (int) c.getPadding(ctx).top();
        int bottom = (int) c.getPadding(ctx).bottom();
        if(!st.isAutoHeight() && st.isIdent(CSSName.BOX_SIZING, IdentValue.BORDER_BOX))
        {
            set(e, "height", (borderHeight(c) + d) + "px");
            return;
        }
        set(e, "padding-top", (top + d / 2) + "px");
        set(e, "padding-bottom", (bottom + d - d / 2) + "px");
    }

    private int borderHeight(Box c)
    {
        return c.getHeight() - Math.max(0, (int) c.getMargin(ctx).top()) - Math.max(0, (int) c.getMargin(ctx).bottom());
    }

    private static void collectControls(Box b, List<BlockBox> out, int depth)
    {
        if(depth > 20)
            return;
        Element e = b.getElement();
        if(b instanceof BlockBox && !(b instanceof LineBox) && e != null && isSizedControl(e))
        {
            out.add((BlockBox) b);
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
        if(b instanceof LineBox || b instanceof InlineLayoutBox)
        {
            for(Box c : b.getChildren())
                collectControls(c, out, depth + 1);
        }
    }

    /** 一行里要一样高的控件：按钮、单行文本框、密码框、下拉框（勾选框、单选、多行文本框、画板不算） */
    static boolean isSizedControl(Element e)
    {
        if(!e.hasAttribute(PageDocument.N_ATTR))
            return false;
        String tag = PageDocument.local(e);
        if(tag.equals("button") || tag.equals("select"))
            return true;
        if(!tag.equals("input"))
            return false;
        String type = e.getAttribute("type").toLowerCase(Locale.ROOT);
        return type.isEmpty() || type.equals("text") || type.equals("password") || type.equals("search") || type.equals("email") || type.equals("number")
                || type.equals("url") || type.equals("tel");
    }

    // ---------------------------------------------------------------- 按钮里的字竖直居中

    /** 写了高度的按钮：字在顶上，空出来的高度改成上下内边距 */
    private void centerButton(BlockBox b)
    {
        Element e = b.getElement();
        if(e == null || seen.containsKey(e) || !PageDocument.local(e).equals("button") || b.getStyle() == null || b.getStyle().isAutoHeight())
            return;
        int top = (int) (b.getBorder(ctx).top() + b.getPadding(ctx).top());
        int bottom = (int) (b.getBorder(ctx).bottom() + b.getPadding(ctx).bottom());
        int inner = borderHeight(b) - top - bottom;
        int used = 0;
        for(Box c : b.getChildren())
            used = Math.max(used, c.getY() + c.getHeight());
        // 子盒子的 y 从内容区顶上量起
        int free = inner - used;
        if(used <= 0 || free <= TOLERANCE)
            return;
        seen.put(e, Boolean.TRUE);
        boolean borderBox = b.getStyle().isIdent(CSSName.BOX_SIZING, IdentValue.BORDER_BOX);
        set(e, "height", (borderBox ? borderHeight(b) : used) + "px");
        set(e, "padding-top", ((int) b.getPadding(ctx).top() + free / 2) + "px");
        set(e, "padding-bottom", ((int) b.getPadding(ctx).bottom() + free - free / 2) + "px");
    }
}
