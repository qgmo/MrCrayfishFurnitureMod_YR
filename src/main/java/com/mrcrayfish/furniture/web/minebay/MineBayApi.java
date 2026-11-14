package com.mrcrayfish.furniture.web.minebay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;

/**
 * MineBay 上架接口（悠然服务器魔改）：别的模组在 MineBay 网站上卖东西。
 * <ul>
 * <li>{@link #registerCategory}：加一个分类（显示在导航栏里，内置分类之后、“杂项”之前）。</li>
 * <li>{@link #registerProduct}：上架一件商品（{@link MineBayProduct}），固定物品或者发货回调（{@link MineBayDelivery}）。</li>
 * </ul>
 * 一般在模组初始化阶段（init / postInit）调用；之后也可以在服务端主线程上调用（例如开服后按配置上架）。
 * 配置文件与 CraftTweaker 里的 MineBay 商品照旧在卖，排在模组商品前面。
 */
public final class MineBayApi
{
    private static final Map<String, MineBayProduct> PRODUCTS = new LinkedHashMap<>();
    private static final AtomicInteger VERSION = new AtomicInteger();
    /** 最多上架几件模组商品 */
    public static final int MAX_PRODUCTS = 512;

    private MineBayApi() {}

    /**
     * @param id   分类编号，写成“模组ID:名字”（小写字母、数字、下划线），出现在网址里
     * @param name 显示的名字（1–8 个字）
     * @throws IllegalArgumentException 编号、名字不合格或已经登记过
     */
    public static void registerCategory(String id, String name)
    {
        Categories.register(id, name);
        VERSION.incrementAndGet();
    }

    /** @throws IllegalArgumentException 编号已经用过 */
    public static synchronized void registerProduct(MineBayProduct product)
    {
        if(PRODUCTS.containsKey(product.id))
            throw new IllegalArgumentException("MineBay 商品编号已经用过：" + product.id);
        if(PRODUCTS.size() >= MAX_PRODUCTS)
            throw new IllegalStateException("MineBay 模组商品太多了（最多 " + MAX_PRODUCTS + " 件）");
        PRODUCTS.put(product.id, product);
        VERSION.incrementAndGet();
    }

    /** 下架（购物车里有它的，会显示“已下架”，由玩家自己删） */
    public static synchronized boolean unregisterProduct(String id)
    {
        boolean removed = PRODUCTS.remove(id) != null;
        if(removed)
            VERSION.incrementAndGet();
        return removed;
    }

    static synchronized List<MineBayProduct> products()
    {
        return new ArrayList<>(PRODUCTS.values());
    }

    static int version()
    {
        return VERSION.get();
    }
}
