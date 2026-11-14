package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;

/**
 * 电脑页面的上限（服主 11-14 定）：XHTML 最大 {@value #MAX_BYTES} 字节、最多 {@value #MAX_ELEMENTS} 个元素、最深 {@value #MAX_DEPTH} 层。
 * 服务端收旁观同步的页面、旁观者渲染前、浏览器渲染前都按它检查；只看大小与结构，不看内容。
 * <p>
 * 检查用流式读（StAX）：不建 DOM、不处理 DTD 与外部实体，数到超限就停，所以在真正解析之前就能拒绝。
 */
public final class PageLimits
{
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_ELEMENTS = 2000;
    public static final int MAX_DEPTH = 64;

    public enum Problem
    {
        TOO_LARGE, TOO_MANY_ELEMENTS, TOO_DEEP, MALFORMED
    }

    private PageLimits() {}

    /** 不超限返回 null */
    @Nullable
    public static Problem check(byte[] xhtml)
    {
        if(xhtml.length > MAX_BYTES)
            return Problem.TOO_LARGE;
        XMLStreamReader r = null;
        try
        {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            f.setProperty(XMLInputFactory.IS_COALESCING, false);
            r = f.createXMLStreamReader(new ByteArrayInputStream(xhtml));
            int elements = 0;
            int depth = 0;
            boolean root = false;
            while(r.hasNext())
            {
                int e = r.next();
                if(e == XMLStreamConstants.START_ELEMENT)
                {
                    root = true;
                    if(++elements > MAX_ELEMENTS)
                        return Problem.TOO_MANY_ELEMENTS;
                    if(++depth > MAX_DEPTH)
                        return Problem.TOO_DEEP;
                }
                else if(e == XMLStreamConstants.END_ELEMENT)
                {
                    depth--;
                }
            }
            return root ? null : Problem.MALFORMED;
        }
        catch(XMLStreamException | RuntimeException e)
        {
            return Problem.MALFORMED;
        }
        finally
        {
            if(r != null)
            {
                try
                {
                    r.close();
                }
                catch(XMLStreamException ignored) {}
            }
        }
    }
}
