package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.chip.ChipNumbers;
import moe.qingu.youran.api.chip.ChipWear;
import moe.qingu.youran.api.chip.CpuChip;
import moe.qingu.youran.api.chip.CpuClock;
import moe.qingu.youran.api.chip.CpuThermal;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * 家具电脑的 CPU 运行数据与每秒一步的规则（悠然服务器魔改，服主 11-17：芯片品质接进电脑，同 YouranSCP 的普通电脑、个人电脑；
 * 数与公式在 YouranMod 的 {@code api.chip}）。存在方块实体 NBT 的 {@value #TAG}：
 * <pre>
 * {Clock:2000000,          // 调频旋钮的设定（kHz；没有 = 标准 2.0 GHz）
 *  Thermal:{…},            // CPU 温度与最近 60 秒（api.chip.CpuThermal）
 *  Time:123456L,           // 上次走温度时的世界时间（tick）
 *  Trip:1b,                // 上一次出事：1 过热断电、2 过热烧坏、3 寿命用完
 *  Crashed:1b}             // 死机了（浏览器排版时出了“死机”那种运行错误）：关机再开才好
 * </pre>
 * 负载 = 浏览器在忙（取页面、排版）的时间比例，操作者的客户端每秒报一次（只在内存；超过 3 秒没报当作空闲）。
 */
public final class CpuRuntime
{
    public static final String TAG = "Cpu";
    public static final byte TRIP_NONE = 0, TRIP_OVERHEAT = 1, TRIP_BURNT = 2, TRIP_WORN_OUT = 3;
    /** 操作者报的负载多久没更新就当作空闲（tick） */
    public static final int LOAD_STALE_TICKS = 60;

    /** 旋钮的设定（kHz；0 = 标准频率） */
    public int clockKHz;
    public final CpuThermal thermal = new CpuThermal();
    public long time = -1;
    public byte trip;
    public boolean crashed;

    /** 操作者报的负载（千分比）与报的时刻（世界时间） */
    public int loadPermille;
    public long loadAt = Long.MIN_VALUE / 2;

    /** 每秒一步的结果 */
    public enum Event
    {
        NONE,
        /** 过热断电（芯片少了一截寿命） */
        OVERHEAT,
        /** 过热断电，芯片烧坏了 */
        BURNT,
        /** 寿命用完了（开着机用完，或过热扣寿命扣完） */
        WORN_OUT
    }

    /** 现在的负载（0–1）：操作者报得够新才算 */
    public double load(long now)
    {
        return now - loadAt <= LOAD_STALE_TICKS ? loadPermille / 1000.0 : 0;
    }

    /**
     * 走一秒：温度；开着机（{@code running}：有电、开关开着、CPU 没坏）时记寿命、查过热。过热断电时调用方关掉电源开关。
     * {@code board} 是主板数据（会被改：用掉的寿命、报废），没有为 null（标称芯片：不记寿命）。
     */
    public Event second(ChipNumbers n, CpuChip chip, @Nullable NBTTagCompound board, double ambientK, boolean running, int actualKHz, double load,
                        Random random)
    {
        double ratio = chip.stdKHz > 0 ? actualKHz / (double) chip.stdKHz : 1;
        // 死机的 CPU 不跑指令（照样有电、照样空转发热）
        thermal.step(n, ambientK, running, crashed ? 0 : load, ratio);
        if(!running)
            return Event.NONE;
        if(!chip.nominal && ChipWear.addUsed(board, 1))
        {
            trip = TRIP_WORN_OUT;
            return Event.WORN_OUT;
        }
        if(thermal.overheated(chip.heatToleranceKs))
        {
            ChipWear.Overheat o = ChipWear.overheat(board, n, random);
            trip = o == ChipWear.Overheat.SCRAPPED ? TRIP_BURNT : o == ChipWear.Overheat.WORN_OUT ? TRIP_WORN_OUT : TRIP_OVERHEAT;
            crashed = false;
            return trip == TRIP_BURNT ? Event.BURNT : trip == TRIP_WORN_OUT ? Event.WORN_OUT : Event.OVERHEAT;
        }
        return Event.NONE;
    }

    /** 给界面看的数（发给开着这台电脑界面的人；客户端排版时也按里面的出错率掷运行错误） */
    public NBTTagCompound status(ChipNumbers n, CpuChip chip, double ambientK)
    {
        int[] settings = CpuClock.settings(chip, n);
        int setting = CpuClock.clamp(clockKHz, settings, chip.stdKHz);
        int index = 0;
        for(int i = 0; i < settings.length; i++)
        {
            if(settings[i] == setting)
                index = i;
        }
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("Set", setting);
        t.setInteger("Act", CpuClock.actualKHz(chip, setting));
        t.setInteger("Std", chip.stdKHz);
        t.setInteger("Min", chip.minKHz);
        t.setInteger("Max", chip.maxAdjKHz);
        t.setDouble("Temp", thermal.unset() ? 0 : thermal.tempC());
        t.setDouble("Heat", thermal.heatLevel(chip.heatToleranceKs, ambientK));
        t.setBoolean("Dead", chip.dead);
        t.setBoolean("Capped", CpuClock.capped(chip, setting));
        t.setBoolean("Nominal", chip.nominal);
        t.setBoolean("Crashed", crashed);
        t.setByte("Trip", trip);
        t.setDouble("Rate", CpuClock.faultRate(chip, setting, n));
        t.setDouble("CrashShare", CpuClock.crashShare(chip, setting, n));
        t.setLong("Life", chip.nominal || chip.remainingS() == Long.MAX_VALUE ? -1 : chip.remainingS() / 3600);
        t.setInteger("Idx", index);
        t.setInteger("Cnt", settings.length);
        return t;
    }

    /** 拧一格；返回变了没有 */
    public boolean turn(ChipNumbers n, CpuChip chip, int delta)
    {
        int[] settings = CpuClock.settings(chip, n);
        int next = CpuClock.turn(clockKHz, settings, chip.stdKHz, delta);
        if(next == clockKHz)
            return false;
        clockKHz = next;
        return true;
    }

    public boolean isEmpty()
    {
        return clockKHz == 0 && thermal.unset() && trip == 0 && !crashed;
    }

    public NBTTagCompound write()
    {
        NBTTagCompound t = new NBTTagCompound();
        if(clockKHz > 0)
            t.setInteger("Clock", clockKHz);
        if(!thermal.unset())
        {
            t.setTag("Thermal", thermal.write(new NBTTagCompound()));
            t.setLong("Time", time);
        }
        if(trip != 0)
            t.setByte("Trip", trip);
        if(crashed)
            t.setBoolean("Crashed", true);
        return t;
    }

    public void read(NBTTagCompound t)
    {
        clockKHz = Math.max(0, t.getInteger("Clock"));
        thermal.read(t.getCompoundTag("Thermal"));
        time = t.hasKey("Time") ? t.getLong("Time") : -1;
        trip = t.getByte("Trip");
        crashed = t.getBoolean("Crashed");
    }

    /**
     * 这一页排版时出不出运行错误（客户端，按服务端发来的出错率、死机比例）：标准频率下排这一页要 {@code layoutMs} 毫秒，
     * 这段时间里至少出一次错的概率 1 − e^(−率 × 秒)。返回 0 不出错、1 排版出错（这一页显示不出来，刷新重来）、2 死机。
     */
    public static int layoutFault(double rate, double crashShare, long layoutMs, Random random)
    {
        double p = CpuClock.faultChance(rate, layoutMs / 1000.0);
        if(p <= 0 || random.nextDouble() >= p)
            return 0;
        return random.nextDouble() < crashShare ? 2 : 1;
    }
}
