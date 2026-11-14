package com.mrcrayfish.furniture.computer;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 电脑浏览器的页面解析（服主 11-14：先用 jsoup 宽松解析，再交给 openhtmltopdf）。纯 Java，两端都用：
 * 操作者与旁观者的浏览器渲染前、服务端收旁观同步的页面时，都走这里，按同样的规则数元素与层数。
 * <ul>
 * <li>{@link Mode#HTML}（{@code text/html}、没写类型等）：jsoup 的 HTML 解析器，和浏览器一样容错（没关的标签、乱套的嵌套都能显示）；</li>
 * <li>{@link Mode#XHTML}（{@code application/xhtml+xml}、XML 类型、浏览器内置页）：jsoup 的 XML 解析器（也容错），
 * 自闭合的 {@code <div/>} 按 XML 理解；根元素不是 {@code html} 时改按 HTML 解析。</li>
 * </ul>
 * 解析前先看字节数，解析后再数元素个数与嵌套层数（{@link PageLimits}）。jsoup 不处理 DTD、不展开外部实体，HTML 命名实体按 HTML 规则解码。
 * 转成 W3C DOM（{@link #toW3c}）时元素都在 XHTML 命名空间里。
 */
public final class PageParser
{
    public static final String XHTML_NS = "http://www.w3.org/1999/xhtml";

    public enum Mode
    {
        HTML, XHTML;

        /** 按响应的 Content-Type 选 */
        public static Mode forContentType(@Nullable String contentType)
        {
            String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
            return t.startsWith("application/xhtml+xml") || t.startsWith("application/xml") || t.startsWith("text/xml") ? XHTML : HTML;
        }
    }

    /** 页面不合格（超限、空的） */
    public static final class Rejected extends Exception
    {
        public final PageLimits.Problem problem;

        Rejected(PageLimits.Problem problem)
        {
            super(problem.name());
            this.problem = problem;
        }
    }

    private PageParser() {}

    /** 解析并检查上限；不合格抛出 {@link Rejected} */
    public static Document parse(byte[] bytes, Mode mode) throws Rejected
    {
        if(bytes.length > PageLimits.MAX_BYTES)
            throw new Rejected(PageLimits.Problem.TOO_LARGE);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if(text.trim().isEmpty())
            throw new Rejected(PageLimits.Problem.MALFORMED);
        Document d;
        try
        {
            d = mode == Mode.XHTML ? parseXml(text) : null;
            if(d == null)
                d = Jsoup.parse(text, "", Parser.htmlParser());
        }
        catch(RuntimeException | StackOverflowError e)
        {
            throw new Rejected(PageLimits.Problem.MALFORMED);
        }
        checkStructure(d);
        return d;
    }

    /** XML 解析；根元素不是 html 时返回 null（改按 HTML 解析） */
    @Nullable
    private static Document parseXml(String text)
    {
        Document d = Jsoup.parse(text, "", Parser.xmlParser());
        Element root = null;
        for(Element c : d.children())
        {
            root = c;
            break;
        }
        if(root == null || d.children().size() != 1 || !root.tagName().toLowerCase(Locale.ROOT).equals("html"))
            return null;
        // 没写命名空间（或写错）的 XHTML：按 XHTML 处理，免得内核不认元素
        root.attr("xmlns", XHTML_NS);
        return d;
    }

    /** 元素个数（不算文档本身）、嵌套层数（html 是第 1 层），超了抛出 */
    static void checkStructure(Document d) throws Rejected
    {
        int elements = 0;
        Deque<Element> stack = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        for(Element c : d.children())
        {
            stack.push(c);
            depths.push(1);
        }
        while(!stack.isEmpty())
        {
            Element e = stack.pop();
            int depth = depths.pop();
            if(++elements > PageLimits.MAX_ELEMENTS)
                throw new Rejected(PageLimits.Problem.TOO_MANY_ELEMENTS);
            if(depth > PageLimits.MAX_DEPTH)
                throw new Rejected(PageLimits.Problem.TOO_DEEP);
            for(Element c : e.children())
            {
                stack.push(c);
                depths.push(depth + 1);
            }
        }
        if(elements == 0)
            throw new Rejected(PageLimits.Problem.MALFORMED);
    }

    /** 转成 W3C DOM（交给 openhtmltopdf）；元素在 XHTML 命名空间里 */
    public static org.w3c.dom.Document toW3c(Document d)
    {
        W3CDom w = new W3CDom();
        w.namespaceAware(true);
        return w.fromJsoup(d);
    }
}
