package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 搜索的状态：失败时之前的结果留着，只认最近一次请求，空页不再往下翻（直接调主线程那一步，不起线程） */
class TvSearchStateTest
{
    private static BiliSearch.Page page(int page, int numPages, String... bvids) throws BiliData.BiliException
    {
        StringBuilder sb = new StringBuilder("{'code':0,'data':{'numPages':" + numPages + ",'result':[");
        for(int i = 0; i < bvids.length; i++)
            sb.append(i > 0 ? "," : "").append("{'bvid':'").append(bvids[i]).append("','title':'t','author':'a','play':1,'pic':''}");
        sb.append("]}}");
        return BiliSearch.parse(new JsonParser().parse(sb.toString().replace('\'', '"')).getAsJsonObject(), BiliSearch.Type.VIDEO, page);
    }

    private static int request(BiliSearch.Type t)
    {
        TvSearchState.Results r = TvSearchState.results(t);
        r.loading = true;
        return ++r.request;
    }

    @Test
    void resultsSurviveErrorsAndStaleRepliesAreIgnored() throws Exception
    {
        BiliSearch.Type t = BiliSearch.Type.VIDEO;
        TvSearchState.Results r = TvSearchState.results(t);
        int a = request(t);
        TvSearchState.finish(t, a, "猫", 1, page(1, 3, "BV1xx411c7mD", "BV1A4411c7mA"), null);
        assertEquals(2, r.items.size());
        assertEquals("猫", r.query);
        assertTrue(r.hasMore());
        assertFalse(r.loading);
        // 新的一次搜索失败：旧结果不动，记下错误
        int b = request(t);
        TvSearchState.finish(t, b, "狗", 1, null, "cfm.tv.search.blocked");
        assertEquals(2, r.items.size());
        assertEquals("猫", r.query);
        assertEquals("cfm.tv.search.blocked", r.error);
        // 晚到的旧请求不算
        int c = request(t);
        TvSearchState.finish(t, b, "狗", 1, page(1, 1, "BV1Nxxr6oES2"), null);
        assertEquals(2, r.items.size());
        assertTrue(r.loading);
        // 第二页接在后面，重复的不要；一条新的都没有时不再往下翻
        TvSearchState.finish(t, c, "猫", 2, page(2, 3, "BV1A4411c7mA", "BV1Nxxr6oES2"), null);
        assertEquals(3, r.items.size());
        assertNull(r.error);
        assertTrue(r.hasMore());
        int d = request(t);
        TvSearchState.finish(t, d, "猫", 3, page(3, 3, "BV1A4411c7mA"), null);
        assertEquals(3, r.items.size());
        assertFalse(r.hasMore());
        // 另一种类型的结果分开放
        assertTrue(TvSearchState.results(BiliSearch.Type.LIVE).items.isEmpty());
    }
}
