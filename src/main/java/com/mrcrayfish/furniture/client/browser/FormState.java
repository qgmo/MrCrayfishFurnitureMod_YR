package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.FormEncoding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一页上表单里填的东西（每条历史记录一份，关界面、断电、断线、提交失败都留着；只有提交成功后才清空那一个表单）。
 */
public final class FormState
{
    final Map<Integer, TextEdit> text = new HashMap<>();
    final Map<Integer, Boolean> checked = new HashMap<>();
    final Map<Integer, Integer> selected = new HashMap<>();

    /** 第一次看到这页的控件时按页面上的初始值填（已经有的不动） */
    void init(PageDocument doc)
    {
        for(PageDocument.Control c : doc.controls)
        {
            if(c.isTextual())
                text.computeIfAbsent(c.n, k -> new TextEdit(Math.max(1, c.maxLength), c.value));
            else if(c.kind == PageDocument.Kind.CHECKBOX || c.kind == PageDocument.Kind.RADIO)
                checked.putIfAbsent(c.n, c.checked);
            else if(c.kind == PageDocument.Kind.SELECT)
                selected.putIfAbsent(c.n, c.selected);
        }
    }

    /** 把一个表单恢复成页面上的初始值 */
    void reset(PageDocument doc, int form)
    {
        for(PageDocument.Control c : doc.controls)
        {
            if(c.form != form)
                continue;
            text.remove(c.n);
            checked.remove(c.n);
            selected.remove(c.n);
        }
        init(doc);
    }

    public TextEdit text(int n)
    {
        return text.get(n);
    }

    public boolean isChecked(int n)
    {
        Boolean b = checked.get(n);
        return b != null && b;
    }

    public int selected(int n)
    {
        Integer i = selected.get(n);
        return i == null ? 0 : i;
    }

    void toggle(PageDocument doc, PageDocument.Control c)
    {
        if(c.kind == PageDocument.Kind.CHECKBOX)
        {
            checked.put(c.n, !isChecked(c.n));
        }
        else if(c.kind == PageDocument.Kind.RADIO)
        {
            for(PageDocument.Control o : doc.controls)
            {
                if(o.kind == PageDocument.Kind.RADIO && o.form == c.form && o.name.equals(c.name))
                    checked.put(o.n, false);
            }
            checked.put(c.n, true);
        }
        else if(c.kind == PageDocument.Kind.SELECT && !c.optionValues.isEmpty())
        {
            selected.put(c.n, (selected(c.n) + 1) % c.optionValues.size());
        }
    }

    /**
     * 按 HTML 的规则收集一个表单要提交的字段：有名字、没禁用；复选框、单选框只收选中的；下拉框收选中的那项；
     * 按钮只收按下的那一个。
     */
    List<Map.Entry<String, String>> fields(PageDocument doc, int form, int submitter)
    {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for(PageDocument.Control c : doc.controls)
        {
            if(c.form != form || c.name.isEmpty() || c.disabled)
                continue;
            switch(c.kind)
            {
                case TEXT:
                case PASSWORD:
                case TEXTAREA:
                {
                    TextEdit t = text.get(c.n);
                    out.add(FormEncoding.field(c.name, t == null ? c.value : t.getText()));
                    break;
                }
                case HIDDEN:
                    out.add(FormEncoding.field(c.name, c.value));
                    break;
                case CHECKBOX:
                case RADIO:
                    if(isChecked(c.n))
                        out.add(FormEncoding.field(c.name, c.value));
                    break;
                case SELECT:
                    if(!c.optionValues.isEmpty())
                        out.add(FormEncoding.field(c.name, c.optionValues.get(Math.min(selected(c.n), c.optionValues.size() - 1))));
                    break;
                case SUBMIT:
                    if(c.n == submitter)
                        out.add(FormEncoding.field(c.name, c.value));
                    break;
                default:
                    break;
            }
        }
        return out;
    }
}
