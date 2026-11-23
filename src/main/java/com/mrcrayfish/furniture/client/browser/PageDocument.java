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
import java.util.regex.Pattern;

/**
 * 浏览器拿到的一页（纯 Java，不碰 Minecraft；两端都用：服务端用它从旁观同步的页面里找出图片所在的主机、密码框）：
 * 先用 jsoup 宽松解析（{@link PageParser}，不加载 DTD、外部实体），按 {@link PageLimits} 检查大小与结构，再转成 W3C DOM 整理：
 * <ul>
 * <li>去掉脚本、外部样式表、框架等（没有 JavaScript，也不加载外部资源）；</li>
 * <li>{@code <input type="submit">} 等改写成 {@code <button>}，好让内核按文字排版；</li>
 * <li>给链接、表单控件、物品图标编号（{@code data-yr-n}），渲染后按编号找到它们在页面上的位置；</li>
 * <li>{@code data-yr-item-name="注册名[@数据值]"} 的空元素填上本地化的物品名（服务器不知道玩家的语言）；</li>
 * <li>{@code data-yr-lang="翻译键"} 的元素（里面只有文字时）换成玩家语言里的这句（附魔名、药水效果名等；键只能是字母数字与 {@code ._-}，翻不了时保留原文）；</li>
 * <li>注入默认样式（字体、表单控件大小、页面宽度）。</li>
 * </ul>
 * HTML 的松散写法（不闭合的标签等）和 XHTML 都认；HTML 命名实体按 HTML 规则解码。
 * 图片只收和页面同一主机的（{@link UrlInput#sameHost}），另外任何网站都可以用 {@value #CAPTCHA_HOST} 的验证码图片（服主 11-15），
 * 每页最多 {@value #MAX_IMAGES} 张。
 */
public final class PageDocument
{
    public static final String XHTML_NS = "http://www.w3.org/1999/xhtml";

    /** 页面里的图片能不能取：同一主机，或者 captcha.yr 的验证码图片（{@code /img/} 下） */
    static boolean imageAllowed(String pageUrl, String src)
    {
        if(UrlInput.sameHost(pageUrl, src))
            return true;
        String host = UrlInput.hostOf(src);
        return CAPTCHA_HOST.equals(host) && (src.startsWith("http://" + CAPTCHA_HOST + "/img/") || src.startsWith("https://" + CAPTCHA_HOST + "/img/"));
    }
    public static final String N_ATTR = "data-yr-n";
    /** 一页最多这么多张图（只有操作者加载） */
    public static final int MAX_IMAGES = 16;
    /** YouranMod 的人机验证服务：任何网站都能在自己的表单里放它的验证码图片 */
    public static final String CAPTCHA_HOST = "captcha.yr";
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
    /** 自动刷新（{@code <meta http-equiv="refresh">}）；没有为 null */
    @Nullable
    public final Refresh refresh;

    /**
     * 页面要求的自动刷新：{@code <meta http-equiv="refresh" content="N">} 或 {@code content="N;url=网址"}（服主 11-15）。
     * 间隔最少 {@value #MIN_SECONDS} 秒（写得更短的按 {@value #MIN_SECONDS} 秒）；网址只许同站（{@code CookieJar.sameSite}），别的站的、
     * 解析不了的整个不算（不刷新）。只认第一个。什么时候真刷由 {@code Browser} 定（表单里填了东西就不刷）。
     */
    public static final class Refresh
    {
        public static final int MIN_SECONDS = 2;
        public static final int MAX_SECONDS = 86400;

        public final int seconds;
        /** 刷新到哪（绝对网址；没写 url 时是页面自己） */
        public final String url;

        Refresh(int seconds, String url)
        {
            this.seconds = seconds;
            this.url = url;
        }

        /** 按 HTML 标准的写法读 {@code content}（简化：整数秒，可选 {@code ;} 或 {@code ,} 后跟 {@code url=}，网址可加引号）；不认识返回 null */
        @Nullable
        static Refresh parse(String content, String baseUrl)
        {
            if(content == null)
                return null;
            String c = content.trim();
            int i = 0;
            while(i < c.length() && Character.isDigit(c.charAt(i)))
                i++;
            if(i == 0 || i > 6)
                return null;
            int seconds = Integer.parseInt(c.substring(0, i));
            // 小数部分不管
            while(i < c.length() && (Character.isDigit(c.charAt(i)) || c.charAt(i) == '.'))
                i++;
            String rest = c.substring(i).trim();
            String target = baseUrl;
            if(!rest.isEmpty())
            {
                if(rest.charAt(0) != ';' && rest.charAt(0) != ',')
                    return null;
                rest = rest.substring(1).trim();
                if(rest.regionMatches(true, 0, "url", 0, 3))
                {
                    String r = rest.substring(3).trim();
                    if(!r.startsWith("="))
                        return null;
                    rest = r.substring(1).trim();
                }
                if(rest.length() >= 2 && (rest.charAt(0) == '"' || rest.charAt(0) == '\''))
                {
                    char q = rest.charAt(0);
                    int end = rest.indexOf(q, 1);
                    rest = end < 0 ? rest.substring(1) : rest.substring(1, end);
                }
                if(!rest.isEmpty())
                    target = UrlInput.resolve(baseUrl, rest);
            }
            if(target == null || target.equals(UrlInput.HOME) || !com.mrcrayfish.furniture.computer.CookieJar.sameSite(baseUrl, target))
                return null;
            return new Refresh(Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds)), target);
        }
    }

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
        TEXT, PASSWORD, HIDDEN, CHECKBOX, RADIO, SELECT, TEXTAREA, SUBMIT, RESET, BUTTON,
        /** 画板（{@code <input type="x-yr-canvas">}）：{@link Control#value} 是页面给的初始图（data URL，可以为空） */
        CANVAS
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
        /** 位置字段（隐藏字段；{@link #value} 是网站给的随机数，提交时换成位置证明） */
        public final boolean location;
        /** 画板的宽、高（像素）；不是画板为 0 */
        public final int canvasWidth;
        public final int canvasHeight;

        Control(int n, Kind kind, String name, String value, boolean checked, List<String> optionValues, List<String> optionLabels, int selected, int form, int maxLength, boolean disabled)
        {
            this(n, kind, name, value, checked, optionValues, optionLabels, selected, form, maxLength, disabled, false);
        }

        Control(int n, Kind kind, String name, String value, boolean checked, List<String> optionValues, List<String> optionLabels, int selected, int form, int maxLength, boolean disabled, boolean location)
        {
            this(n, kind, name, value, checked, optionValues, optionLabels, selected, form, maxLength, disabled, location, 0, 0);
        }

        Control(int n, Kind kind, String name, String value, boolean checked, List<String> optionValues, List<String> optionLabels, int selected, int form, int maxLength, boolean disabled, boolean location, int canvasWidth, int canvasHeight)
        {
            this.canvasWidth = canvasWidth;
            this.canvasHeight = canvasHeight;
            this.location = location;
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
                         Map<Integer, String> links, Map<Integer, String> items, List<String> images, @Nullable Refresh refresh)
    {
        this.refresh = refresh;
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

    /** 按 XHTML 解析，带翻译（{@code data-yr-lang}） */
    public static PageDocument parse(byte[] page, String baseUrl, Function<String, String> itemName, Function<String, String> lang, int viewWidth) throws Invalid
    {
        return parse(page, PageParser.Mode.XHTML, baseUrl, itemName, lang, viewWidth);
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
        return parse(page, mode, baseUrl, itemName, key -> null, viewWidth);
    }

    /** @param lang 翻译键 → 玩家语言里的文字（没有返回 null） */
    public static PageDocument parse(byte[] page, PageParser.Mode mode, String baseUrl, Function<String, String> itemName, Function<String, String> lang, int viewWidth) throws Invalid
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
        return new Builder(dom, baseUrl, itemName, lang, viewWidth).build();
    }

    static final Pattern LANG_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    /** 位置字段的类型（悠然服务器的约定，见 {@code Browser#submitForm}） */
    public static final String LOCATION_TYPE = "x-yr-location";
    static final String LOCATION_ATTR = "data-yr-location";
    /**
     * 画板的类型（悠然服务器的约定，服主 11-15）：{@code <input type="x-yr-canvas" name="…" width="宽" height="高" [value="data:image/png;base64,…"] />}。
     * 浏览器显示成像素画板，提交时字段值是 PNG 的 data URL（见 {@code computer.PixelCanvas}）。
     */
    public static final String CANVAS_TYPE = "x-yr-canvas";
    static final String CANVAS_ATTR = "data-yr-canvas";

    static String local(Node n)
    {
        String l = n.getLocalName();
        return (l == null ? n.getNodeName() : l).toLowerCase(Locale.ROOT);
    }

    /**
     * 注入的默认样式（网站的样式写在后面，能覆盖）：
     * <ul>
     * <li>长单词、长网址到了行尾就断开（{@code word-wrap: break-word}），{@code <pre>} 也自动换行，
     * 不会伸出盒子、伸出页面（页面没有横向滚动）；中文本来就按字断。表格里默认不断（内核算列宽时把能断的词当成一个字宽，
     * 短词也会被挤断），表格被长词撑宽时由排版修正只让有长词的格子断；</li>
     * <li>输入框、按钮竖直居中对齐（同一行的一样高由排版修正保证，见 {@link LayoutFixes}）；
     * 按钮的字用黑色（同真浏览器，不随外面的颜色变：深色标题栏里的“退出”按钮不再看不见）；</li>
     * <li>图片最宽是所在盒子的宽（宽高一起缩，见 {@link PageRenderer} 的排版修正）。</li>
     * </ul>
     */
    static String userAgentCss()
    {
        return "html { font-family: 'YR Sans', sans-serif; font-size: 14px; background: #fff; color: #000; word-wrap: break-word; }\n"
                + "body { margin: 8px; }\n"
                + "a { color: #0645ad; text-decoration: underline; }\n"
                + "pre { white-space: pre-wrap; }\n"
                + "table { word-wrap: normal; }\n"
                + "input, textarea, select { display: inline-block; width: 160px; height: 18px; padding: 2px 3px; border: 1px solid #767676; background: #fff; vertical-align: middle; }\n"
                + "textarea { height: 18px; }\n"
                + "select { width: 120px; }\n"
                + "input[type=\"checkbox\"], input[type=\"radio\"] { width: 13px; height: 13px; padding: 0; margin: 0 3px 0 4px; }\n"
                + "input[type=\"hidden\"] { display: none; }\n"
                + "button { display: inline-block; height: auto; padding: 2px 8px; border: 1px solid #767676; background: #efefef; color: #000; vertical-align: middle; text-align: center; text-decoration: none; }\n"
                + "script, style, head, title { display: none; }\n";
    }

    /**
     * 文本框的 {@code size="N"}：约 N 个字宽（每个字 7.5 像素，约等于真浏览器里表单字号的平均字宽），不写时默认 160 像素。写在默认样式里，
     * 网站写了更具体的宽度（如 {@code input.q}）照样按网站的。
     */
    static String sizeCss(java.util.Collection<Integer> sizes)
    {
        StringBuilder b = new StringBuilder();
        for(int n : sizes)
        {
            b.append("input[size=\"").append(n).append("\"] { width: ").append((n * 15 + 1) / 2).append("px; }\n");
        }
        return b.toString();
    }

    private static final class Builder
    {
        final Document dom;
        final String baseUrl;
        final Function<String, String> itemName;
        final Function<String, String> lang;
        final int viewWidth;
        final List<FormInfo> forms = new ArrayList<>();
        final Map<Element, Integer> formIndex = new HashMap<>();
        final List<Control> controls = new ArrayList<>();
        final Map<Integer, String> links = new HashMap<>();
        final Map<Integer, String> items = new HashMap<>();
        final Set<String> images = new LinkedHashSet<>();
        /** 文本框写的 size（几个字宽），注入默认样式时按它定宽 */
        final java.util.TreeSet<Integer> sizes = new java.util.TreeSet<>();
        Refresh refresh;
        boolean refreshSeen;
        int next;

        Builder(Document dom, String baseUrl, Function<String, String> itemName, Function<String, String> lang, int viewWidth)
        {
            this.lang = lang;
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
            adaptSiteStyles(root);
            injectStyles(root);
            return new PageDocument(dom, baseUrl, title, forms, controls, links, items, new ArrayList<>(images), refresh);
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
                if(tag.equals("meta") && refresh == null && !refreshSeen && c.getAttribute("http-equiv").trim().equalsIgnoreCase("refresh"))
                {
                    // 只认第一个（不认识的也算看过了）
                    refreshSeen = true;
                    refresh = Refresh.parse(c.getAttribute("content"), baseUrl);
                }
                if(tag.equals("script") || tag.equals("link") || tag.equals("iframe") || tag.equals("frame") || tag.equals("frameset")
                        || tag.equals("object") || tag.equals("embed") || tag.equals("applet") || tag.equals("base") || tag.equals("meta") || tag.equals("noscript"))
                {
                    e.removeChild(c);
                    continue;
                }
                Element el = c;
                // 位置字段、画板的标记只由浏览器自己加（页面自己写的不算）
                c.removeAttribute(LOCATION_ATTR);
                c.removeAttribute(CANVAS_ATTR);
                c.removeAttribute(InputSelectors.ATTR);
                Element locationLabel = null;
                if(tag.equals("input"))
                {
                    String type = c.getAttribute("type").toLowerCase(Locale.ROOT);
                    if(type.equals("submit") || type.equals("reset") || type.equals("button") || type.equals("image"))
                        el = toButton(c, type);
                    else if(type.equals(LOCATION_TYPE))
                        locationLabel = toLocationField(c);
                    else if(type.equals(CANVAS_TYPE))
                        el = toCanvas(c);
                }
                visit(el);
                walk(el);
                if(locationLabel != null)
                    visit(locationLabel);
            }
        }

        /**
         * 位置字段（{@code <input type="x-yr-location" name="…" value="网站给的随机数" />}，悠然服务器的约定）：
         * 改成隐藏字段（提交时浏览器先请服务器签位置，再把证明或 {@code error:代号} 填进去），后面加一句说明给人看。
         */
        private Element toLocationField(Element input)
        {
            input.setAttribute("type", "hidden");
            input.setAttribute(LOCATION_ATTR, "1");
            Element label = dom.createElementNS(XHTML_NS, "span");
            label.setAttribute("style", "color: #2b4f81;");
            String text = lang.apply("cfm.browser.location.field");
            label.appendChild(dom.createTextNode(text != null ? text : "[提交时附上这台电脑的位置（要先在确认框里同意）]"));
            Node next = input.getNextSibling();
            if(next == null)
                input.getParentNode().appendChild(label);
            else
                input.getParentNode().insertBefore(label, next);
            return label;
        }

        /**
         * 画板：换成一个定好大小的方块（宽高 = 像素 × 显示倍数），界面在它上面画像素、点它打开编辑器。尺寸不对时换成一句说明，不算控件。
         */
        private Element toCanvas(Element input)
        {
            int w = canvasSide(input.getAttribute("width"));
            int h = canvasSide(input.getAttribute("height"));
            Element box = dom.createElementNS(XHTML_NS, "span");
            if(!moe.qingu.youran.api.image.DyePng.validSize(w, h))
            {
                String text = lang.apply("cfm.browser.canvas.bad_size");
                box.appendChild(dom.createTextNode(text != null ? text : "[画板尺寸不对]"));
                box.setAttribute("style", "color: #b02e26;");
                input.getParentNode().replaceChild(box, input);
                return box;
            }
            int z = com.mrcrayfish.furniture.computer.PixelCanvas.previewZoom(w, h);
            for(int i = 0; i < input.getAttributes().getLength(); i++)
            {
                Node a = input.getAttributes().item(i);
                String an = a.getNodeName().toLowerCase(Locale.ROOT);
                if(an.equals("name") || an.equals("value") || an.equals("disabled") || an.equals("id") || an.equals("class"))
                    box.setAttribute(an, a.getNodeValue());
            }
            box.setAttribute(CANVAS_ATTR, w + "x" + h);
            box.setAttribute("style", "display: inline-block; width: " + w * z + "px; height: " + h * z
                    + "px; border: 1px solid #767676; background: #f4f4f4; vertical-align: top; margin: 0; padding: 0;");
            input.getParentNode().replaceChild(box, input);
            return box;
        }

        /** 宽、高的写法：1–4 位数字；没写为默认 64，写得不对为 0 */
        private static int canvasSide(String v)
        {
            String t = v == null ? "" : v.trim();
            if(t.isEmpty())
                return com.mrcrayfish.furniture.computer.PixelCanvas.DEFAULT_SIZE;
            return t.matches("\\d{1,4}") ? Integer.parseInt(t) : 0;
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
            // 网站写给 input 的样式（input.go 之类）照样管这个按钮，见 InputSelectors
            b.setAttribute(InputSelectors.ATTR, type);
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
            String key = e.getAttribute("data-yr-lang");
            if(!key.isEmpty() && LANG_KEY.matcher(key).matches() && !hasElementChildren(e))
            {
                String text = lang.apply(key);
                if(text != null)
                    e.setTextContent(text.length() > 200 ? text.substring(0, 200) : text);
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
                    if(src != null && !src.equals(UrlInput.HOME) && imageAllowed(baseUrl, src) && images.size() < MAX_IMAGES)
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
                case "span":
                    if(e.hasAttribute(CANVAS_ATTR))
                        control(e, "canvas");
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
                case "canvas":
                    kind = Kind.CANVAS;
                    break;
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
            if((kind == Kind.TEXT || kind == Kind.PASSWORD) && tag.equals("input"))
            {
                String size = e.getAttribute("size").trim();
                if(size.matches("\\d{1,3}") && Integer.parseInt(size) > 0 && sizes.size() < 32)
                    sizes.add(Integer.parseInt(size));
            }
            int maxLength = 512;
            String ml = e.getAttribute("maxlength");
            if(ml.matches("\\d{1,4}"))
                maxLength = Math.max(0, Math.min(maxLength, Integer.parseInt(ml)));
            int cw = 0, ch = 0;
            if(kind == Kind.CANVAS)
            {
                String[] wh = e.getAttribute(CANVAS_ATTR).split("x");
                cw = Integer.parseInt(wh[0]);
                ch = Integer.parseInt(wh[1]);
            }
            controls.add(new Control(n, kind, e.getAttribute("name"), value, e.hasAttribute("checked"), values, labels, selected, form, maxLength,
                    e.hasAttribute("disabled"), kind == Kind.HIDDEN && e.hasAttribute(LOCATION_ATTR), cw, ch));
        }

        /** 网站的样式表：写给 input 的规则也用到改写出来的按钮上（{@link InputSelectors}） */
        private void adaptSiteStyles(Element root)
        {
            NodeList styles = root.getElementsByTagNameNS("*", "style");
            for(int i = 0; i < styles.getLength(); i++)
            {
                Element st = (Element) styles.item(i);
                String css = st.getTextContent();
                String adapted = InputSelectors.adapt(css);
                if(adapted != null && !adapted.equals(css))
                    st.setTextContent(adapted);
            }
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
            ua.setTextContent(userAgentCss() + sizeCss(sizes));
            head.insertBefore(ua, head.getFirstChild());
            // 页面大小由浏览器定（宽固定、高够长，最后按内容裁）：放在最后，网站改不了
            Element page = dom.createElementNS(XHTML_NS, "style");
            page.setAttribute("type", "text/css");
            page.setTextContent("@page { size: " + viewWidth + "px 30000px; margin: 0; }");
            head.appendChild(page);
        }
    }
}
