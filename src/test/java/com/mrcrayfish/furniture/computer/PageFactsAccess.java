package com.mrcrayfish.furniture.computer;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Map;

/** 测试用：直接造一份只有画板的 PageFacts */
final class PageFactsAccess
{
    private PageFactsAccess() {}

    static PageFacts with(Map<Integer, int[]> canvases)
    {
        try
        {
            Constructor<PageFacts> c = PageFacts.class.getDeclaredConstructor(java.util.Set.class, Map.class, Map.class);
            c.setAccessible(true);
            return c.newInstance(Collections.emptySet(), Collections.emptyMap(), canvases);
        }
        catch(ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
