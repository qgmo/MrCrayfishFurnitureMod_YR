package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 模组商品的翻译键名字与说明：写法、不能和 name 同时用、说明总行数 */
class MineBayProductTest
{
    @BeforeAll
    static void boot()
    {
        Bootstrap.register();
    }

    static MineBayProduct.Builder base(String id)
    {
        return MineBayProduct.builder(id).item(new ItemStack(Items.PAPER)).price(new ItemStack(Items.EMERALD), 2);
    }

    @Test
    void nameAndDescriptionKeys()
    {
        MineBayProduct p = base("test:disk").nameKey("test.minebay.disk.name").description("一行字").descriptionKey("test.minebay.disk.desc").build();
        assertEquals("test.minebay.disk.name", p.nameKey);
        assertNull(p.name);
        assertEquals(Collections.singletonList("一行字"), p.description);
        assertEquals(Collections.singletonList("test.minebay.disk.desc"), p.descriptionKeys);
        MineBayProduct plain = base("test:plain").name("纸").build();
        assertNull(plain.nameKey);
        assertTrue(plain.descriptionKeys.isEmpty());
    }

    @Test
    void rejectsBadKeys()
    {
        assertThrows(IllegalArgumentException.class, () -> base("test:a").name("纸").nameKey("test.name").build(), "name 与 nameKey 只能有一个");
        assertThrows(IllegalArgumentException.class, () -> base("test:b").nameKey("带 空格").build());
        assertThrows(IllegalArgumentException.class, () -> base("test:c").nameKey("").build());
        assertThrows(IllegalArgumentException.class, () -> base("test:d").descriptionKey("a<b").build());
        MineBayProduct.Builder b = base("test:e");
        for(int i = 0; i < 5; i++)
            b.description("行 " + i);
        for(int i = 0; i < 4; i++)
            b.descriptionKey("test.desc." + i);
        assertThrows(IllegalArgumentException.class, b::build, "文字行加翻译键行最多 8 行");
    }
}
