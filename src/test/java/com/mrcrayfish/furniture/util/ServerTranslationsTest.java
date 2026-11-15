package com.mrcrayfish.furniture.util;

import com.mrcrayfish.furniture.web.minebay.Categories;
import com.mrcrayfish.furniture.web.minebay.Listing;
import com.mrcrayfish.furniture.web.minebay.ShopParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServerTranslationsTest
{
    @TempDir
    Path tmp;

    private final List<String> warnings = new ArrayList<>();

    private ServerTranslations load(Path dir, Path... mods)
    {
        return ServerTranslations.load(dir, Arrays.asList(mods), warnings::add);
    }

    private static void write(Path p, String text) throws IOException
    {
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void zip(Path zip, String... nameAndText) throws IOException
    {
        try(ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            for(int i = 0; i < nameAndText.length; i += 2)
            {
                out.putNextEntry(new ZipEntry(nameAndText[i]));
                out.write(nameAndText[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    @Test
    void parsesLikeTheGame() throws IOException
    {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("zh_cn.lang"), "﻿# 注释\n\nitem.saddle.name=鞍\nitem.a.name=%d 个 %1$s %.2f\nbroken line\n=nokey\nitem.eq.name=a=b\n");
        ServerTranslations t = load(dir);
        assertEquals("鞍", t.local("item.saddle.name"));
        assertEquals("%s 个 %1$s %s", t.local("item.a.name"));
        assertEquals("a=b", t.local("item.eq.name"));
        assertNull(t.local("broken line"));
    }

    @Test
    void priorityDirThenModsThenEnglishThenNothing() throws IOException
    {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("zh_cn.lang"), "k.dir=目录\nk.both=目录优先\n");
        Path mod = tmp.resolve("mod.jar");
        zip(mod, "assets/cfm/lang/zh_cn.lang", "k.both=模组\nk.mod=模组的\n", "assets/cfm/lang/en_us.lang", "k.both=En both\nk.mod=En mod\nk.en=English only\n");
        ServerTranslations t = load(dir, mod);
        assertEquals("目录优先", t.local("k.both"));
        assertEquals("目录", t.local("k.dir"));
        assertEquals("模组的", t.local("k.mod"));
        assertNull(t.local("k.en"), "英文不算中文");
        assertEquals("English only", t.format("k.en"));
        assertEquals("模组的", t.format("k.mod"), "有中文时不用英文");
        assertNull(t.format("k.none"));
        assertEquals("English only", t.english("k.en"));
    }

    @Test
    void laterFileNameWinsInDirectory() throws IOException
    {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("zz-override.lang"), "k.a=覆盖\n");
        write(dir.resolve("zh_cn.lang"), "k.a=原版\nk.b=原版 b\n");
        write(dir.resolve("a-first.lang"), "k.a=最先\nk.c=最先 c\n");
        ServerTranslations t = load(dir);
        assertEquals("覆盖", t.local("k.a"));
        assertEquals("原版 b", t.local("k.b"));
        assertEquals("最先 c", t.local("k.c"));
    }

    @Test
    void resourcePackZipAndFolder() throws IOException
    {
        Path dir = tmp.resolve("lang");
        Files.createDirectories(dir);
        zip(dir.resolve("b-pack.zip"), "assets/minecraft/lang/zh_cn.lang", "item.diamond.name=钻石\nk.x=zip\n", "assets/minecraft/lang/en_us.lang", "item.diamond.name=Diamond\n",
                "assets/other/lang/ja_jp.lang", "item.diamond.name=ダイヤ\n");
        write(dir.resolve("c-folder/assets/minecraft/lang/zh_cn.lang"), "k.x=folder\nitem.gold.name=金\n");
        write(dir.resolve("c-folder/assets/minecraft/lang/ZH_CN.LANG.bak"), "k.x=wrong\n");
        write(dir.resolve("readme.txt"), "item.diamond.name=不是语言文件\n");
        ServerTranslations t = load(dir);
        assertEquals("钻石", t.local("item.diamond.name"));
        assertEquals("folder", t.local("k.x"), "文件夹排在 zip 后面");
        assertEquals("金", t.local("item.gold.name"));
        assertNull(t.english("item.diamond.name"), "语言目录里只读 zh_cn");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void badFilesDoNotCrash() throws IOException
    {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("a-notzip.zip"), "this is not a zip");
        write(dir.resolve("b-empty.lang"), "");
        write(dir.resolve("c-garbage.lang"), "\u0000\u0001� binary ===\n===\n=\n\n#\n");
        Files.createDirectories(dir.resolve("d-empty-pack/assets"));
        Files.write(dir.resolve("e-invalid-utf8.lang"), new byte[]{(byte) 0xff, (byte) 0xfe, '=', (byte) 0xc3, '\n', 'k', '.', 'o', 'k', '=', 'y', '\n'});
        Path bigFile = dir.resolve("f-huge.lang");
        try(OutputStream out = Files.newOutputStream(bigFile))
        {
            byte[] line = "k.huge=0123456789012345678901234567890123456789012345678901234567890123\n".getBytes(StandardCharsets.UTF_8);
            for(long n = 0; n <= LangFile.MAX_BYTES / line.length + 1; n++)
                out.write(line);
        }
        write(dir.resolve("g-good.lang"), "k.good=好\n");
        Path brokenJar = tmp.resolve("broken.jar");
        write(brokenJar, "PK nope");
        Path missingJar = tmp.resolve("missing.jar");
        ServerTranslations t = load(dir, brokenJar, missingJar);
        assertEquals("好", t.local("k.good"));
        assertEquals("y", t.local("k.ok"));
        assertNull(t.local("k.huge"), "超大文件整个跳过");
        assertFalse(warnings.isEmpty(), "坏文件要记警告");
    }

    @Test
    void missingOrNullDirectoryIsFine() throws IOException
    {
        Path mod = tmp.resolve("mod.jar");
        zip(mod, "assets/cfm/lang/zh_cn.lang", "k.mod=模组\n");
        assertEquals("模组", load(tmp.resolve("nope"), mod).local("k.mod"));
        assertEquals("模组", ServerTranslations.load(null, Collections.singletonList(mod), warnings::add).local("k.mod"));
        assertTrue(ServerTranslations.empty().isEmpty());
        assertNull(ServerTranslations.empty().local("k"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void modFolderInDevelopment() throws IOException
    {
        Path folder = tmp.resolve("classes");
        write(folder.resolve("assets/cfm/lang/zh_cn.lang"), "k.dev=开发\n");
        write(folder.resolve("assets/cfm/lang/en_us.lang"), "k.dev=dev\n");
        ServerTranslations t = load(null, folder);
        assertEquals("开发", t.local("k.dev"));
        assertEquals("dev", t.english("k.dev"));
    }

    @Test
    void namesFromTranslationsSortInChineseOrderAndAreSearchable() throws IOException
    {
        Path dir = tmp.resolve("lang");
        write(dir.resolve("zh_cn.lang"), "item.diamond.name=钻石\nitem.saddle.name=鞍\nitem.book.name=书\ntile.clayHardened.name=陶瓦\n");
        ServerTranslations t = load(dir);
        String[][] items = {{"diamond", "item.diamond"}, {"saddle", "item.saddle"}, {"book", "item.book"}, {"terracotta", "tile.clayHardened"}};
        List<Listing> all = new ArrayList<>();
        int seq = 0;
        for(String[] it : items)
        {
            String zh = t.local(it[1] + ".name");
            all.add(new Listing("config:" + it[0], seq++, Categories.MISC, "minecraft:" + it[0], 1, null, zh, (zh + " " + it[0] + " minecraft:" + it[0]).toLowerCase(),
                    "minecraft:emerald", 1, 64, new ArrayList<>()));
        }
        List<String> byName = ShopParams.select(all, null, "", ShopParams.Sort.NAME).stream().map(l -> l.id).collect(Collectors.toList());
        assertEquals(Arrays.asList("config:saddle", "config:book", "config:terracotta", "config:diamond"), byName, "按拼音：鞍 书 陶 钻");
        assertEquals(1, ShopParams.select(all, null, "钻石", ShopParams.Sort.DEFAULT).size());
        assertEquals(1, ShopParams.select(all, null, "diamond", ShopParams.Sort.DEFAULT).size());
        assertEquals(1, ShopParams.select(all, null, "minecraft:diamond", ShopParams.Sort.DEFAULT).size());
    }
}
