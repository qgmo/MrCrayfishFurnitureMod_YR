package com.mrcrayfish.furniture.computer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormEncodingTest
{
    @Test
    void encodesLikeBrowsers()
    {
        String s = FormEncoding.encode(Arrays.asList(FormEncoding.field("name", "张三 李四"), FormEncoding.field("q", "a+b&c=d/e?*-._~"), FormEncoding.field("e", "")));
        assertEquals("name=%E5%BC%A0%E4%B8%89+%E6%9D%8E%E5%9B%9B&q=a%2Bb%26c%3Dd%2Fe%3F*-._%7E&e=", s);
    }

    @Test
    void roundTrip()
    {
        List<Map.Entry<String, String>> in = Arrays.asList(FormEncoding.field("i", "3"), FormEncoding.field("t", "ab 你好 😀 %"), FormEncoding.field("i", "4"));
        List<Map.Entry<String, String>> out = FormEncoding.decode(FormEncoding.encode(in));
        assertEquals(in, out);
    }

    @Test
    void decodesLooseInput()
    {
        List<Map.Entry<String, String>> out = FormEncoding.decode("a=1&&b&c=%ZZ&d=%E4%BD%A0");
        assertEquals(4, out.size());
        assertEquals("", out.get(1).getValue());
        assertEquals("%ZZ", out.get(2).getValue());
        assertEquals("你", out.get(3).getValue());
        assertTrue(FormEncoding.decode("").isEmpty());
        assertTrue(FormEncoding.decode(null).isEmpty());
    }
}
