package com.mrcrayfish.furniture.web.minebay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ShopParamsTest
{
    @Test
    void pageNumbers()
    {
        assertEquals(1, ShopParams.page(null));
        assertEquals(1, ShopParams.page(""));
        assertEquals(3, ShopParams.page("3"));
        assertEquals(ShopParams.MAX_PAGE, ShopParams.page(Integer.toString(ShopParams.MAX_PAGE)));
        for(String bad : new String[]{"0", "-1", "1000", "+1", " 1", "1e3", "abc", "99999999999", "１"})
            assertEquals(-1, ShopParams.page(bad), bad);
    }

    @Test
    void quantities()
    {
        assertEquals(1, ShopParams.quantity(null, 64));
        assertEquals(1, ShopParams.quantity("  ", 64));
        assertEquals(12, ShopParams.quantity(" 12 ", 64));
        assertEquals(64, ShopParams.quantity("64", 64));
        assertEquals(-1, ShopParams.quantity("65", 64));
        assertEquals(-1, ShopParams.quantity("2", 1));
        for(String bad : new String[]{"0", "-3", "1.5", "x", "0x10", "2147483648", "9999999999"})
            assertEquals(-1, ShopParams.quantity(bad, 64), bad);
    }

    @Test
    void productIds()
    {
        assertTrue(ShopParams.isProductId("config:0a1b2c3d4e5f"));
        assertTrue(ShopParams.isProductId("config:0a1b2c3d4e5f-2"));
        assertTrue(ShopParams.isProductId("youranscp:floppy/port_scan"));
        for(String bad : new String[]{null, "", "nocolon", "A:b", "a:", ":b", "a:b c", "a:<b>", "a:b\n", "a:b?", "a:" + String.join("", Collections.nCopies(65, "x"))})
            assertFalse(ShopParams.isProductId(bad), String.valueOf(bad));
    }

    @Test
    void queries()
    {
        assertEquals("", ShopParams.query(null));
        assertEquals("附魔 书", ShopParams.query("  附魔 \t 书 "));
        assertEquals(String.join("", Collections.nCopies(ShopParams.MAX_QUERY, "字")), ShopParams.query(String.join("", Collections.nCopies(ShopParams.MAX_QUERY, "字"))));
        assertNull(ShopParams.query(String.join("", Collections.nCopies(ShopParams.MAX_QUERY + 1, "字"))));
        assertEquals(ShopParams.MAX_QUERY, ShopParams.query(String.join("", Collections.nCopies(ShopParams.MAX_QUERY, "😀"))).codePointCount(0, ShopParams.MAX_QUERY * 2), "按字数算，不按 char");
        for(String bad : new String[]{"a\u0000b", "a\u0007", "￾", "￿", "\uD800", "a\uDC00b", "\u0085", "�"})
            assertNull(ShopParams.query(bad), bad);
    }

    @Test
    void sorts()
    {
        assertEquals(ShopParams.Sort.DEFAULT, ShopParams.Sort.parse(null));
        assertEquals(ShopParams.Sort.DEFAULT, ShopParams.Sort.parse(""));
        assertEquals(ShopParams.Sort.PRICE_DESC, ShopParams.Sort.parse("price_desc"));
        assertNull(ShopParams.Sort.parse("PRICE"));
        assertNull(ShopParams.Sort.parse("price;drop"));
    }

    static Listing l(String id, int seq, String cat, String sortName, String currency, long price)
    {
        return new Listing(id, seq, cat, "minecraft:stone", 1, null, sortName, (sortName + " " + id).toLowerCase(), currency, price, 64, new ArrayList<>());
    }

    @Test
    void selectFiltersAndSorts()
    {
        List<Listing> all = new ArrayList<>();
        all.add(l("config:a", 0, Categories.BLOCKS, "陶瓦 Terracotta", "minecraft:emerald", 1));
        all.add(l("config:b", 1, Categories.MISC, "鞍 Saddle", "minecraft:emerald", 4));
        all.add(l("config:c", 2, Categories.TOOLS, "钻石马铠 Diamond Horse Armor", "minecraft:diamond", 8));
        all.add(l("config:d", 3, Categories.MAGIC, "附魔书 Enchanted Book", "minecraft:emerald", 8));
        assertEquals(ids("config:a", "config:b", "config:c", "config:d"), ids(ShopParams.select(all, null, "", ShopParams.Sort.DEFAULT)));
        assertEquals(ids("config:b"), ids(ShopParams.select(all, null, "SADDLE", ShopParams.Sort.DEFAULT)), "不分大小写");
        assertEquals(ids("config:c"), ids(ShopParams.select(all, null, "马铠 diamond", ShopParams.Sort.DEFAULT)), "每个词都要有");
        assertEquals(ids(), ids(ShopParams.select(all, null, "马铠 saddle", ShopParams.Sort.DEFAULT)));
        assertEquals(ids("config:d"), ids(ShopParams.select(all, Categories.MAGIC, "", ShopParams.Sort.DEFAULT)));
        assertEquals(ids("config:a", "config:b", "config:c", "config:d"), ids(ShopParams.select(all, null, "", ShopParams.Sort.PRICE_ASC)));
        // 同价时按货币（diamond 在 emerald 前）
        assertEquals(ids("config:c", "config:d", "config:b", "config:a"), ids(ShopParams.select(all, null, "", ShopParams.Sort.PRICE_DESC)));
        // 按拼音：鞍(an) 附(fu) 陶(tao) 钻(zuan)
        assertEquals(ids("config:b", "config:d", "config:a", "config:c"), ids(ShopParams.select(all, null, "", ShopParams.Sort.NAME)));
    }

    static List<String> ids(String... ids)
    {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, ids);
        return out;
    }

    /** 服务端翻译给的中文名（原版 zh_cn.lang 里的）按拼音排序，中文名、英文名、物品 ID 都能搜到 */
    @Test
    void chineseNamesSortInChineseOrderAndAreSearchable()
    {
        String[][] items = {{"diamond", "钻石"}, {"saddle", "鞍"}, {"book", "书"}, {"terracotta", "陶瓦"}};
        List<Listing> all = new ArrayList<>();
        int seq = 0;
        for(String[] it : items)
        {
            String zh = it[1];
            all.add(new Listing("config:" + it[0], seq++, Categories.MISC, "minecraft:" + it[0], 1, null, zh, (zh + " " + it[0] + " minecraft:" + it[0]).toLowerCase(),
                    "minecraft:emerald", 1, 64, new ArrayList<>()));
        }
        List<String> byName = ids(ShopParams.select(all, null, "", ShopParams.Sort.NAME));
        assertEquals(Arrays.asList("config:saddle", "config:book", "config:terracotta", "config:diamond"), byName, "按拼音：鞍 书 陶 钻");
        assertEquals(1, ShopParams.select(all, null, "钻石", ShopParams.Sort.DEFAULT).size());
        assertEquals(1, ShopParams.select(all, null, "diamond", ShopParams.Sort.DEFAULT).size());
        assertEquals(1, ShopParams.select(all, null, "minecraft:diamond", ShopParams.Sort.DEFAULT).size());
    }

    static List<String> ids(List<Listing> list)
    {
        return list.stream().map(x -> x.id).collect(Collectors.toList());
    }
}
