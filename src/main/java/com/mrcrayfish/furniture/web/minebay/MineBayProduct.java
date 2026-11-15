package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 别的模组在 MineBay 上架的一件商品（用 {@link #builder} 写，再交给 {@link MineBayApi#registerProduct}）。
 * <pre>{@code
 * MineBayApi.registerCategory("youranscp:software", "软件");
 * MineBayApi.registerProduct(MineBayProduct.builder("youranscp:floppy_portscan")
 *         .category("youranscp:software")
 *         .name("工具软盘：端口扫描器")
 *         .icon(new ItemStack(ScpItems.FLOPPY))
 *         .price(new ItemStack(Items.DIAMOND), 3)
 *         .description("软盘里装着端口扫描器的镜像。")
 *         .delivery((buyer, n) -> makeFloppies(buyer, n))
 *         .maxPerOrder(4)
 *         .build());
 * }</pre>
 */
public final class MineBayProduct
{
    /** 名字、说明的长度上限 */
    public static final int MAX_NAME = 48;
    public static final int MAX_DESCRIPTION_LINES = 8;
    public static final int MAX_DESCRIPTION_LINE = 120;
    public static final int MAX_KEYWORDS = 16;
    /** 单价上限 */
    public static final int MAX_PRICE = 1_000_000;

    public final String id;
    public final String category;
    @Nullable
    public final String name;
    /** 图标（也是没写名字时显示的物品名） */
    public final ItemStack icon;
    public final ItemStack currency;
    public final int price;
    /** 固定物品：每份发这一叠；用回调时为空 */
    public final ItemStack item;
    @Nullable
    public final MineBayDelivery delivery;
    public final int maxPerOrder;
    public final List<String> description;
    public final List<String> keywords;
    /** 库存（全服统一）；不限量为 null */
    @Nullable
    public final Stock.Spec stock;

    private MineBayProduct(Builder b)
    {
        this.id = b.id;
        this.category = b.category;
        this.name = b.name;
        this.icon = b.icon.copy();
        this.currency = b.currency.copy();
        this.price = b.price;
        this.item = b.item.copy();
        this.delivery = b.delivery;
        this.maxPerOrder = b.maxPerOrder;
        this.description = Collections.unmodifiableList(new ArrayList<>(b.description));
        this.keywords = Collections.unmodifiableList(new ArrayList<>(b.keywords));
        this.stock = b.stock;
    }

    /** @param id 商品编号，写成“模组ID:名字”（小写字母、数字、{@code _ . - /}），以后不要改：玩家购物车里记的是它 */
    public static Builder builder(String id)
    {
        return new Builder(id);
    }

    public static final class Builder
    {
        private final String id;
        private String category = Categories.MISC;
        @Nullable
        private String name;
        private ItemStack icon = ItemStack.EMPTY;
        private ItemStack currency = ItemStack.EMPTY;
        private int price;
        private ItemStack item = ItemStack.EMPTY;
        @Nullable
        private MineBayDelivery delivery;
        private int maxPerOrder = Cart.MAX_QUANTITY;
        private final List<String> description = new ArrayList<>();
        private final List<String> keywords = new ArrayList<>();
        @Nullable
        private Stock.Spec stock;

        private Builder(String id)
        {
            this.id = id;
        }

        /** 分类：内置的（{@link Categories#BLOCKS} 等）或用 {@link MineBayApi#registerCategory} 登记过的；默认杂项 */
        public Builder category(String category)
        {
            this.category = category;
            return this;
        }

        /** 网页上显示的名字（中文）；不写时显示图标物品在玩家语言里的名字 */
        public Builder name(String name)
        {
            this.name = name;
            return this;
        }

        public Builder icon(ItemStack icon)
        {
            this.icon = icon;
            return this;
        }

        /** 价格：每份 amount 个 currency（只看物品与数据值，不看 NBT） */
        public Builder price(ItemStack currency, int amount)
        {
            this.currency = currency;
            this.price = amount;
            return this;
        }

        /** 固定物品：每份发这一叠（数量就是每份几个）。没设图标时它也是图标 */
        public Builder item(ItemStack item)
        {
            this.item = item;
            return this;
        }

        /** 发货回调：每次发货现做物品 */
        public Builder delivery(MineBayDelivery delivery)
        {
            this.delivery = delivery;
            return this;
        }

        /** 一单最多买几份（1–64，默认 64） */
        public Builder maxPerOrder(int n)
        {
            this.maxPerOrder = n;
            return this;
        }

        /** 商品页“商品说明”里的一行 */
        public Builder description(String line)
        {
            this.description.add(line);
            return this;
        }

        /**
         * 限量（全服统一，按现实时间）：每月最多卖 {@code perMonth} 份（1–100000）。{@link Stock.Rule#DAILY} 每天补上限的 1/30（连续补，
         * 卖完要等补回来）；{@link Stock.Rule#MONTHLY} 每个现实月 1 日补满、平时不补。不调用就不限量。
         */
        public Builder stock(int perMonth, Stock.Rule rule)
        {
            this.stock = new Stock.Spec(perMonth, rule);
            return this;
        }

        /** 搜索用的词（名字、物品 ID 已经能搜到，这里写别名） */
        public Builder keywords(String... words)
        {
            Collections.addAll(this.keywords, words);
            return this;
        }

        public MineBayProduct build()
        {
            if(!ShopParams.isProductId(id))
                throw new IllegalArgumentException("MineBay 商品编号要写成“模组ID:名字”（小写字母、数字、_ . - /）：" + id);
            if(id.startsWith(Catalog.CONFIG_PREFIX))
                throw new IllegalArgumentException("MineBay 商品编号不能用 " + Catalog.CONFIG_PREFIX + " 开头（留给配置里的商品）：" + id);
            if(Categories.get(category) == null)
                throw new IllegalArgumentException("MineBay 没有这个分类（先 registerCategory）：" + category);
            if(name != null && (name.trim().isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME))
                throw new IllegalArgumentException("MineBay 商品名要有 1–" + MAX_NAME + " 个字：" + name);
            if(currency.isEmpty() || price < 1 || price > MAX_PRICE)
                throw new IllegalArgumentException("MineBay 商品要有货币，单价 1–" + MAX_PRICE + "：" + id);
            if(item.isEmpty() == (delivery == null))
                throw new IllegalArgumentException("MineBay 商品要么给固定物品（item），要么给发货回调（delivery），只能二选一：" + id);
            if(icon.isEmpty())
                icon = item;
            if(icon.isEmpty())
                throw new IllegalArgumentException("MineBay 用发货回调的商品要设图标（icon）：" + id);
            if(maxPerOrder < 1 || maxPerOrder > Cart.MAX_QUANTITY)
                throw new IllegalArgumentException("MineBay 一单上限要在 1–" + Cart.MAX_QUANTITY + "：" + id);
            if(description.size() > MAX_DESCRIPTION_LINES)
                throw new IllegalArgumentException("MineBay 商品说明最多 " + MAX_DESCRIPTION_LINES + " 行：" + id);
            for(String d : description)
            {
                if(d == null || d.codePointCount(0, d.length()) > MAX_DESCRIPTION_LINE)
                    throw new IllegalArgumentException("MineBay 商品说明每行最多 " + MAX_DESCRIPTION_LINE + " 个字：" + id);
            }
            if(keywords.size() > MAX_KEYWORDS)
                throw new IllegalArgumentException("MineBay 搜索词最多 " + MAX_KEYWORDS + " 个：" + id);
            for(String k : keywords)
                Objects.requireNonNull(k, "keyword");
            return new MineBayProduct(this);
        }
    }
}
