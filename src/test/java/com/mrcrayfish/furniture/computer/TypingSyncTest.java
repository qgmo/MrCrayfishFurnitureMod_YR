package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.Ipv6Address;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 打字同步：限频、限长、密码框只同步长度、服务端按自己解析的页面检查 */
class TypingSyncTest
{
    @Test
    void outboxSendsAtMostFourTimesASecondAndOnlyChanges()
    {
        TypingSync.Outbox o = new TypingSync.Outbox();
        o.baseline(TypingSync.outgoing(TypingSync.ADDRESS, false, "http://test.yr/"));
        o.offer(TypingSync.outgoing(TypingSync.ADDRESS, false, "http://test.yr/"));
        assertNull(o.poll(0), "和页面上的一样：不发");
        // 一秒里打了 20 个字：只发 4 条，每条是当时最新的内容
        int sent = 0;
        String last = null;
        StringBuilder typed = new StringBuilder();
        for(long now = 0; now < 1000; now += 50)
        {
            typed.append('a');
            o.offer(TypingSync.outgoing(TypingSync.ADDRESS, false, typed.toString()));
            TypingSync.Typed t = o.poll(now);
            if(t != null)
            {
                sent++;
                last = t.text;
            }
        }
        assertEquals(4, sent);
        assertEquals("aaaaaaaaaaaaaaaa", last, "第 4 条在第 750 毫秒：那时打了 16 个字");
        // 停手以后补发最后的内容
        assertEquals(typed.toString(), o.poll(1000).text);
        assertNull(o.poll(2000), "没变化不再发");
        // 两个框都改了：先改的先发，一个一个来
        o.offer(TypingSync.outgoing(3, false, "x"));
        o.offer(TypingSync.outgoing(5, false, "y"));
        assertEquals(3, o.poll(3000).field);
        assertNull(o.poll(3100));
        assertEquals(5, o.poll(3250).field);
        // 换页面：重来
        o.reset();
        assertEquals(0, o.pendingCount());
    }

    @Test
    void lengthIsCappedAndPasswordsOnlyHaveLength()
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < 1000; i++)
            sb.append('字');
        TypingSync.Typed t = TypingSync.outgoing(TypingSync.ADDRESS, false, sb.toString());
        assertEquals(TypingSync.MAX_CHARS, t.text.length());
        TypingSync.Typed pw = TypingSync.outgoing(2, true, "hunter2");
        assertTrue(pw.password);
        assertEquals("", pw.text, "密码本身不发");
        assertEquals(7, pw.length);
    }

    static PageFacts facts() throws Exception
    {
        String html = "<form><input name=a><input type=password name=p><textarea name=t></textarea><input type=checkbox name=c></form>";
        return PageFacts.of(new PageSnapshot("http://test.yr/", html.getBytes(StandardCharsets.UTF_8), PageParser.Mode.HTML, null), n -> (Ipv6Address) null);
    }

    @Test
    void serverChecksFieldsAgainstItsOwnParse() throws Exception
    {
        PageFacts f = facts();
        assertEquals(3, f.textFields.size());
        int text = -1, password = -1, checkbox = 99;
        for(java.util.Map.Entry<Integer, Boolean> e : f.textFields.entrySet())
        {
            if(e.getValue())
                password = e.getKey();
            else if(text < 0)
                text = e.getKey();
        }
        String[] why = new String[1];
        assertEquals("你好", TypingSync.accept(f, 7, 7, text, 2, "你好", why).text);
        assertEquals("http://x", TypingSync.accept(f, 7, 3, TypingSync.ADDRESS, 8, "http://x", why).text, "地址栏不看页面编号");
        // 密码框：只收长度，带文字的拒收
        TypingSync.Typed pw = TypingSync.accept(f, 7, 7, password, 5, "", why);
        assertTrue(pw.password);
        assertEquals(5, pw.length);
        assertNull(TypingSync.accept(f, 7, 7, password, 5, "secret", why));
        assertTrue(why[0].contains("password"));
        // 页面对不上、不是文本框、太长
        assertNull(TypingSync.accept(f, 7, 6, text, 1, "a", why));
        assertNull(TypingSync.accept(f, 7, 7, checkbox, 1, "a", why));
        assertNull(TypingSync.accept(f, 7, 7, text, 1, String.join("", Collections.nCopies(TypingSync.MAX_CHARS + 1, "a")), why));
        assertNull(TypingSync.accept(PageFacts.NONE, -1, -1, 0, 1, "a", why), "没有页面时只有地址栏");
    }

    @Test
    void wireFormat()
    {
        TypingSync.Typed t = new TypingSync.Typed(4, false, 3, "a字b");
        assertEquals(t, com.mrcrayfish.furniture.network.message.MessageComputerView.decodeTyping(4,
                new byte[]{0, 0, 3, 'a', (byte) 0xE5, (byte) 0xAD, (byte) 0x97, 'b'}));
        // 密码框带了文字：客户端也不收
        assertNull(com.mrcrayfish.furniture.network.message.MessageComputerView.decodeTyping(4, new byte[]{1, 0, 1, 'x'}));
        assertNull(com.mrcrayfish.furniture.network.message.MessageComputerView.decodeTyping(4, new byte[]{0}));
    }
}
