package com.mrcrayfish.furniture.web;

import com.mrcrayfish.furniture.client.browser.PageDocument;
import com.mrcrayfish.furniture.computer.PageLimits;
import com.mrcrayfish.furniture.web.minebay.MineBayPages;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MineBayPagesTest
{
    @Test
    void shopPageIsValidAndWithinLimits() throws Exception
    {
        List<MineBayPages.Entry> list = new ArrayList<>();
        for(int i = 0; i < 300; i++)
            list.add(new MineBayPages.Entry(i, "minecraft:dye@" + (i % 16), 1 + i % 3, "minecraft:emerald", 1 + i % 10));
        for(int page = 0; page < 3; page++)
        {
            byte[] html = MineBayPages.shop(list, page, "abc", page == 1 ? "买好了 <ok>" : null, true).getBytes(StandardCharsets.UTF_8);
            assertNull(PageLimits.check(html));
            PageDocument d = PageDocument.parse(html, "https://minebay.yr/", id -> null, 600);
            assertEquals(MineBayPages.PER_PAGE, d.forms.size());
            assertEquals(MineBayPages.PER_PAGE, d.controls.stream().filter(c -> c.kind == PageDocument.Kind.TEXT && c.name.equals("n")).count(), "每件商品一个数量框");
            assertEquals("https://minebay.yr/buy", d.forms.get(0).action);
        }
        // 页码超出范围时停在最后一页
        assertTrue(MineBayPages.shop(list, 999, "t", null, false).contains("第 38 / 38 页"));
    }
}
