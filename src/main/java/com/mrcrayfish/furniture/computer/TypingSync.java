package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 操作者正在打的字同步给旁观者（服主 11-14）：地址栏和表单文本框里还没提交的内容。纯逻辑，两端都用。
 * <ul>
 * <li>只是给旁观者看：操作者本地的内容照旧按电脑存着（用户输入不丢的规定不变），这里只读不改；</li>
 * <li>限频：操作者每 {@value #INTERVAL_MS} 毫秒最多发一条（{@link Outbox}），服务端再按玩家限频；</li>
 * <li>限长：每条最多 {@value #MAX_CHARS} 个字符，多的截掉；</li>
 * <li>密码框（{@code type=password}）只同步长度，旁观者看到的是圆点。是不是密码框由服务端按它自己解析的页面判断（{@link PageFacts}），
 * 密码框带了文字的消息直接拒收，转给旁观者的也只有长度。</li>
 * </ul>
 */
public final class TypingSync
{
    /** 地址栏的编号（表单控件用页面里的编号，从 0 起） */
    public static final int ADDRESS = -1;
    public static final int MAX_CHARS = 256;
    /** 密码框长度的上限（同表单控件的 maxlength 上限） */
    public static final int MAX_LENGTH = 512;
    public static final long INTERVAL_MS = 250;
    /** 服务端：每个玩家每秒最多几条、最多攒几条 */
    public static final double SERVER_RATE = 6;
    public static final double SERVER_BURST = 10;

    private TypingSync() {}

    /** 一个框里现在的内容（密码框只有长度，{@link #text} 为空） */
    public static final class Typed
    {
        public final int field;
        public final boolean password;
        public final int length;
        public final String text;

        public Typed(int field, boolean password, int length, String text)
        {
            this.field = field;
            this.password = password;
            this.length = length;
            this.text = text;
        }

        @Override
        public boolean equals(Object o)
        {
            if(!(o instanceof Typed))
                return false;
            Typed t = (Typed) o;
            return t.field == field && t.password == password && t.length == length && t.text.equals(text);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(field, password, length, text);
        }

        @Override
        public String toString()
        {
            return field + (password ? "=•×" + length : "=" + text);
        }
    }

    /** 操作者这边：要发给服务端的那条（截到 {@value #MAX_CHARS} 个字符；密码框只留长度） */
    public static Typed outgoing(int field, boolean password, String text)
    {
        if(password)
            return new Typed(field, true, Math.min(text.length(), MAX_LENGTH), "");
        String t = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        return new Typed(field, false, t.length(), t);
    }

    /**
     * 服务端：检查操作者发来的一条，合格时返回要转给旁观者的那条，不合格时返回 null 并把原因写进 {@code reason[0]}。
     *
     * @param facts       服务端自己解析的当前页面
     * @param currentPage 服务端当前页面的上传编号
     * @param page        消息说的上传编号（地址栏不看它）
     */
    @Nullable
    public static Typed accept(PageFacts facts, int currentPage, int page, int field, int length, String text, String[] reason)
    {
        if(text.length() > MAX_CHARS || length < 0 || length > MAX_LENGTH)
        {
            reason[0] = "typing too long (" + text.length() + " chars, length " + length + ")";
            return null;
        }
        if(field == ADDRESS)
            return new Typed(ADDRESS, false, text.length(), text);
        if(page != currentPage)
        {
            reason[0] = "typing for a page that is not shown (" + page + " != " + currentPage + ")";
            return null;
        }
        Boolean password = facts.textFields.get(field);
        if(password == null)
        {
            reason[0] = "typing into field " + field + " which is not a text field on the page";
            return null;
        }
        if(password)
        {
            if(!text.isEmpty())
            {
                reason[0] = "typing sent the text of password field " + field;
                return null;
            }
            return new Typed(field, true, length, "");
        }
        return new Typed(field, false, text.length(), text);
    }

    /**
     * 操作者这边的发件箱：记下每个框最后发出去的内容，内容变了就排队，每 {@value #INTERVAL_MS} 毫秒最多发一条（先改的先发，发的是最新内容）。
     */
    public static final class Outbox
    {
        private final Map<Integer, Typed> sent = new LinkedHashMap<>();
        private final Map<Integer, Typed> pending = new LinkedHashMap<>();
        private long last = Long.MIN_VALUE;

        /** 换页面了：都重来（页面上的初始值不用发） */
        public void reset()
        {
            sent.clear();
            pending.clear();
        }

        /** 当作已经发过（页面上的初始值：没改过就不发） */
        public void baseline(Typed t)
        {
            sent.putIfAbsent(t.field, t);
        }

        /** 框里现在的内容；和最后发出去的不一样就排队（同一个框只留最新的） */
        public void offer(Typed t)
        {
            Typed s = sent.get(t.field);
            if(t.equals(s))
                pending.remove(t.field);
            else
                pending.put(t.field, t);
        }

        /** 到时间了就取一条要发的；没有或还没到时间返回 null */
        @Nullable
        public Typed poll(long now)
        {
            if(pending.isEmpty() || (last != Long.MIN_VALUE && now - last < INTERVAL_MS))
                return null;
            Iterator<Typed> it = pending.values().iterator();
            Typed t = it.next();
            it.remove();
            sent.put(t.field, t);
            last = now;
            return t;
        }

        public int pendingCount()
        {
            return pending.size();
        }
    }
}
