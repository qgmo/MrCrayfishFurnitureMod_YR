package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 屏幕缩略图的“投票”（服务端，纯逻辑，时间由调用方给；服主 11-16）。
 * <ul>
 * <li>每个开着界面的人最新的一张算一票（新的换掉旧的）；只算对得上当前页面（上传编号）与滚动位置的，对不上的（过时的）丢掉；
 * 已经关了界面的人的票也丢掉。</li>
 * <li>只有一个人开着界面：照收他的，不校验。</li>
 * <li>多个人开着：每个像素取票数最多的值；一样多时听操作者的（操作者的值在并列的里面时），操作者没投或不在并列里时取最先投的那张的值。
 * 所以两个人时等于听操作者的，三个人起多数才真起作用。</li>
 * <li>多个人开着、只来了一张而且不是操作者的：先等别人的（最多 {@value #HOLD_MS} 毫秒），等不到再照收，免得一个人抢先投的图先挂上去。</li>
 * </ul>
 */
public final class ScreenVote
{
    /** 多人开着时，只有一张非操作者的票，最多等多久 */
    public static final long HOLD_MS = 2000;

    private static final class Ballot
    {
        final int page;
        final int scroll;
        final byte[] pixels;
        final long at;

        Ballot(int page, int scroll, byte[] pixels, long at)
        {
            this.page = page;
            this.scroll = scroll;
            this.pixels = pixels;
            this.at = at;
        }
    }

    /** 按投票先后（同一个人再投时挪到最后） */
    private final Map<UUID, Ballot> ballots = new LinkedHashMap<>();

    /** 投一张（像素是调色板下标，长度由调用方查过） */
    public void submit(UUID player, int page, int scroll, byte[] pixels, long now)
    {
        ballots.remove(player);
        ballots.put(player, new Ballot(page, scroll, pixels.clone(), now));
    }

    public void clear()
    {
        ballots.clear();
    }

    public boolean isEmpty()
    {
        return ballots.isEmpty();
    }

    public int size()
    {
        return ballots.size();
    }

    /**
     * 按当前状态定下一张；还定不了（没有有效的票、或在等别人）返回 null。顺带丢掉过时的、已经不看的人的票。
     *
     * @param viewers  现在开着这台电脑界面的人（含操作者）
     * @param operator 当前操作者（没有为 null）
     */
    @Nullable
    public byte[] decide(int page, int scroll, Collection<UUID> viewers, @Nullable UUID operator, long now)
    {
        List<byte[]> valid = new ArrayList<>();
        int operatorIndex = -1;
        long firstAt = 0;
        UUID first = null;
        Iterator<Map.Entry<UUID, Ballot>> it = ballots.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<UUID, Ballot> e = it.next();
            Ballot b = e.getValue();
            if(b.page != page || b.scroll != scroll || !viewers.contains(e.getKey()))
            {
                it.remove();
                continue;
            }
            if(e.getKey().equals(operator))
                operatorIndex = valid.size();
            if(first == null)
            {
                first = e.getKey();
                firstAt = b.at;
            }
            valid.add(b.pixels);
        }
        if(valid.isEmpty())
            return null;
        if(viewers.size() <= 1 || valid.size() == 1)
        {
            if(viewers.size() > 1 && operatorIndex < 0 && now - firstAt < HOLD_MS)
                return null;
            return valid.get(0).clone();
        }
        return majority(valid, operatorIndex);
    }

    /**
     * 每个像素取票数最多的值；并列时取操作者的值（{@code operatorIndex} 那张，值在并列里时），否则取最先那张（下标小的）里并列的值。
     * 各张长度要一样。
     */
    public static byte[] majority(List<byte[]> uploads, int operatorIndex)
    {
        int n = uploads.get(0).length;
        byte[] out = new byte[n];
        int[] count = new int[256];
        for(int i = 0; i < n; i++)
        {
            int max = 0;
            for(byte[] u : uploads)
            {
                int c = ++count[u[i] & 0xFF];
                if(c > max)
                    max = c;
            }
            int pick = -1;
            if(operatorIndex >= 0 && count[uploads.get(operatorIndex)[i] & 0xFF] == max)
                pick = uploads.get(operatorIndex)[i] & 0xFF;
            for(int k = 0; pick < 0 && k < uploads.size(); k++)
            {
                int v = uploads.get(k)[i] & 0xFF;
                if(count[v] == max)
                    pick = v;
            }
            out[i] = (byte) pick;
            for(byte[] u : uploads)
                count[u[i] & 0xFF] = 0;
        }
        return out;
    }
}
