package com.mrcrayfish.furniture.client.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网站样式表里写给 {@code <input>} 的选择器，也用到浏览器改写出来的按钮上。
 * <p>
 * 内核按文字排版按钮，所以 {@code <input type="submit">} 等被改写成 {@code <button data-yr-input="…">}（见 {@link PageDocument}）。
 * 网站写的 {@code input.go}、{@code input[type="submit"]}、{@code div.bar input} 在真浏览器里都管得到这些按钮，改写后就管不到了
 * （按钮变成默认的灰色、大小也不对）。这里把每条含 {@code input} 类型选择器的规则复制一份，{@code input} 换成
 * {@code button[data-yr-input]}，只匹配改写出来的按钮，不碰网站自己写的 {@code <button>}。
 * <p>
 * 只认 CSS 2.1 的写法：规则、{@code @media} 里的规则、注释、引号里的字符串；看不懂的原样留着。
 */
final class InputSelectors
{
    /** 改写出来的按钮上的标记（值是原来的 type） */
    static final String ATTR = "data-yr-input";
    private static final String REPLACEMENT = "button[" + ATTR + "]";
    /** 选择器里独立的 {@code input}（前面是开头、空白、组合符或括号，后面是类、ID、属性、伪类、空白、组合符或结尾） */
    private static final Pattern INPUT = Pattern.compile("(^|[\\s>+~(])input(?=$|[.#\\[:\\s>+~)])", Pattern.CASE_INSENSITIVE);

    private InputSelectors() {}

    static String adapt(String css)
    {
        if(css == null || !css.toLowerCase(java.util.Locale.ROOT).contains("input"))
            return css;
        StringBuilder out = new StringBuilder(css.length() + 64);
        rules(css, 0, css.length(), out, 0);
        return out.toString();
    }

    /** 处理 [from, to) 里的规则，写到 out */
    private static void rules(String css, int from, int to, StringBuilder out, int depth)
    {
        int i = from;
        while(i < to)
        {
            int brace = find(css, i, to, '{');
            if(brace < 0)
            {
                out.append(css, i, to);
                return;
            }
            int end = matching(css, brace, to);
            String prelude = css.substring(i, brace);
            String trimmed = stripComments(prelude).trim();
            if(trimmed.startsWith("@"))
            {
                out.append(prelude).append('{');
                if(depth < 4 && trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("@media"))
                    rules(css, brace + 1, Math.max(brace + 1, end), out, depth + 1);
                else
                    out.append(css, brace + 1, Math.max(brace + 1, end));
                if(end < to)
                    out.append('}');
            }
            else
            {
                out.append(selectors(prelude)).append('{').append(css, brace + 1, Math.max(brace + 1, end));
                if(end < to)
                    out.append('}');
            }
            i = end < to ? end + 1 : to;
        }
    }

    /** 选择器列表：含 input 的每个选择器后面补一个换成按钮的 */
    static String selectors(String prelude)
    {
        String clean = stripComments(prelude);
        List<String> parts = split(clean);
        List<String> extra = new ArrayList<>();
        for(String p : parts)
        {
            Matcher m = INPUT.matcher(p);
            if(m.find())
                extra.add(m.replaceAll("$1" + Matcher.quoteReplacement(REPLACEMENT)).trim());
        }
        if(extra.isEmpty())
            return prelude;
        int lead = 0;
        while(lead < clean.length() && Character.isWhitespace(clean.charAt(lead)))
            lead++;
        StringBuilder b = new StringBuilder(clean.substring(0, lead)).append(clean.trim());
        for(String e : extra)
            b.append(", ").append(e);
        return b.append(' ').toString();
    }

    /** 按顶层逗号分开（引号、括号、方括号里的不算） */
    private static List<String> split(String s)
    {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if(quote != 0)
            {
                if(c == '\\')
                    i++;
                else if(c == quote)
                    quote = 0;
            }
            else if(c == '"' || c == '\'')
                quote = c;
            else if(c == '(' || c == '[')
                depth++;
            else if((c == ')' || c == ']') && depth > 0)
                depth--;
            else if(c == ',' && depth == 0)
            {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String stripComments(String s)
    {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ");
    }

    /** 从 i 起找字符 c（跳过注释与引号里的）；没有为 -1 */
    private static int find(String s, int i, int to, char c)
    {
        char quote = 0;
        for(; i < to; i++)
        {
            char ch = s.charAt(i);
            if(quote != 0)
            {
                if(ch == '\\')
                    i++;
                else if(ch == quote)
                    quote = 0;
            }
            else if(ch == '/' && i + 1 < to && s.charAt(i + 1) == '*')
            {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 || e >= to ? to : e + 1;
            }
            else if(ch == '"' || ch == '\'')
                quote = ch;
            else if(ch == c)
                return i;
        }
        return -1;
    }

    /** 与 open 处的 { 配对的 }；没有配对时为 to */
    private static int matching(String s, int open, int to)
    {
        int depth = 0;
        char quote = 0;
        for(int i = open; i < to; i++)
        {
            char ch = s.charAt(i);
            if(quote != 0)
            {
                if(ch == '\\')
                    i++;
                else if(ch == quote)
                    quote = 0;
            }
            else if(ch == '/' && i + 1 < to && s.charAt(i + 1) == '*')
            {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 || e >= to ? to : e + 1;
            }
            else if(ch == '"' || ch == '\'')
                quote = ch;
            else if(ch == '{')
                depth++;
            else if(ch == '}' && --depth == 0)
                return i;
        }
        return to;
    }
}
