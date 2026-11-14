package com.mrcrayfish.furniture.client.browser;

/**
 * 单行输入框的内容（纯逻辑）：光标、选中（锚点到光标）、插入、删除、按字或按词移动、全选、剪切复制粘贴、长度上限。
 * 地址栏和网页里的文字输入框都用它。控制字符、换行在插入时去掉。按 UTF-16 下标算，不会停在代理对中间。
 */
public final class TextEdit
{
    private final int maxLength;
    private String text = "";
    private int cursor;
    private int anchor;
    /** 显示时从第几个字开始画（由界面维护，保证光标看得见） */
    public int viewStart;

    public TextEdit(int maxLength)
    {
        this.maxLength = maxLength;
    }

    public TextEdit(int maxLength, String initial)
    {
        this(maxLength);
        setText(initial);
    }

    public String getText()
    {
        return text;
    }

    public int getCursor()
    {
        return cursor;
    }

    public int getAnchor()
    {
        return anchor;
    }

    public int maxLength()
    {
        return maxLength;
    }

    public int selectionStart()
    {
        return Math.min(cursor, anchor);
    }

    public int selectionEnd()
    {
        return Math.max(cursor, anchor);
    }

    public boolean hasSelection()
    {
        return cursor != anchor;
    }

    public String selected()
    {
        return text.substring(selectionStart(), selectionEnd());
    }

    /** 整个换掉（光标到最后，不选中） */
    public void setText(String s)
    {
        String t = clean(s == null ? "" : s);
        if(t.length() > maxLength)
            t = cut(t, maxLength);
        text = t;
        cursor = anchor = text.length();
        viewStart = 0;
    }

    public void selectAll()
    {
        anchor = 0;
        cursor = text.length();
    }

    /** 把选中的换成 s（没有选中时在光标处插入），超过上限的部分丢掉 */
    public void insert(String s)
    {
        String add = clean(s);
        int start = selectionStart();
        int end = selectionEnd();
        int room = maxLength - (text.length() - (end - start));
        if(room <= 0 && add.length() > 0)
        {
            if(start == end)
                return;
            add = "";
        }
        if(add.length() > room)
            add = cut(add, room);
        text = text.substring(0, start) + add + text.substring(end);
        cursor = anchor = start + add.length();
    }

    public void backspace(boolean word)
    {
        if(hasSelection())
        {
            insert("");
            return;
        }
        if(cursor == 0)
            return;
        int to = word ? wordLeft(cursor) : stepLeft(cursor);
        text = text.substring(0, to) + text.substring(cursor);
        cursor = anchor = to;
    }

    public void delete(boolean word)
    {
        if(hasSelection())
        {
            insert("");
            return;
        }
        if(cursor >= text.length())
            return;
        int to = word ? wordRight(cursor) : stepRight(cursor);
        text = text.substring(0, cursor) + text.substring(to);
        anchor = cursor;
    }

    public void left(boolean word, boolean select)
    {
        int to;
        if(!select && hasSelection() && !word)
            to = selectionStart();
        else
            to = word ? wordLeft(cursor) : stepLeft(cursor);
        move(to, select);
    }

    public void right(boolean word, boolean select)
    {
        int to;
        if(!select && hasSelection() && !word)
            to = selectionEnd();
        else
            to = word ? wordRight(cursor) : stepRight(cursor);
        move(to, select);
    }

    public void home(boolean select)
    {
        move(0, select);
    }

    public void end(boolean select)
    {
        move(text.length(), select);
    }

    /** 把光标放到第 i 个字处（点击） */
    public void moveTo(int i, boolean select)
    {
        int to = Math.max(0, Math.min(text.length(), i));
        if(to > 0 && to < text.length() && Character.isLowSurrogate(text.charAt(to)))
            to--;
        move(to, select);
    }

    private void move(int to, boolean select)
    {
        cursor = to;
        if(!select)
            anchor = to;
    }

    /** 剪切：返回剪下的文字（没有选中时为 null） */
    public String cut()
    {
        if(!hasSelection())
            return null;
        String s = selected();
        insert("");
        return s;
    }

    private int stepLeft(int i)
    {
        if(i <= 0)
            return 0;
        int j = i - 1;
        if(j > 0 && Character.isLowSurrogate(text.charAt(j)) && Character.isHighSurrogate(text.charAt(j - 1)))
            j--;
        return j;
    }

    private int stepRight(int i)
    {
        if(i >= text.length())
            return text.length();
        int j = i + 1;
        if(j < text.length() && Character.isHighSurrogate(text.charAt(i)) && Character.isLowSurrogate(text.charAt(j)))
            j++;
        return j;
    }

    private int wordLeft(int i)
    {
        int j = i;
        while(j > 0 && !isWordChar(text.charAt(j - 1)))
            j--;
        while(j > 0 && isWordChar(text.charAt(j - 1)))
            j--;
        return j;
    }

    private int wordRight(int i)
    {
        int j = i;
        while(j < text.length() && !isWordChar(text.charAt(j)))
            j++;
        while(j < text.length() && isWordChar(text.charAt(j)))
            j++;
        return j;
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 去掉控制字符（含换行、§ 格式符） */
    static String clean(String s)
    {
        StringBuilder sb = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if(c >= 0x20 && c != 0x7F && c != '§')
                sb.append(c);
        }
        return sb.toString();
    }

    /** 截到 n 个 UTF-16 单位，不切开代理对 */
    static String cut(String s, int n)
    {
        if(n <= 0)
            return "";
        if(s.length() <= n)
            return s;
        int end = n;
        if(Character.isHighSurrogate(s.charAt(end - 1)))
            end--;
        return s.substring(0, end);
    }
}
