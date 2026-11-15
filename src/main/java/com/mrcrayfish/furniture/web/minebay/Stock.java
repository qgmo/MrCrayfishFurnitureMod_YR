package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * MineBay 商品的库存（服主 11-15：全服统一、按现实时间；做成通用商品选项）。两种规则：
 * <ul>
 * <li>{@link Rule#DAILY}“按月上限、每天补”：上限是一个月的量；每个现实日补上限的 1/30，连续地补（按毫秒折算），不超过上限；卖完就没了，
 * 等慢慢补回来。（SCP 的工具盘用）</li>
 * <li>{@link Rule#MONTHLY}“按月整批重置”：每个现实月（北京时间）开始时补满到上限，平时不补。（光刻掩模用）</li>
 * </ul>
 * 一开始（第一次有人看到这件商品）是满的。规则或上限改了：规则变了重新从满的开始；上限变小时截到新上限。
 * 纯逻辑；状态 {@link State} 随 MineBay 存档保存，时间由调用方给（毫秒）。份数按“份”算（一份是商品页上的“每份 N 个”）。
 */
public final class Stock
{
    public static final long DAY_MS = 24L * 3600 * 1000;
    /** “一个月”按 30 天折算每天补多少 */
    public static final int DAYS_PER_MONTH = 30;
    public static final long MONTH_MS = DAYS_PER_MONTH * DAY_MS;
    /** 上限（每月最多几份） */
    public static final int MAX_PER_MONTH = 100_000;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 浮点补货的容差（防 0.999999… 少算一份） */
    private static final double EPS = 1e-9;

    private Stock() {}

    public enum Rule
    {
        /** 按月上限、每天补上限的 1/30（连续补） */
        DAILY("daily"),
        /** 每个现实月开始时补满，不按天补 */
        MONTHLY("monthly");

        public final String code;

        Rule(String code)
        {
            this.code = code;
        }

        @Nullable
        public static Rule of(@Nullable String code)
        {
            for(Rule r : values())
            {
                if(r.code.equalsIgnoreCase(code == null ? "" : code.trim()))
                    return r;
            }
            return null;
        }
    }

    /** 一件商品的库存设置：每月上限 + 规则 */
    public static final class Spec
    {
        public final int perMonth;
        public final Rule rule;

        public Spec(int perMonth, Rule rule)
        {
            if(perMonth < 1 || perMonth > MAX_PER_MONTH)
                throw new IllegalArgumentException("MineBay 库存上限要在 1–" + MAX_PER_MONTH + "：" + perMonth);
            if(rule == null)
                throw new IllegalArgumentException("MineBay 库存要给规则（daily / monthly）");
            this.perMonth = perMonth;
            this.rule = rule;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Spec && ((Spec) o).perMonth == perMonth && ((Spec) o).rule == rule;
        }

        @Override
        public int hashCode()
        {
            return perMonth * 31 + rule.hashCode();
        }

        @Override
        public String toString()
        {
            return perMonth + "/" + rule.code;
        }
    }

    /** 库存状态（可变；主线程上用） */
    public static final class State
    {
        Rule rule;
        int cap;
        /** 现在有多少（每天补时有小数） */
        double level;
        /** {@link #level} 是哪一刻的（毫秒） */
        long since;
        /** 按月重置：上次补满是哪个月（年 × 12 + 月 − 1，北京时间） */
        int period;

        State(Rule rule, int cap, double level, long since, int period)
        {
            this.rule = rule;
            this.cap = cap;
            this.level = level;
            this.since = since;
            this.period = period;
        }

        public NBTTagCompound write()
        {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("rule", rule.code);
            t.setInteger("cap", cap);
            t.setDouble("level", level);
            t.setLong("since", since);
            t.setInteger("period", period);
            return t;
        }

        @Nullable
        public static State read(NBTTagCompound t)
        {
            Rule r = Rule.of(t.getString("rule"));
            int cap = t.getInteger("cap");
            double level = t.getDouble("level");
            if(r == null || cap < 1 || cap > MAX_PER_MONTH || Double.isNaN(level) || Double.isInfinite(level))
                return null;
            return new State(r, cap, Math.max(0, Math.min(cap, level)), t.getLong("since"), t.getInteger("period"));
        }
    }

    /** 这一刻是哪个月（年 × 12 + 月 − 1，北京时间） */
    static int period(long now)
    {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(ZONE);
        return t.getYear() * 12 + t.getMonthValue() - 1;
    }

    /** 下个月 1 日 0 点（北京时间）是哪一刻 */
    static long nextPeriodStart(long now)
    {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(ZONE).withDayOfMonth(1).toLocalDate().atStartOfDay(ZONE).plusMonths(1);
        return t.toInstant().toEpochMilli();
    }

    /** 新的库存（满的） */
    public static State fresh(Spec spec, long now)
    {
        return new State(spec.rule, spec.perMonth, spec.perMonth, now, period(now));
    }

    /**
     * 补到 {@code now}：按天补的加上这段时间该补的；按月重置的换月了就补满。设置变了时照规则调整（见类说明）。
     * {@code state} 为 null 时给一个满的。返回同一个（或新建的）状态。
     */
    public static State advance(@Nullable State state, Spec spec, long now)
    {
        if(state == null || state.rule != spec.rule)
            return fresh(spec, now);
        if(state.cap != spec.perMonth)
        {
            state.cap = spec.perMonth;
            state.level = Math.min(state.level, state.cap);
        }
        if(state.rule == Rule.DAILY)
        {
            if(now > state.since)
            {
                state.level = Math.min(state.cap, state.level + (double) state.cap * (now - state.since) / MONTH_MS);
                state.since = now;
            }
        }
        else
        {
            int p = period(now);
            if(p != state.period)
            {
                state.level = state.cap;
                state.period = p;
            }
            state.since = Math.max(state.since, now);
        }
        return state;
    }

    /** 现在能卖几份（整份） */
    public static long available(State state)
    {
        return (long) Math.floor(state.level + EPS);
    }

    /** 卖掉 {@code n} 份；不够时不动、返回 false */
    public static boolean take(State state, int n)
    {
        if(n < 0 || available(state) < n)
            return false;
        state.level = Math.max(0, state.level - n);
        return true;
    }

    /** 已经付了款要发的（预留过的）：照扣，不够时扣到 0（不让付了钱的落空） */
    public static void takeReserved(State state, int n)
    {
        state.level = Math.max(0, state.level - Math.max(0, n));
    }

    /**
     * 再过多久能多一份（毫秒）：按天补的按补货速度算；按月重置的是到下个月 1 日的时间。已满时为 0。
     */
    public static long untilNext(State state, long now)
    {
        if(state.level + EPS >= state.cap)
            return 0;
        if(state.rule == Rule.MONTHLY)
            return Math.max(0, nextPeriodStart(now) - now);
        double need = Math.floor(state.level + EPS) + 1 - state.level;
        return (long) Math.ceil(need * MONTH_MS / state.cap);
    }

    /** 给人看的规则：“每月 14 份，每天补” / “每月 2 份，每月 1 日补满” */
    public static String describe(Spec spec)
    {
        return "每月 " + spec.perMonth + " 份，" + (spec.rule == Rule.DAILY ? "每天补" : "每月 1 日补满");
    }

    /** 给人看的“多久以后补”：约 3 小时 / 约 2 天 / 12 月 1 日 */
    public static String describeWait(State state, long now)
    {
        long ms = untilNext(state, now);
        if(state.rule == Rule.MONTHLY)
        {
            ZonedDateTime t = Instant.ofEpochMilli(nextPeriodStart(now)).atZone(ZONE);
            return t.getMonthValue() + " 月 1 日补满";
        }
        if(ms <= 0)
            return "";
        long min = (ms + 59_999) / 60_000;
        if(min < 60)
            return "约 " + min + " 分钟后补 1 份";
        long h = (min + 59) / 60;
        if(h < 48)
            return "约 " + h + " 小时后补 1 份";
        return "约 " + ((h + 23) / 24) + " 天后补 1 份";
    }
}
