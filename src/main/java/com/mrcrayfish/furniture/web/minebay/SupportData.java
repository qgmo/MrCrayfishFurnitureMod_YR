package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MineBay 在线客服的存档（主世界的 {@code data/cfm_minebay_support.dat}，服务端主线程）：每个帐号（玩家 UUID）的对话记录，
 * 与转给管理员的留言（{@link Ticket}）。和 MineBay 的购物车、订单分开存（不和钱的账放在一起）。
 */
public class SupportData extends WorldSavedData
{
    public static final String NAME = "cfm_minebay_support";
    /** 最多给这么多个帐号存对话 */
    public static final int MAX_PLAYERS = 10000;
    /** 最多存多少条留言（满了先丢最早的已处理的；都没处理时不收新的） */
    public static final int MAX_TICKETS = 500;
    /** 每个帐号最多有几条没处理的留言 */
    public static final int MAX_OPEN_TICKETS_PER_PLAYER = 3;

    /** 一条转给管理员的留言 */
    public static final class Ticket
    {
        public final int id;
        public final long time;
        public final UUID player;
        public final String playerName;
        public final String text;
        boolean closed;
        @Nullable
        String closedBy;

        Ticket(int id, long time, UUID player, String playerName, String text, boolean closed, @Nullable String closedBy)
        {
            this.id = id;
            this.time = time;
            this.player = player;
            this.playerName = playerName;
            this.text = text;
            this.closed = closed;
            this.closedBy = closedBy;
        }

        public boolean closed()
        {
            return closed;
        }

        @Nullable
        public String closedBy()
        {
            return closedBy;
        }
    }

    private final Map<UUID, SupportChat> chats = new HashMap<>();
    private final List<Ticket> tickets = new ArrayList<>();
    private int nextTicket = 1;

    public SupportData(String name)
    {
        super(name);
    }

    @Nullable
    public static SupportData get(@Nullable MinecraftServer server)
    {
        World overworld = server == null ? null : server.getWorld(0);
        MapStorage storage = overworld == null ? null : overworld.getMapStorage();
        if(storage == null)
            return null;
        SupportData d = (SupportData) storage.getOrLoadData(SupportData.class, NAME);
        if(d == null)
        {
            d = new SupportData(NAME);
            storage.setData(NAME, d);
        }
        return d;
    }

    /** 这个帐号的对话（没有时给一个空的，不存进去） */
    public SupportChat chat(UUID player)
    {
        SupportChat c = chats.get(player);
        return c == null ? new SupportChat() : c;
    }

    /** 要改的对话（没有时建；人数到上限时为 null） */
    @Nullable
    public SupportChat chatForChange(UUID player)
    {
        SupportChat c = chats.get(player);
        if(c == null && chats.size() < MAX_PLAYERS)
        {
            c = new SupportChat();
            chats.put(player, c);
        }
        markDirty();
        return c;
    }

    public List<Ticket> tickets()
    {
        return Collections.unmodifiableList(tickets);
    }

    @Nullable
    public Ticket ticket(int id)
    {
        for(Ticket t : tickets)
        {
            if(t.id == id)
                return t;
        }
        return null;
    }

    public int openTickets(UUID player)
    {
        int n = 0;
        for(Ticket t : tickets)
        {
            if(!t.closed && t.player.equals(player))
                n++;
        }
        return n;
    }

    public int openTickets()
    {
        int n = 0;
        for(Ticket t : tickets)
        {
            if(!t.closed)
                n++;
        }
        return n;
    }

    /** 收一条留言；这个帐号没处理的太多、或存满了时不收，返回 null */
    @Nullable
    public Ticket addTicket(UUID player, String name, String text, long now)
    {
        if(openTickets(player) >= MAX_OPEN_TICKETS_PER_PLAYER)
            return null;
        if(tickets.size() >= MAX_TICKETS)
        {
            Ticket oldestClosed = null;
            for(Ticket t : tickets)
            {
                if(t.closed)
                {
                    oldestClosed = t;
                    break;
                }
            }
            if(oldestClosed == null)
                return null;
            tickets.remove(oldestClosed);
        }
        Ticket t = new Ticket(nextTicket++, now, player, name, text, false, null);
        tickets.add(t);
        markDirty();
        return t;
    }

    /** 标成处理过；没有这条或已经处理过返回 false */
    public boolean closeTicket(int id, String by)
    {
        Ticket t = ticket(id);
        if(t == null || t.closed)
            return false;
        t.closed = true;
        t.closedBy = by;
        markDirty();
        return true;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        chats.clear();
        tickets.clear();
        nextTicket = Math.max(1, nbt.getInteger("next"));
        NBTTagList list = nbt.getTagList("chats", Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < list.tagCount() && chats.size() < MAX_PLAYERS; i++)
        {
            NBTTagCompound t = list.getCompoundTagAt(i);
            if(t.hasUniqueId("id"))
            {
                SupportChat c = SupportChat.read(t);
                if(!c.isEmpty())
                    chats.put(t.getUniqueId("id"), c);
            }
        }
        NBTTagList ts = nbt.getTagList("tickets", Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < ts.tagCount() && tickets.size() < MAX_TICKETS; i++)
        {
            NBTTagCompound t = ts.getCompoundTagAt(i);
            if(!t.hasUniqueId("player"))
                continue;
            int id = t.getInteger("id");
            tickets.add(new Ticket(id, t.getLong("time"), t.getUniqueId("player"), t.getString("name"),
                    SupportText.clip(t.getString("text"), SupportText.MAX_STORED), t.getBoolean("closed"), t.hasKey("by") ? t.getString("by") : null));
            nextTicket = Math.max(nextTicket, id + 1);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt)
    {
        nbt.setInteger("next", nextTicket);
        NBTTagList list = new NBTTagList();
        for(Map.Entry<UUID, SupportChat> e : chats.entrySet())
        {
            if(e.getValue().isEmpty())
                continue;
            NBTTagCompound t = e.getValue().write();
            t.setUniqueId("id", e.getKey());
            list.appendTag(t);
        }
        nbt.setTag("chats", list);
        NBTTagList ts = new NBTTagList();
        for(Ticket k : tickets)
        {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("id", k.id);
            t.setLong("time", k.time);
            t.setUniqueId("player", k.player);
            t.setString("name", k.playerName);
            t.setString("text", k.text);
            t.setBoolean("closed", k.closed);
            if(k.closedBy != null)
                t.setString("by", k.closedBy);
            ts.appendTag(t);
        }
        nbt.setTag("tickets", ts);
        return nbt;
    }
}
