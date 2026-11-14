package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.PageLimits;
import com.mrcrayfish.furniture.computer.PageParser;
import com.mrcrayfish.furniture.computer.UrlInput;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 浏览器拿到的一页（纯 Java，不碰 Minecraft；两端都用：服务端用它从旁观同步的页面里找出图片所在的主机、密码框）：
 * 先用 jsoup 宽松解析（{@link PageParser}，不加载 DTD、外部实体），按 {@link PageLimits} 检查大小与结构，再转成 W3C DOM 整理：
 * <ul>
 * <li>去掉脚本、外部样式表、框架等（没有 JavaScript，也不加载外部资源）；</li>
 * <li>{@code <input type="submit">} 等改写成 {@code <button>}，好让内核按文字排版；</li>
 * <li>给链接、表单控件、物品图标编号（{@code data-yr-n}），渲染后按编号找到它们在页面上的位置；</li>
 * <li>{@code data-yr-item-name="注册名[@数据值]"} 的空元素填上本地化的物品名（服务器不知道玩家的语言）；</li>
 * <li>注入默认样式（字体、表单控件大小、页面宽度）。</li>
 * </ul>
 * HTML 的松散写法（不闭合的标签等）和 XHTML 都认；HTML 命名实体按 HTML 规则解码。
 * 图片只收和页面同一主机的（{@link UrlInput#sameHost}），每页最多 {@value #MAX_IMAGES} 张。
 */
public final class PageDocument
{
    public static final String XHTML_NS = "http://www.w3.org/1999/xhtml";
    public static final String N_ATTR = "data-yr-n";
    /** 一页最多这么多张图（只有操作者加载） */
    public static final int MAX_IMAGES = 16;
    /** 一页最多这么多个表单控件 */
    public static final int MAX_CONTROLS = 128;

    public final Document dom;
    public final String baseUrl;
    public final String title;
    public final List<FormInfo> forms;
    public final List<Control> controls;
    /** 编号 → 链接目标（不支持的链接为 null） */
    public final Map<Integer, String> links;
    /** 编号 → 物品写法（画图标） */
    public final Map<Integer, String> items;
    /** 要加载的图片（解析好的绝对网址） */
    public final List<String> images;

    public static final class FormInfo
    {
        @Nullable
        public final String action;
        public final boolean post;

        FormInfo(@Nullable String action, boolean post)
        {
            this.action = action;
            this.post = post;
        }
    }

    public enum Kind
    {
        TEXT, PASSWORD, HIDDEN, CHECKBOX, RADIO, SELECT, TEXTAREA, SUBMIT, RESET, BUTTON
    }

    public static final class Control
    {
        public final int n;
        public final Kind kind;
        public final String name;
        public final String value;
        public final boolean checked;
        public final List<String> optionValues;
        public final List<String> optionLabels;
        public final int selected;
        /** 所在表单的序号；不在表单里为 -1 */
        public final int form;
        public final int maxLength;
        public final boolean disabled;

        Control(int n, Kind kind, String name, String value, boolean checked, List<String> optionValues, List<String> optionLabels, int selected, int form, int maxLength, boolean disabled)
        {
            this.n = n;
            this.kind = kind;
            this.name = name;
            this.value = value;
            this.checked = checked;
            this.optionValues = optionValues;
            this.optionLabels = optionLabels;
            this.selected = selected;
            this.form = form;
            this.maxLength = maxLength;
            this.disabled = disabled;
        }

        public boolean isTextual()
        {
            return kind == Kind.TEXT || kind == Kind.PASSWORD || kind == Kind.TEXTAREA;
        }
    }

    /** 页面有问题（超限、不是 XHTML） */
    public static final class Invalid extends Exception
    {
        @Nullable
        public final PageLimits.Problem problem;

        Invalid(@Nullable PageLimits.Problem problem, String message)
        {
            super(message);
            this.problem = problem;
        }
    }

    private PageDocument(Document dom, String baseUrl, String title, List<FormInfo> forms, List<Control> controls,
                         Map<Integer, String> links, Map<Integer, String> items, List<String> images)
    {
        this.dom = dom;
        this.baseUrl = baseUrl;
        this.title = title;
        this.forms = Collections.unmodifiableList(forms);
        this.controls = Collections.unmodifiableList(controls);
        this.links = Collections.unmodifiableMap(links);
        this.items = Collections.unmodifiableMap(items);
        this.images = Collections.unmodifiableList(images);
    }

    @Nullable
    public Control control(int n)
    {
        for(Control c : controls)
        {
            if(c.n == n)
                return c;
        }
        return null;
    }

    /**
     * 按 XHTML 解析（浏览器内置页、{@code application/xhtml+xml}），见 {@link #parse(byte[], PageParser.Mode, String, Function, int)}
     */
    public static PageDocument parse(byte[] page, String baseUrl, Function<String, String> itemName, int viewWidth) throws Invalid
    {
        return parse(page, PageParser.Mode.XHTML, baseUrl, itemName, viewWidth);
    }

    /**
     * 先用 jsoup 宽松解析并按 {@link PageLimits} 检查（{@link PageParser}），再转成 W3C DOM 整理。
     *
     * @param mode     按 HTML 还是 XHTML 解析（由 Content-Type 定）
     * @param itemName 物品写法 → 本地化名字（不认识返回 null）
     * @param viewWidth 页面宽度（CSS 像素）
     */
    public static PageDocument parse(byte[] page, PageParser.Mode mode, String baseUrl, Function<String, String> itemName, int viewWidth) throws Invalid
    {
        Document dom;
        try
        {
            dom = PageParser.toW3c(PageParser.parse(page, mode));
        }
        catch(PageParser.Rejected r)
        {
            throw new Invalid(r.problem, r.problem.name());
        }
        catch(RuntimeException | StackOverflowError e)
        {
            throw new Invalid(PageLimits.Problem.MALFORMED, e.toString());
        }
        Element root = dom.getDocumentElement();
        if(root == null || !local(root).equals("html"))
            throw new Invalid(PageLimits.Problem.MALFORMED, "根元素不是 html");
        return new Builder(dom, baseUrl, itemName, viewWidth).build();
    }

    static String local(Node n)
    {
        String l = n.getLocalName();
        return (l == null ? n.getNodeName() : l).toLowerCase(Locale.ROOT);
    }

    /** 注入的默认样式（网站的样式写在后面，能覆盖） */
    static String userAgentCss()
    {
        return "html { font-family: 'YR Sans SC', sans-serif; font-size: 14px; background: #fff; color: #000; }\n"
                + "body { margin: 8px; }\n"
                + "a { color: #0645ad; text-decoration: underline; }\n"
                + "input, textarea, select { display: inline-block; width: 160px; height: 18px; padding: 2px 3px; border: 1px solid #767676; background: #fff; vertical-align: middle; }\n"
                + "textarea { height: 18px; }\n"
                + "select { width: 120px; }\n"
                + "input[type=\"checkbox\"], input[type=\"radio\"] { width: 13px; height: 13px; padding: 0; margin: 0; }\n"
                + "input[type=\"hidden\"] { display: none; }\n"
                + "button { display: inline-block; padding: 2px 8px; border: 1px solid #767676; background: #efefef; vertical-align: middle; }\n"
                + "script, style, head, title { display: none; }\n";
    }

    private static final class Builder
    {
        final Document dom;
        final String baseUrl;
        final Function<String, String> itemName;
        final int viewWidth;
        final List<FormInfo> forms = new ArrayList<>();
        final Map<Element, Integer> formIndex = new HashMap<>();
        final List<Control> controls = new ArrayList<>();
        final Map<Integer, String> links = new HashMap<>();
        final Map<Integer, String> items = new HashMap<>();
        final Set<String> images = new LinkedHashSet<>();
        int next;

        Builder(Document dom, String baseUrl, Function<String, String> itemName, int viewWidth)
        {
            this.dom = dom;
            this.baseUrl = baseUrl;
            this.itemName = itemName;
            this.viewWidth = viewWidth;
        }

        PageDocument build()
        {
            Element root = dom.getDocumentElement();
            String title = "";
            NodeList titles = root.getElementsByTagNameNS("*", "title");
            if(titles.getLength() > 0)
                title = titles.item(0).getTextContent().trim().replaceAll("\\s+", " ");
            if(title.length() > 200)
                title = title.substring(0, 200);
            walk(root);
            injectStyles(root);
            return new PageDocument(dom, baseUrl, title, forms, controls, links, items, new ArrayList<>(images));
        }

        private void walk(Element e)
        {
            List<Element> children = new ArrayList<>();
            for(Node c = e.getFirstChild(); c != null; c = c.getNextSibling())
            {
                if(c instanceof Element)
                    children.add((Element) c);
            }
            for(Element c : children)
            {
                String tag = local(c);
                if(tag.equals("script") || tag.equals("link") || tag.equals("iframe") || tag.equals("frame") || tag.equals("frameset")
                        || tag.equals("object") || tag.equals("embed") || tag.equals("applet") || tag.equals("base") || tag.equals("meta") || tag.equals("noscript"))
                {
                    e.removeChild(c);
                    continue;
                }
                Element el = c;
                if(tag.equals("input"))
                {
                    String type = c.getAttribute("type").toLowerCase(Locale.ROOT);
                    if(type.equals("submit") || type.equals("reset") || type.equals("button") || type.equals("image"))
                        el = toButton(c, type);
                }
                visit(el);
                walk(el);
            }
        }

        private Element toButton(Element input, String type)
        {
            Element b = dom.createElementNS(XHTML_NS, "button");
            for(int i = 0; i < input.getAttributes().getLength(); i++)
            {
                Node a = input.getAttributes().item(i);
                if(!a.getNodeName().equalsIgnoreCase("type"))
                    b.setAttribute(a.getNodeName(), a.getNodeValue());
            }
            b.setAttribute("type", type.equals("image") ? "submit" : type);
            String label = input.getAttribute("value");
            if(label.isEmpty())
                label = type.equals("reset") ? "重置" : "提交";
            b.appendChild(dom.createTextNode(label));
            input.getParentNode().replaceChild(b, input);
            return b;
        }

        private int number(Element e)
        {
            int n = next++;
            e.setAttribute(N_ATTR, Integer.toString(n));
            return n;
        }

        private void visit(Element e)
        {
            String tag = local(e);
            String item = e.getAttribute("data-yr-item");
            if(!item.isEmpty() && items.size() < 256)
            {
                items.put(number(e), item);
            }
            String nameOf = e.getAttribute("data-yr-item-name");
            if(!nameOf.isEmpty() && !hasElementChildren(e))
            {
                String name = itemName.apply(nameOf);
                if(name != null)
                    e.setTextContent(name);
            }
            switch(tag)
            {
                case "a":
                {
                    if(e.hasAttribute("href"))
                    {
                        int n = e.hasAttribute(N_ATTR) ? Integer.parseInt(e.getAttribute(N_ATTR)) : number(e);
                        links.put(n, UrlInput.resolve(baseUrl, e.getAttribute("href")));
                    }
                    break;
                }
                case "img":
                {
                    String src = UrlInput.resolve(baseUrl, e.getAttribute("src"));
                    if(src != null && !src.equals(UrlInput.HOME) && UrlInput.sameHost(baseUrl, src) && images.size() < MAX_IMAGES)
                        images.add(src);
                    break;
                }
                case "form":
                {
                    String action = e.hasAttribute("action") && !e.getAttribute("action").isEmpty() ? UrlInput.resolve(baseUrl, e.getAttribute("action")) : baseUrl;
                    boolean post = e.getAttribute("method").equalsIgnoreCase("post");
                    formIndex.put(e, forms.size());
                    forms.add(new FormInfo(action, post));
                    break;
                }
                case "input":
                case "button":
                case "select":
                case "textarea":
                    control(e, tag);
                    break;
                default:
                    break;
            }
        }

        private static boolean hasElementChildren(Element e)
        {
            for(Node c = e.getFirstChild(); c != null; c = c.getNextSibling())
            {
                if(c instanceof Element)
                    return true;
            }
            return false;
        }

        private void control(Element e, String tag)
        {
            if(controls.size() >= MAX_CONTROLS)
                return;
            Kind kind;
            String type = e.getAttribute("type").toLowerCase(Locale.ROOT);
            switch(tag)
            {
                case "button":
                    kind = type.equals("reset") ? Kind.RESET : type.equals("button") ? Kind.BUTTON : Kind.SUBMIT;
                    break;
                case "select":
                    kind = Kind.SELECT;
                    break;
                case "textarea":
                    kind = Kind.TEXTAREA;
                    break;
                default:
                    switch(type)
                    {
                        case "password": kind = Kind.PASSWORD; break;
                        case "hidden": kind = Kind.HIDDEN; break;
                        case "checkbox": kind = Kind.CHECKBOX; break;
                        case "radio": kind = Kind.RADIO; break;
                        case "file": return;
                        default: kind = Kind.TEXT;
                    }
            }
            int form = -1;
            for(Node p = e.getParentNode(); p != null; p = p.getParentNode())
            {
                if(p instanceof Element && formIndex.containsKey(p))
                {
                    form = formIndex.get(p);
                    break;
                }
            }
            int n = e.hasAttribute(N_ATTR) ? Integer.parseInt(e.getAttribute(N_ATTR)) : number(e);
            List<String> values = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            int selected = 0;
            String value;
            if(kind == Kind.SELECT)
            {
                NodeList opts = e.getElementsByTagNameNS("*", "option");
                for(int i = 0; i < opts.getLength() && i < 256; i++)
                {
                    Element o = (Element) opts.item(i);
                    String label = o.getTextContent().trim();
                    values.add(o.hasAttribute("value") ? o.getAttribute("value") : label);
                    labels.add(label);
                    if(o.hasAttribute("selected"))
                        selected = i;
                }
                value = values.isEmpty() ? "" : values.get(selected);
            }
            else if(kind == Kind.TEXTAREA)
            {
                value = e.getTextContent();
                // 内核不画 textarea 的内容（由界面画）
                e.setTextContent("");
            }
            else if(kind == Kind.SUBMIT || kind == Kind.RESET || kind == Kind.BUTTON)
            {
                value = e.getAttribute("value");
            }
            else
            {
                value = e.hasAttribute("value") ? e.getAttribute("value") : (kind == Kind.CHECKBOX || kind == Kind.RADIO ? "on" : "");
            }
            int maxLength = 512;
            String ml = e.getAttribute("maxlength");
            if(ml.matches("\\d{1,4}"))
                maxLength = Math.max(0, Math.min(maxLength, Integer.parseInt(ml)));
            controls.add(new Control(n, kind, e.getAttribute("name"), value, e.hasAttribute("checked"), values, labels, selected, form, maxLength, e.hasAttribute("disabled")));
        }

        private void injectStyles(Element root)
        {
            Element head = null;
            for(Node c = root.getFirstChild(); c != null; c = c.getNextSibling())
            {
                if(c instanceof Element && local(c).equals("head"))
                {
                    head = (Element) c;
                    break;
                }
            }
            if(head == null)
            {
                head = dom.createElementNS(XHTML_NS, "head");
                root.insertBefore(head, root.getFirstChild());
            }
            Element ua = dom.createElementNS(XHTML_NS, "style");
            ua.setAttribute("type", "text/css");
            ua.setTextContent(userAgentCss());
            head.insertBefore(ua, head.getFirstChild());
            // 页面大小由浏览器定（宽固定、高够长，最后按内容裁）：放在最后，网站改不了
            Element page = dom.createElementNS(XHTML_NS, "style");
            page.setAttribute("type", "text/css");
            page.setTextContent("@page { size: " + viewWidth + "px 30000px; margin: 0; }");
            head.appendChild(page);
        }
    }
}
