package com.mrcrayfish.furniture.gui;

import net.minecraft.client.gui.Gui;

/**
 * 电视设置界面用的小图标（悠然服务器魔改，服主 11-18）：放大镜（搜索）、人的上半身（UP 主 / 主播）。
 * 自己画的 8×8 像素图，用填色矩形一行一段画出来，不用图片素材。
 */
public final class TvIcons
{
    public static final int SIZE = 8;

    static final String[] MAGNIFIER = {
            " ###    ",
            "#   #   ",
            "#   #   ",
            "#   #   ",
            " ####   ",
            "    ##  ",
            "     ## ",
            "      ##"
    };

    static final String[] PERSON = {
            "  ####  ",
            " ###### ",
            " ###### ",
            "  ####  ",
            "        ",
            "  ####  ",
            " ###### ",
            "########"
    };

    private TvIcons() {}

    public static void magnifier(int x, int y, int scale, int argb)
    {
        draw(MAGNIFIER, x, y, scale, argb);
    }

    public static void person(int x, int y, int scale, int argb)
    {
        draw(PERSON, x, y, scale, argb);
    }

    /** 每行里连续的 # 画成一个矩形 */
    static void draw(String[] rows, int x, int y, int scale, int argb)
    {
        for(int r = 0; r < rows.length; r++)
        {
            String row = rows[r];
            int c = 0;
            while(c < row.length())
            {
                if(row.charAt(c) != '#')
                {
                    c++;
                    continue;
                }
                int start = c;
                while(c < row.length() && row.charAt(c) == '#')
                    c++;
                Gui.drawRect(x + start * scale, y + r * scale, x + c * scale, y + (r + 1) * scale, argb);
            }
        }
    }
}
