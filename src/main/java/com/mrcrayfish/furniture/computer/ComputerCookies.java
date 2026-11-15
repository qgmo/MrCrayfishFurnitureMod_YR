package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 电脑的 Cookie 存在服务端（悠然服务器魔改，服主 11-15）：每个维度一份存档数据（{@code data/cfm_computer_cookies.dat}），
 * 按电脑的位置存，跟着存档走（关服再开还在）。不放进方块实体的 NBT：那样会随方块更新、被搬运类模组装进物品时发到客户端。
 * <p>
 * 每台电脑另有一个随机的编号（存在方块实体里）：取的时候位置和编号都要对上。电脑被拆（{@code breakBlock}）时删掉；
 * 换了位置（搬走再放下）的电脑在新位置上没有数据，在旧位置上的会被拆方块时删掉，就算没删掉，新放在那里的电脑编号也对不上。
 * 只在服务端主线程上用。
 */
public class ComputerCookies extends WorldSavedData
{
    public static final String NAME = "cfm_computer_cookies";
    /** 每个维度最多给这么多台电脑存 Cookie（防止存档无限长大） */
    public static final int MAX_COMPUTERS = 4096;

    private static final class Entry
    {
        final UUID id;
        final CookieJar jar;

        Entry(UUID id, CookieJar jar)
        {
            this.id = id;
            this.jar = jar;
        }
    }

    private final Map<Long, Entry> entries = new HashMap<>();
    private boolean warnedFull;

    public ComputerCookies(String name)
    {
        super(name);
    }

    @Nullable
    static ComputerCookies of(@Nullable World world)
    {
        if(world == null || world.isRemote)
            return null;
        MapStorage storage = world.getPerWorldStorage();
        if(storage == null)
            return null;
        ComputerCookies d = (ComputerCookies) storage.getOrLoadData(ComputerCookies.class, NAME);
        if(d == null)
        {
            d = new ComputerCookies(NAME);
            storage.setData(NAME, d);
        }
        return d;
    }

    /** 这台电脑的 Cookie（没有时给一个空的，不存进去；改了以后调 {@link #changed}） */
    public static CookieJar get(TileEntityComputer computer)
    {
        ComputerCookies d = of(computer.getWorld());
        return d == null ? new CookieJar() : d.jar(computer.getPos().toLong(), computer.getCookieJarId());
    }

    /** 改过以后存回去（空的就删掉） */
    public static void changed(TileEntityComputer computer, CookieJar jar)
    {
        ComputerCookies d = of(computer.getWorld());
        if(d == null)
            return;
        if(!d.put(computer.getPos().toLong(), jar.isEmpty() ? computer.getCookieJarId() : computer.getOrCreateCookieJarId(), jar) && !d.warnedFull)
        {
            d.warnedFull = true;
            MrCrayfishFurnitureMod.logger().warn("Computer cookies: this dimension already stores cookies for {} computers, new ones are not saved", MAX_COMPUTERS);
        }
    }

    /** 电脑被拆了：删掉这个位置上的 Cookie */
    public static void remove(World world, BlockPos pos)
    {
        ComputerCookies d = of(world);
        if(d != null)
            d.remove(pos.toLong());
    }

    /** 这个位置、这个编号的 Cookie；位置上没有、编号对不上（换过电脑）时给一个空的（不存进去） */
    CookieJar jar(long pos, @Nullable UUID id)
    {
        Entry e = entries.get(pos);
        return e != null && id != null && e.id.equals(id) ? e.jar : new CookieJar();
    }

    /** 存（空的就删掉）；台数到上限、这个位置上又没有时存不了，返回 false */
    boolean put(long pos, @Nullable UUID id, CookieJar jar)
    {
        if(jar.isEmpty() || id == null)
        {
            remove(pos);
            return true;
        }
        Entry e = entries.get(pos);
        if(e == null && entries.size() >= MAX_COMPUTERS)
            return false;
        if(e == null || !e.id.equals(id) || e.jar != jar)
            entries.put(pos, new Entry(id, jar));
        markDirty();
        return true;
    }

    void remove(long pos)
    {
        if(entries.remove(pos) != null)
            markDirty();
    }

    int computers()
    {
        return entries.size();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        entries.clear();
        long now = System.currentTimeMillis();
        NBTTagList list = nbt.getTagList("Computers", Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < list.tagCount() && entries.size() < MAX_COMPUTERS; i++)
        {
            NBTTagCompound t = list.getCompoundTagAt(i);
            if(!t.hasUniqueId("Id"))
                continue;
            // 坏的、被改过的数据当作没有
            CookieJar jar = CookieJar.decode(t.getByteArray("Cookies"), true, now);
            if(jar != null && !jar.isEmpty())
                entries.put(t.getLong("Pos"), new Entry(t.getUniqueId("Id"), jar));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        long now = System.currentTimeMillis();
        NBTTagList list = new NBTTagList();
        Iterator<Map.Entry<Long, Entry>> it = entries.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<Long, Entry> e = it.next();
            e.getValue().jar.purge(now);
            if(e.getValue().jar.isEmpty())
            {
                it.remove();
                continue;
            }
            NBTTagCompound t = new NBTTagCompound();
            t.setLong("Pos", e.getKey());
            t.setUniqueId("Id", e.getValue().id);
            t.setByteArray("Cookies", e.getValue().jar.encode(true, now));
            list.appendTag(t);
        }
        compound.setTag("Computers", list);
        return compound;
    }
}
