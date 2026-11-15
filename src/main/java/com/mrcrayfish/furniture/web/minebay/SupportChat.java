package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个 MineBay 帐号（玩家 UUID）和客服的对话记录（纯逻辑，主线程上用；随存档，换电脑登录还在）。最多留 {@value #MAX_MESSAGES} 条，
 * 多了最早的不留。另记最近一小时里问过的时间（限额用，{@link #sentWithin}）。
 */
public final class SupportChat
{
    public static final int MAX_MESSAGES = 40;
    /** 限额最多看这么多条（配置的每小时条数上限也不会超过它） */
    static final int MAX_SENT = 1000;

    public enum Role
    {
        /** 玩家说的 */
        PLAYER,
        /** 客服（小 Bay）说的 */
        AGENT,
        /** 网站自己写的说明（例如“客服暂时没能回复”） */
        NOTE
    }

    public static final class Message
    {
        public final Role role;
        public final String text;
        public final long time;

        public Message(Role role, String text, long time)
        {
            this.role = role;
            this.text = text;
            this.time = time;
        }
    }

    private final List<Message> messages = new ArrayList<>();
    private final List<Long> sent = new ArrayList<>();

    public List<Message> messages()
    {
        return Collections.unmodifiableList(messages);
    }

    public void add(Message m)
    {
        messages.add(m);
        while(messages.size() > MAX_MESSAGES)
            messages.remove(0);
    }

    /** 记一次提问（限额） */
    public void countSent(long now)
    {
        sent.add(now);
        while(sent.size() > MAX_SENT)
            sent.remove(0);
    }

    /** 收回最近一次提问的限额（客服没能回复时） */
    public void uncountLast()
    {
        if(!sent.isEmpty())
            sent.remove(sent.size() - 1);
    }

    /** 最近 {@code windowMs} 里问过几次 */
    public int sentWithin(long now, long windowMs)
    {
        int n = 0;
        for(long t : sent)
        {
            if(t > now - windowMs && t <= now)
                n++;
        }
        return n;
    }

    /** 到了上限时，还要等多久（毫秒）才能再问；没到上限为 0 */
    public long waitFor(long now, long windowMs, int limit)
    {
        List<Long> recent = new ArrayList<>();
        for(long t : sent)
        {
            if(t > now - windowMs && t <= now)
                recent.add(t);
        }
        if(recent.size() < limit)
            return 0;
        Collections.sort(recent);
        return recent.get(recent.size() - limit) + windowMs - now;
    }

    public boolean isEmpty()
    {
        return messages.isEmpty() && sent.isEmpty();
    }

    public NBTTagCompound write()
    {
        NBTTagCompound t = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for(Message m : messages)
        {
            NBTTagCompound x = new NBTTagCompound();
            x.setByte("r", (byte) m.role.ordinal());
            x.setString("s", m.text);
            x.setLong("t", m.time);
            list.appendTag(x);
        }
        t.setTag("m", list);
        NBTTagList s = new NBTTagList();
        for(long v : sent)
            s.appendTag(new NBTTagLong(v));
        t.setTag("sent", s);
        return t;
    }

    public static SupportChat read(NBTTagCompound t)
    {
        SupportChat c = new SupportChat();
        NBTTagList list = t.getTagList("m", Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < list.tagCount(); i++)
        {
            NBTTagCompound x = list.getCompoundTagAt(i);
            int r = x.getByte("r");
            if(r < 0 || r >= Role.values().length)
                continue;
            c.add(new Message(Role.values()[r], SupportText.clip(x.getString("s"), SupportText.MAX_STORED), x.getLong("t")));
        }
        NBTTagList s = t.getTagList("sent", Constants.NBT.TAG_LONG);
        for(int i = 0; i < s.tagCount(); i++)
            c.countSent(((NBTTagLong) s.get(i)).getLong());
        return c;
    }
}
