package com.mrcrayfish.furniture.computer;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 电脑的会话（服主 11-14）：当前地址与前进后退历史，存在方块实体里（进存档），不存网页内容。服务器重启后有人打开电脑，
 * 浏览器按它重新加载当前地址，前进后退的条目点到时再加载。
 * <p>
 * 操作者每次换页面时把历史报给服务端（客户端说的，服务端逐条检查）：最多 {@value #MAX_ENTRIES} 条，每条最多 {@value UrlInput#MAX_LENGTH} 个字符，
 * 合起来最多 {@value #MAX_TOTAL_CHARS} 个字符（超了从最旧的后退条目开始丢）；只收规范化过的 http / https 网址和 {@value UrlInput#HOME}。
 * 纯逻辑，不碰世界。
 */
public final class BrowserHistory
{
    public static final int MAX_ENTRIES = 32;
    public static final int MAX_TOTAL_CHARS = 16 * 1024;
    /** 编码后（报给服务端、发给操作者）最多这么多字节 */
    public static final int MAX_ENCODED = 4 + 2 + MAX_ENTRIES * 2 + MAX_TOTAL_CHARS * 3;
    public static final BrowserHistory EMPTY = new BrowserHistory(Collections.<String>emptyList(), -1);

    public final List<String> urls;
    /** 当前那条；没有为 -1 */
    public final int index;

    private BrowserHistory(List<String> urls, int index)
    {
        this.urls = Collections.unmodifiableList(urls);
        this.index = index;
    }

    public boolean isEmpty()
    {
        return urls.isEmpty();
    }

    @Nullable
    public String current()
    {
        return index >= 0 && index < urls.size() ? urls.get(index) : null;
    }

    /** 一条网址能不能存：规范化过的 http / https，或者主页 */
    public static boolean isStorable(@Nullable String url)
    {
        if(url == null || url.isEmpty() || url.length() > UrlInput.MAX_LENGTH)
            return false;
        if(url.equals(UrlInput.HOME))
            return true;
        UrlInput.Result r = UrlInput.normalize(url);
        return r.isOk() && url.equals(r.url);
    }

    /**
     * 浏览器这边：从历史里挑能存的（错误页之类的不合格条目跳过），{@code index} 跟着挪；太多、太长时从最旧的开始丢（当前那条留着）。
     */
    public static BrowserHistory of(List<String> all, int index)
    {
        List<String> urls = new ArrayList<>();
        int at = -1;
        for(int i = 0; i < all.size(); i++)
        {
            if(!isStorable(all.get(i)))
                continue;
            if(i <= index)
                at = urls.size();
            urls.add(all.get(i));
        }
        if(urls.isEmpty())
            return EMPTY;
        if(at < 0)
            at = 0;
        // 先丢最旧的，再丢最远的前进条目
        while(urls.size() > MAX_ENTRIES || total(urls) > MAX_TOTAL_CHARS)
        {
            if(at > 0)
            {
                urls.remove(0);
                at--;
            }
            else if(urls.size() > 1)
            {
                urls.remove(urls.size() - 1);
            }
            else
            {
                break;
            }
        }
        return new BrowserHistory(urls, at);
    }

    /**
     * 服务端：检查客户端报来的；每条都要合格、条数与长度不超限，否则返回 null（拒收）。
     */
    @Nullable
    public static BrowserHistory checked(List<String> urls, int index)
    {
        if(urls.isEmpty())
            return index == -1 ? EMPTY : null;
        if(urls.size() > MAX_ENTRIES || index < 0 || index >= urls.size() || total(urls) > MAX_TOTAL_CHARS)
            return null;
        for(String u : urls)
        {
            if(!isStorable(u))
                return null;
        }
        return new BrowserHistory(new ArrayList<>(urls), index);
    }

    private static int total(List<String> urls)
    {
        int n = 0;
        for(String u : urls)
            n += u.length();
        return n;
    }

    // ---- 编码（网络包） ----

    public byte[] encode()
    {
        try
        {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(index);
            out.writeShort(urls.size());
            for(String u : urls)
            {
                byte[] b = u.getBytes(StandardCharsets.UTF_8);
                out.writeShort(b.length);
                out.write(b);
            }
            return raw.toByteArray();
        }
        catch(IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** 解码并检查；不合格返回 null */
    @Nullable
    public static BrowserHistory decode(byte[] data)
    {
        if(data.length > MAX_ENCODED)
            return null;
        try
        {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int index = in.readInt();
            int n = in.readUnsignedShort();
            if(n > MAX_ENTRIES)
                return null;
            List<String> urls = new ArrayList<>();
            for(int i = 0; i < n; i++)
            {
                int len = in.readUnsignedShort();
                if(len > UrlInput.MAX_LENGTH * 3)
                    return null;
                byte[] b = new byte[len];
                in.readFully(b);
                urls.add(new String(b, StandardCharsets.UTF_8));
            }
            if(in.available() > 0)
                return null;
            return checked(urls, index);
        }
        catch(IOException e)
        {
            return null;
        }
    }

    // ---- 存档 ----

    public void writeToNBT(NBTTagCompound tag)
    {
        NBTTagList list = new NBTTagList();
        for(String u : urls)
            list.appendTag(new NBTTagString(u));
        tag.setTag("History", list);
        tag.setInteger("HistoryIndex", index);
    }

    /** 读存档；不合格（被改过的存档）时当作没有 */
    public static BrowserHistory readFromNBT(NBTTagCompound tag)
    {
        if(!tag.hasKey("History", 9))
            return EMPTY;
        NBTTagList list = tag.getTagList("History", 8);
        List<String> urls = new ArrayList<>();
        for(int i = 0; i < list.tagCount() && i <= MAX_ENTRIES; i++)
            urls.add(list.getStringTagAt(i));
        BrowserHistory h = checked(urls, tag.getInteger("HistoryIndex"));
        return h == null ? EMPTY : h;
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof BrowserHistory && ((BrowserHistory) o).index == index && ((BrowserHistory) o).urls.equals(urls);
    }

    @Override
    public int hashCode()
    {
        return urls.hashCode() * 31 + index;
    }
}
