package com.mrcrayfish.furniture.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 网站证书的目录名（世界存档的 cfm/certs/<主机>/） */
class WebCertificatesTest
{
    @Test
    void dirNameFromHostKey()
    {
        assertEquals("minebay", WebCertificates.dirName("cfm:minebay"));
        assertEquals("othermod_shop", WebCertificates.dirName("othermod:shop"));
        assertEquals("a_b", WebCertificates.dirName("cfm:a/b"));
        assertNull(WebCertificates.dirName("cfm:.."));
        assertNull(WebCertificates.dirName("cfm:"));
    }

    @Test
    void noStoreWithoutNetwork()
    {
        // 网络没在运行（单元测试、客户端）：找不到世界存档，不存
        assertNull(WebCertificates.store("cfm:minebay"));
    }
}
