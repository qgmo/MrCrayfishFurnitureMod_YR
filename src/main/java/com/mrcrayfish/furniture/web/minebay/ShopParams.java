package com.mrcrayfish.furniture.web.minebay;

import javax.annotation.Nullable;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MineBay 网址与表单参数的校验（纯逻辑）。不合格的参数不猜、不截：页面回 400 并说明哪里不对。
 */
public final class ShopParams
{
    /** 搜索词最多几个字 */
    public static final int MAX_QUERY = 32;
    /** 列表每页几件 */
    public static final int PER_PAGE = 12;
    /** 页码上限（再大没有意义，防止乱填） */
    public static final int MAX_PAGE = 999;
    static final Pattern PRODUCT_ID = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}");
    static final Pattern NUMBER = Pattern.compile("[0-9]{1,9}");

    private ShopParams() {}

    public enum Sort
    {
        DEFAULT("", "默认"),
        PRICE_ASC("price", "价格从低到高"),
        PRICE_DESC("price_desc", "价格从高到低"),
        NAME("name", "名称");

        public final String code;
        public final String label;

        Sort(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Nullable
        public static Sort parse(@Nullable String s)
        {
            if(s == null || s.isEmpty())
                return DEFAULT;
            for(Sort x : values())
            {
                if(x.code.equals(s))
                    return x;
            }
            return null;
        }
    }

    public static boolean isProductId(@Nullable String s)
    {
        return s != null && PRODUCT_ID.matcher(s).matches();
    }

    /** 页码（网址里从 1 起）；没给为 1，不合格为 -1 */
    public static int page(@Nullable String s)
    {
        if(s == null || s.isEmpty())
            return 1;
        if(!NUMBER.matcher(s).matches())
            return -1;
        int p = Integer.parseInt(s);
        return p < 1 || p > MAX_PAGE ? -1 : p;
    }

    /** 数量：1 到 max 的整数（前后空格可以有）；不合格为 -1；没填为 1 */
    public static int quantity(@Nullable String s, int max)
    {
        if(s == null)
            return 1;
        String t = s.trim();
        if(t.isEmpty())
            return 1;
        if(!NUMBER.matcher(t).matches())
            return -1;
        int n = Integer.parseInt(t);
        return n < 1 || n > max ? -1 : n;
    }

    /** 订单号；不合格为 -1 */
    public static long orderNumber(@Nullable String s)
    {
        if(s == null || !NUMBER.matcher(s).matches())
            return -1;
        return Long.parseLong(s);
    }

    /**
     * 搜索词：去掉首尾空白、把连续空白合成一个；太长、带控制字符或 XML 里不能用的字符时返回 null。没填为空串。
     */
    @Nullable
    public static String query(@Nullable String s)
    {
        if(s == null)
            return "";
        for(int i = 0; i < s.length(); )
        {
            int cp = s.codePointAt(i);
            boolean space = cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r';
            if((cp < 0x20 && !space) || (cp >= 0x7F && cp < 0xA0) || cp == 0xFFFE || cp == 0xFFFF || (cp >= 0xD800 && cp <= 0xDFFF) || cp == 0xFFFD)
                return null;
            i += Character.charCount(cp);
        }
        String t = s.trim().replaceAll("\\s+", " ");
        if(t.codePointCount(0, t.length()) > MAX_QUERY)
            return null;
        return t;
    }

    /** 搜索：每个词都要出现在商品的搜索文字里（不分大小写） */
    public static boolean matches(Listing l, String query)
    {
        if(query.isEmpty())
            return true;
        String text = l.searchText;
        for(String w : query.toLowerCase(Locale.ROOT).split(" "))
        {
            if(!w.isEmpty() && !text.contains(w))
                return false;
        }
        return true;
    }

    /** 按分类、搜索词筛，再排序 */
    public static List<Listing> select(List<Listing> all, @Nullable String category, String query, Sort sort)
    {
        List<Listing> out = new ArrayList<>();
        for(Listing l : all)
        {
            if((category == null || l.category.equals(category)) && matches(l, query))
                out.add(l);
        }
        out.sort(comparator(sort));
        return out;
    }

    static Comparator<Listing> comparator(Sort sort)
    {
        Comparator<Listing> bySeq = Comparator.comparingInt(l -> l.seq);
        switch(sort)
        {
            case PRICE_ASC:
                return Comparator.<Listing>comparingLong(l -> l.price).thenComparing(l -> l.currency).thenComparing(bySeq);
            case PRICE_DESC:
                return Comparator.<Listing>comparingLong(l -> -l.price).thenComparing(l -> l.currency).thenComparing(bySeq);
            case NAME:
            {
                Collator c = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
                return Comparator.<Listing, String>comparing(l -> l.sortName, c).thenComparing(bySeq);
            }
            default:
                return bySeq;
        }
    }
}
