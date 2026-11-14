package com.mrcrayfish.furniture.web.minebay;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * MineBay 的商品分类：内置六个（配置里的商品按物品自动归类），别的模组可以加自己的（{@link MineBayApi#registerCategory}）。
 * 分类编号出现在网址里（{@code ?c=编号}），只认登记过的。
 */
public final class Categories
{
    public static final String BLOCKS = "blocks";
    public static final String TOOLS = "tools";
    public static final String FOOD = "food";
    public static final String REDSTONE = "redstone";
    public static final String MAGIC = "magic";
    public static final String MISC = "misc";

    /** 模组登记的分类编号：模组 ID + 冒号 + 名字 */
    static final Pattern MOD_ID = Pattern.compile("[a-z0-9_]{1,32}:[a-z0-9_]{1,32}");
    static final int MAX_NAME = 8;
    static final int MAX_REGISTERED = 16;

    public static final class Category
    {
        public final String id;
        public final String name;

        Category(String id, String name)
        {
            this.id = id;
            this.name = name;
        }
    }

    private static final List<Category> BUILTIN;
    private static final List<Category> REGISTERED = new CopyOnWriteArrayList<>();

    static
    {
        List<Category> b = new ArrayList<>();
        b.add(new Category(BLOCKS, "方块"));
        b.add(new Category(TOOLS, "工具与装备"));
        b.add(new Category(FOOD, "食物"));
        b.add(new Category(REDSTONE, "红石"));
        b.add(new Category(MAGIC, "药水与附魔"));
        b.add(new Category(MISC, "杂项"));
        BUILTIN = Collections.unmodifiableList(b);
    }

    private Categories() {}

    /** 内置的在前（杂项放最后），模组加的按登记顺序插在杂项前面 */
    public static List<Category> all()
    {
        List<Category> out = new ArrayList<>(BUILTIN.subList(0, BUILTIN.size() - 1));
        out.addAll(REGISTERED);
        out.add(BUILTIN.get(BUILTIN.size() - 1));
        return out;
    }

    @Nullable
    public static Category get(@Nullable String id)
    {
        if(id == null)
            return null;
        for(Category c : BUILTIN)
        {
            if(c.id.equals(id))
                return c;
        }
        for(Category c : REGISTERED)
        {
            if(c.id.equals(id))
                return c;
        }
        return null;
    }

    static synchronized void register(String id, String name)
    {
        if(id == null || !MOD_ID.matcher(id).matches())
            throw new IllegalArgumentException("MineBay 分类编号要写成“模组ID:名字”（小写字母、数字、下划线）：" + id);
        if(name == null || name.trim().isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME)
            throw new IllegalArgumentException("MineBay 分类名要有 1–" + MAX_NAME + " 个字：" + name);
        if(get(id) != null)
            throw new IllegalArgumentException("MineBay 分类已经登记过：" + id);
        if(REGISTERED.size() >= MAX_REGISTERED)
            throw new IllegalStateException("MineBay 分类太多了（最多 " + MAX_REGISTERED + " 个）");
        REGISTERED.add(new Category(id, name.trim()));
    }
}
