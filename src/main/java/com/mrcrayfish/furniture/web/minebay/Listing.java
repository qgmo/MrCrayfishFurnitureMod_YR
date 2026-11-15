package com.mrcrayfish.furniture.web.minebay;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * 网页上的一件商品（纯数据，主线程上从配置与模组登记的商品生成，交给页面与结算用）。
 * 物品写成“注册名[@数据值]”；没有 {@link #name} 时由浏览器按物品在玩家本地显示名字（服务端不知道玩家的语言）。
 */
public final class Listing implements Checkout.Offer
{
    /** 说明里的一段：文字，或者让浏览器按玩家语言翻译的键（翻不了时显示 {@link #text}） */
    public static final class Part
    {
        public final String text;
        @Nullable
        public final String langKey;

        public Part(String text, @Nullable String langKey)
        {
            this.text = text;
            this.langKey = langKey;
        }

        public static Part text(String s)
        {
            return new Part(s, null);
        }

        public static Part lang(String key, String fallback)
        {
            return new Part(fallback, key);
        }
    }

    public final String id;
    /** 上架顺序（默认排序） */
    public final int seq;
    public final String category;
    /** 图标的物品 */
    public final String icon;
    /** 每份几个 */
    public final int count;
    /** 网站给的名字（模组登记的商品、改过名的物品）；null 时用浏览器本地化的物品名 */
    @Nullable
    public final String name;
    /** 服务端能拿到的名字（搜索、按名字排序、订单记录用）：网站给的名字、中文名、英文名 */
    public final String sortName;
    /** 搜索用的文字（小写） */
    public final String searchText;
    public final String currency;
    public final long price;
    public final int maxPerOrder;
    /** 商品说明，每行若干段 */
    public final List<List<Part>> description;
    /** 库存规则；不限量为 null */
    @Nullable
    public final Stock.Spec stock;

    public Listing(String id, int seq, String category, String icon, int count, @Nullable String name, String sortName, String searchText,
                   String currency, long price, int maxPerOrder, List<List<Part>> description)
    {
        this(id, seq, category, icon, count, name, sortName, searchText, currency, price, maxPerOrder, description, null);
    }

    public Listing(String id, int seq, String category, String icon, int count, @Nullable String name, String sortName, String searchText,
                   String currency, long price, int maxPerOrder, List<List<Part>> description, @Nullable Stock.Spec stock)
    {
        this.stock = stock;
        this.id = id;
        this.seq = seq;
        this.category = category;
        this.icon = icon;
        this.count = count;
        this.name = name;
        this.sortName = sortName;
        this.searchText = searchText;
        this.currency = currency;
        this.price = price;
        this.maxPerOrder = maxPerOrder;
        this.description = Collections.unmodifiableList(description);
    }

    @Override
    public String id()
    {
        return id;
    }

    @Override
    public String currency()
    {
        return currency;
    }

    @Override
    public long unitPrice()
    {
        return price;
    }

    @Override
    public int maxPerOrder()
    {
        return maxPerOrder;
    }
}
