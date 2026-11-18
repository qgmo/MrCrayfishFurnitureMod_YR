package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 频道设置界面搜索的状态（悠然服务器魔改，服主 11-18），只在内存里、只在客户端主线程读写：
 * 搜索框里输的字、视频与直播间各自搜出来的结果、滚到哪里，在网络出错、接口拒绝、关掉搜索界面再打开（同一次游戏内）时都留着。
 * 搜索在后台线程做，结果回到主线程再换上；新搜索失败时旧结果不动，只显示错误。
 */
public final class TvSearchState
{
    public static final int MAX_QUERY = 100;
    /** 一种类型最多留多少条（翻到 15 页左右） */
    public static final int MAX_ITEMS = 300;
    private static final AtomicInteger THREADS = new AtomicInteger();

    /** 一种类型的结果 */
    public static final class Results
    {
        public String query = "";
        public final List<BiliSearch.Item> items = new ArrayList<>();
        public int loadedPage;
        public int numPages;
        public boolean loading;
        /** 最近一次出错的语言键（成功后清掉）；结果留着 */
        @Nullable
        public String error;
        public int scroll;
        int request;

        public boolean hasMore()
        {
            return !query.isEmpty() && loadedPage < numPages && items.size() < MAX_ITEMS;
        }
    }

    private static BiliSearch.Type type = BiliSearch.Type.VIDEO;
    private static String input = "";
    private static final Map<BiliSearch.Type, Results> RESULTS = new EnumMap<>(BiliSearch.Type.class);

    static
    {
        for(BiliSearch.Type t : BiliSearch.Type.values())
            RESULTS.put(t, new Results());
    }

    private TvSearchState() {}

    public static BiliSearch.Type type()
    {
        return type;
    }

    public static void setType(BiliSearch.Type t)
    {
        type = t;
    }

    public static String input()
    {
        return input;
    }

    public static void setInput(String text)
    {
        input = text == null ? "" : text;
    }

    public static Results results(BiliSearch.Type t)
    {
        return RESULTS.get(t);
    }

    /** 搜输入框里的字（当前类型，第 1 页）；空的不搜 */
    public static void search()
    {
        String q = input.trim();
        if(q.isEmpty())
            return;
        start(type, q, 1);
    }

    /** 当前类型加载下一页（滚到底时） */
    public static void loadMore()
    {
        Results r = RESULTS.get(type);
        if(r.loading || !r.hasMore())
            return;
        start(type, r.query, r.loadedPage + 1);
    }

    private static void start(BiliSearch.Type t, String query, int page)
    {
        Results r = RESULTS.get(t);
        int request = ++r.request;
        r.loading = true;
        Thread th = new Thread(() ->
        {
            BiliSearch.Page result = null;
            String error = null;
            try
            {
                result = BiliApi.search(t, query, page);
            }
            catch(BiliData.BiliException e)
            {
                error = e.getKey();
                MrCrayfishFurnitureMod.logger().info("TV search failed: {} ({})", e.getKey(), e.getMessage());
            }
            catch(RuntimeException e)
            {
                error = "cfm.tv.bili.error";
                MrCrayfishFurnitureMod.logger().info("TV search failed: {}", e.toString());
            }
            BiliSearch.Page done = result;
            String err = error;
            Minecraft.getMinecraft().addScheduledTask(() -> finish(t, request, query, page, done, err));
        }, "cfm-tv-search-" + THREADS.incrementAndGet());
        th.setDaemon(true);
        th.start();
    }

    /** 主线程：换上结果（只认最近一次请求） */
    static void finish(BiliSearch.Type t, int request, String query, int page, @Nullable BiliSearch.Page result, @Nullable String error)
    {
        Results r = RESULTS.get(t);
        if(request != r.request)
            return;
        r.loading = false;
        if(result == null)
        {
            r.error = error == null ? "cfm.tv.bili.error" : error;
            return;
        }
        r.error = null;
        if(page == 1)
        {
            r.items.clear();
            r.scroll = 0;
            r.query = query;
        }
        int added = 0;
        for(BiliSearch.Item item : result.items)
        {
            if(r.items.size() >= MAX_ITEMS)
                break;
            boolean dup = false;
            for(BiliSearch.Item old : r.items)
                dup |= old.channel.equals(item.channel);
            if(!dup)
            {
                r.items.add(item);
                added++;
            }
        }
        r.loadedPage = page;
        // 这一页什么新的都没有：不再往下翻（免得滚到底时一页页空翻）
        r.numPages = added == 0 ? page : result.numPages;
    }
}
