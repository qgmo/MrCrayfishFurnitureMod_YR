package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.api.Recipes;
import com.mrcrayfish.furniture.computer.ItemIcons;
import com.mrcrayfish.furniture.util.ServerTranslations;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockRailDetector;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.BlockRedstoneLight;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEnchantedBook;
import net.minecraft.item.ItemExpBottle;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemFlintAndSteel;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemSkull;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.PotionEffect;
import net.minecraft.potion.PotionType;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MineBay 在卖的商品（服务端主线程）：配置文件 / CraftTweaker 里的商品（{@link Recipes#getMineBayItems()}）加上模组登记的（{@link MineBayApi}）。
 * <ul>
 * <li>配置里的商品编号按物品（注册名、数据值、数量、NBT）算出来，写成 {@code config:十六进制}，与价格无关：改价后购物车里的还对得上。</li>
 * <li>分类：配置里的商品按物品自动归类（方块、工具与装备、食物、红石、药水与附魔、杂项）。</li>
 * <li>名字：服务端拿不到玩家的语言，网页上的物品名由浏览器在玩家本地翻译；服务端这边的名字（搜索、按名字排序、订单记录用）
 * 依次取：改过的名字、服务端翻译表里的中文名（语言目录、模组 jar 的 zh_cn.lang，见 {@link ServerLang}）、内置的中文名、英文名。</li>
 * </ul>
 * 商品清单变了（配置重载、模组上下架）才重建，否则用缓存。
 */
final class Catalog
{
    static final String CONFIG_PREFIX = "config:";

    /** 一件商品：网页数据 + 发货要用的东西 */
    static final class Entry
    {
        final Listing listing;
        final ItemStack currency;
        /** 固定物品（每份）；用回调时为空 */
        final ItemStack item;
        @Nullable
        final MineBayDelivery delivery;

        Entry(Listing listing, ItemStack currency, ItemStack item, @Nullable MineBayDelivery delivery)
        {
            this.listing = listing;
            this.currency = currency;
            this.item = item;
            this.delivery = delivery;
        }
    }

    final List<Listing> listings;
    final Map<String, Entry> byId;

    private Catalog(List<Listing> listings, Map<String, Entry> byId)
    {
        this.listings = Collections.unmodifiableList(listings);
        this.byId = byId;
    }

    @Nullable
    Listing listing(String id)
    {
        Entry e = byId.get(id);
        return e == null ? null : e.listing;
    }

    private static Catalog cached;
    private static Object[] cachedKey;

    /** 主线程 */
    static Catalog get()
    {
        RecipeData[] data = Recipes.getMineBayItems();
        Object[] key = new Object[data.length + 1];
        System.arraycopy(data, 0, key, 0, data.length);
        key[data.length] = MineBayApi.version() + "/" + ServerLang.version();
        if(cached == null || !sameKey(key, cachedKey))
        {
            cached = build(data);
            cachedKey = key;
        }
        return cached;
    }

    private static boolean sameKey(Object[] a, Object[] b)
    {
        if(b == null || a.length != b.length)
            return false;
        for(int i = 0; i < a.length - 1; i++)
        {
            if(a[i] != b[i])
                return false;
        }
        return a[a.length - 1].equals(b[b.length - 1]);
    }

    private static Catalog build(RecipeData[] data)
    {
        List<Listing> out = new ArrayList<>();
        Map<String, Entry> byId = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for(RecipeData d : data)
        {
            ItemStack in = d.getInput();
            ItemStack cur = d.getCurrency();
            if(in == null || cur == null || in.isEmpty() || cur.isEmpty() || d.getPrice() <= 0 || d.getPrice() > MineBayProduct.MAX_PRICE)
                continue;
            String base = CONFIG_PREFIX + hash(in);
            int n = seen.merge(base, 1, Integer::sum);
            String id = n == 1 ? base : base + "-" + n;
            String custom = in.hasDisplayName() ? strip(in.getDisplayName()) : null;
            Listing l = listing(id, out.size(), classify(in), in, in.getCount(), custom, cur, d.getPrice(), Cart.MAX_QUANTITY, Collections.<String>emptyList(), Collections.<String>emptyList());
            out.add(l);
            byId.put(id, new Entry(l, cur.copy(), in.copy(), null));
        }
        for(MineBayProduct p : MineBayApi.products())
        {
            ItemStack shown = p.item.isEmpty() ? p.icon : p.item;
            int count = p.item.isEmpty() ? 1 : p.item.getCount();
            Listing l = listing(p.id, out.size(), p.category, p.icon, count, p.name, p.currency, p.price, p.maxPerOrder, p.description, p.keywords);
            if(shown != p.icon && p.name == null && shown.hasDisplayName())
                l = listing(p.id, out.size(), p.category, p.icon, count, strip(shown.getDisplayName()), p.currency, p.price, p.maxPerOrder, p.description, p.keywords);
            out.add(l);
            byId.put(p.id, new Entry(l, p.currency.copy(), p.item.copy(), p.delivery));
        }
        return new Catalog(out, byId);
    }

    private static Listing listing(String id, int seq, String category, ItemStack stack, int count, @Nullable String name, ItemStack currency, int price, int max,
                                   List<String> extraDescription, List<String> keywords)
    {
        String english = english(stack);
        String chinese = chinese(stack);
        String sortName = name != null ? name : chinese != null ? chinese : english;
        List<List<Listing.Part>> desc = description(stack);
        for(String line : extraDescription)
            desc.add(Collections.singletonList(Listing.Part.text(line)));
        StringBuilder search = new StringBuilder();
        search.append(sortName).append(' ').append(english).append(' ');
        if(chinese != null)
            search.append(chinese).append(' ');
        ResourceLocation reg = stack.getItem().getRegistryName();
        if(reg != null)
            search.append(reg).append(' ').append(reg.getPath()).append(' ');
        Categories.Category c = Categories.get(category);
        if(c != null)
            search.append(c.name).append(' ');
        for(List<Listing.Part> line : desc)
        {
            for(Listing.Part part : line)
                search.append(part.text);
            search.append(' ');
        }
        for(String k : keywords)
            search.append(k).append(' ');
        return new Listing(id, seq, category, iconId(stack), count, name, sortName, search.toString().toLowerCase(Locale.ROOT), itemId(currency), price, max, desc);
    }

    /** 货币的写法：注册名，有子类型或数据值不为 0 时加 {@code @数据值}（货币只看物品与数据值） */
    static String itemId(ItemStack stack)
    {
        String id = String.valueOf(stack.getItem().getRegistryName());
        return stack.getHasSubtypes() || stack.getMetadata() != 0 ? id + "@" + stack.getMetadata() : id;
    }

    /** 图标的写法：再带上画图标要用的 NBT（药水、附魔、烟花、刷怪蛋，见 {@link ItemIcons}） */
    static String iconId(ItemStack stack)
    {
        return itemId(stack) + ItemIcons.nbtSuffix(stack.getTagCompound());
    }

    static String hash(ItemStack s)
    {
        String key = s.getItem().getRegistryName() + "|" + s.getMetadata() + "|" + s.getCount() + "|" + (s.getTagCompound() == null ? "" : s.getTagCompound().toString());
        try
        {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for(int i = 0; i < 6; i++)
                sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        }
        catch(Exception e)
        {
            return Integer.toHexString(key.hashCode());
        }
    }

    static String strip(String s)
    {
        String t = TextFormatting.getTextWithoutFormattingCodes(s);
        t = t == null ? "" : t.trim();
        return t.length() > MineBayProduct.MAX_NAME ? t.substring(0, MineBayProduct.MAX_NAME) : t;
    }

    // ---------------------------------------------------------------- 分类

    static String classify(ItemStack s)
    {
        Item i = s.getItem();
        if(i instanceof ItemFood || i == Items.CAKE || i == Items.MILK_BUCKET)
            return Categories.FOOD;
        if(i instanceof ItemPotion || i instanceof ItemEnchantedBook || i instanceof ItemExpBottle || i == Items.BLAZE_POWDER || i == Items.NETHER_WART
                || i == Items.GLASS_BOTTLE || i == Items.BREWING_STAND || i == Items.FERMENTED_SPIDER_EYE || i == Items.SPECKLED_MELON)
            return Categories.MAGIC;
        if(i == Items.REDSTONE || i == Items.REPEATER || i == Items.COMPARATOR || (i instanceof ItemBlock && isRedstone(((ItemBlock) i).getBlock())))
            return Categories.REDSTONE;
        if(i instanceof ItemTool || i instanceof ItemSword || i instanceof ItemHoe || i instanceof ItemBow || i instanceof ItemShears || i instanceof ItemFishingRod
                || i instanceof ItemFlintAndSteel || i instanceof ItemArmor || i instanceof ItemShield || i == Items.SADDLE || i == Items.ELYTRA
                || i == Items.IRON_HORSE_ARMOR || i == Items.GOLDEN_HORSE_ARMOR || i == Items.DIAMOND_HORSE_ARMOR || i == Items.LEAD || i == Items.COMPASS || i == Items.CLOCK)
            return Categories.TOOLS;
        if(i instanceof ItemBlock || i instanceof ItemSkull)
            return Categories.BLOCKS;
        return Categories.MISC;
    }

    @SuppressWarnings("deprecation")
    private static boolean isRedstone(Block b)
    {
        try
        {
            return b.getDefaultState().canProvidePower() || b instanceof BlockPistonBase || b instanceof BlockDispenser || b instanceof BlockRedstoneLight
                    || b instanceof BlockRailPowered || b instanceof BlockRailDetector;
        }
        catch(RuntimeException e)
        {
            return false;
        }
    }

    // ---------------------------------------------------------------- 名字

    @SuppressWarnings("deprecation")
    static String english(ItemStack s)
    {
        try
        {
            return strip(s.getItem().getItemStackDisplayName(s));
        }
        catch(RuntimeException e)
        {
            return String.valueOf(s.getItem().getRegistryName());
        }
    }

    /**
     * 服务端能拿到的中文名：先查语言目录与模组 jar 里的 zh_cn.lang（{@link ServerLang}），查不到再用内置的几个原版物品、
     * 药水、附魔书的中文名（没有放原版语言文件时就靠它们）
     */
    @Nullable
    static String chinese(ItemStack s)
    {
        String fromLang = fromLang(ServerLang.get(), s);
        if(fromLang != null)
            return fromLang;
        Item i = s.getItem();
        if(i instanceof ItemPotion)
        {
            String effect = POTIONS.get(String.valueOf(PotionType.REGISTRY.getNameForObject(PotionUtils.getPotionFromItem(s))).replace("minecraft:", "")
                    .replace("long_", "").replace("strong_", ""));
            String kind = i == Items.SPLASH_POTION ? "喷溅型" : i == Items.LINGERING_POTION ? "滞留型" : "";
            if(effect != null)
                return kind + effect + "药水";
        }
        if(i instanceof ItemEnchantedBook)
        {
            Map<Enchantment, Integer> e = EnchantmentHelper.getEnchantments(s);
            if(e.size() == 1)
            {
                Map.Entry<Enchantment, Integer> only = e.entrySet().iterator().next();
                String n = ENCHANTMENTS.get(String.valueOf(Enchantment.REGISTRY.getNameForObject(only.getKey())).replace("minecraft:", ""));
                if(n != null)
                    return "附魔书（" + n + " " + roman(only.getValue()) + "）";
            }
            return "附魔书";
        }
        ResourceLocation reg = i.getRegistryName();
        if(reg == null)
            return null;
        String builtin = VANILLA.get(reg.getPath() + "@" + s.getMetadata());
        if(builtin == null)
            builtin = VANILLA.get(reg.getPath());
        if(builtin != null && reg.getNamespace().equals("minecraft"))
            return builtin;
        return null;
    }

    /** 按游戏里 {@code getItemStackDisplayName} 的规则，用服务端翻译表算出中文名；表里没有就返回 null */
    @Nullable
    @SuppressWarnings("deprecation")
    static String fromLang(ServerTranslations t, ItemStack s)
    {
        if(t.isEmpty())
            return null;
        try
        {
            Item i = s.getItem();
            if(i instanceof ItemPotion || i == Items.TIPPED_ARROW)
            {
                String prefix = i == Items.SPLASH_POTION ? "splash_potion.effect." : i == Items.LINGERING_POTION ? "lingering_potion.effect."
                        : i == Items.TIPPED_ARROW ? "tipped_arrow.effect." : "potion.effect.";
                return t.local(PotionUtils.getPotionFromItem(s).getNamePrefixed(prefix));
            }
            if(i instanceof ItemEnchantedBook)
            {
                String book = t.local("item.enchantedBook.name");
                Map<Enchantment, Integer> e = EnchantmentHelper.getEnchantments(s);
                if(book != null && e.size() == 1)
                {
                    Map.Entry<Enchantment, Integer> only = e.entrySet().iterator().next();
                    String n = t.local(only.getKey().getName());
                    if(n != null)
                        return book + "（" + n + " " + roman(only.getValue()) + "）";
                }
                return book;
            }
            if(i instanceof ItemMonsterPlacer)
            {
                String base = t.local("item.monsterPlacer.name");
                ResourceLocation id = ItemMonsterPlacer.getNamedIdFrom(s);
                if(base == null || id == null)
                    return base;
                String tn = EntityList.getTranslationName(id);
                String entity = tn == null ? null : t.local("entity." + tn + ".name");
                return entity == null ? base : base + " " + entity;
            }
            return t.local(s.getTranslationKey() + ".name");
        }
        catch(RuntimeException e)
        {
            return null;
        }
    }

    // ---------------------------------------------------------------- 说明

    /** 服务端能拿到的物品说明：附魔、药水效果、自定义说明（Lore）、耐久、饱食度、烟花飞行时间 */
    @SuppressWarnings("deprecation")
    static List<List<Listing.Part>> description(ItemStack s)
    {
        List<List<Listing.Part>> out = new ArrayList<>();
        try
        {
            for(Map.Entry<Enchantment, Integer> e : EnchantmentHelper.getEnchantments(s).entrySet())
            {
                List<Listing.Part> line = new ArrayList<>();
                line.add(Listing.Part.lang(e.getKey().getName(), displayName(e.getKey().getName())));
                if(e.getKey().getMaxLevel() > 1 || e.getValue() > 1)
                    line.add(Listing.Part.text(" " + roman(e.getValue())));
                out.add(line);
                if(out.size() >= 8)
                    break;
            }
            if(s.getItem() instanceof ItemPotion || s.getItem() == Items.TIPPED_ARROW)
            {
                for(PotionEffect p : PotionUtils.getEffectsFromStack(s))
                {
                    List<Listing.Part> line = new ArrayList<>();
                    line.add(Listing.Part.lang(p.getEffectName(), displayName(p.getEffectName())));
                    if(p.getAmplifier() > 0)
                        line.add(Listing.Part.text(" " + roman(p.getAmplifier() + 1)));
                    if(p.getDuration() > 20)
                    {
                        int sec = p.getDuration() / 20;
                        line.add(Listing.Part.text(String.format(" (%d:%02d)", sec / 60, sec % 60)));
                    }
                    out.add(line);
                }
            }
            NBTTagCompound display = s.getSubCompound("display");
            if(display != null && display.hasKey("Lore", Constants.NBT.TAG_LIST))
            {
                NBTTagList lore = display.getTagList("Lore", Constants.NBT.TAG_STRING);
                for(int k = 0; k < lore.tagCount() && k < 8; k++)
                    out.add(Collections.singletonList(Listing.Part.text(strip(lore.getStringTagAt(k)))));
            }
            if(s.getItem() == Items.FIREWORKS)
            {
                NBTTagCompound fw = s.getSubCompound("Fireworks");
                if(fw != null)
                    out.add(Collections.singletonList(Listing.Part.text("飞行时间：" + fw.getByte("Flight"))));
            }
            if(s.getItem() instanceof ItemFood)
                out.add(Collections.singletonList(Listing.Part.text("恢复饥饿值：" + ((ItemFood) s.getItem()).getHealAmount(s))));
            if(s.isItemStackDamageable())
                out.add(Collections.singletonList(Listing.Part.text("耐久：" + (s.getMaxDamage() + 1))));
        }
        catch(RuntimeException e)
        {
            MrCrayfishFurnitureMod.logger().debug("MineBay: description of {} failed: {}", s, e.toString());
        }
        return out;
    }

    /** 没有翻译键可用时显示的名字：服务端翻译表里的中文，没有就是服务端自己的英文 */
    @SuppressWarnings("deprecation")
    private static String displayName(String key)
    {
        String zh = ServerLang.get().local(key);
        return zh != null ? zh : net.minecraft.util.text.translation.I18n.translateToLocal(key);
    }

    static String roman(int n)
    {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 1 && n <= 10 ? r[n - 1] : Integer.toString(n);
    }

    private static final Map<String, String> VANILLA = new HashMap<>();
    private static final Map<String, String> POTIONS = new HashMap<>();
    private static final Map<String, String> ENCHANTMENTS = new HashMap<>();

    static
    {
        // 原版 MineBay 默认商品与常见货币的中文名（服务端没有原版的中文语言文件）
        String[] v = {"hardened_clay", "陶瓦", "skull@0", "骷髅头颅", "skull@1", "凋灵骷髅头颅", "skull@2", "僵尸的头", "skull@3", "头", "skull@4", "苦力怕的头", "skull@5", "龙首",
                "saddle", "鞍", "spawn_egg", "刷怪蛋", "diamond_horse_armor", "钻石马铠", "golden_horse_armor", "金马铠", "iron_horse_armor", "铁马铠",
                "experience_bottle", "附魔之瓶", "fireworks", "烟花火箭", "emerald", "绿宝石", "diamond", "钻石", "iron_ingot", "铁锭", "gold_ingot", "金锭",
                "gold_nugget", "金粒", "name_tag", "命名牌", "lead", "拴绳", "elytra", "鞘翅", "totem_of_undying", "不死图腾", "nether_star", "下界之星"};
        for(int i = 0; i < v.length; i += 2)
            VANILLA.put(v[i], v[i + 1]);
        String[] p = {"night_vision", "夜视", "invisibility", "隐身", "leaping", "跳跃", "fire_resistance", "抗火", "swiftness", "迅捷", "slowness", "缓慢",
                "water_breathing", "水肺", "healing", "治疗", "harming", "伤害", "poison", "剧毒", "regeneration", "再生", "strength", "力量", "weakness", "虚弱", "luck", "幸运"};
        for(int i = 0; i < p.length; i += 2)
            POTIONS.put(p[i], p[i + 1]);
        String[] e = {"protection", "保护", "fire_protection", "火焰保护", "feather_falling", "摔落保护", "blast_protection", "爆炸保护", "projectile_protection", "弹射物保护",
                "respiration", "水下呼吸", "aqua_affinity", "水下速掘", "thorns", "荆棘", "depth_strider", "深海探索者", "frost_walker", "冰霜行者", "binding_curse", "绑定诅咒",
                "sharpness", "锋利", "smite", "亡灵杀手", "bane_of_arthropods", "节肢杀手", "knockback", "击退", "fire_aspect", "火焰附加", "looting", "抢夺", "sweeping", "横扫之刃",
                "efficiency", "效率", "silk_touch", "精准采集", "unbreaking", "耐久", "fortune", "时运", "power", "力量", "punch", "冲击", "flame", "火矢", "infinity", "无限",
                "luck_of_the_sea", "海之眷顾", "lure", "饵钓", "mending", "经验修补", "vanishing_curse", "消失诅咒"};
        for(int i = 0; i < e.length; i += 2)
            ENCHANTMENTS.put(e[i], e[i + 1]);
    }
}
