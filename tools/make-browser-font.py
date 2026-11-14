"""家具电脑浏览器的网页字体 YR Sans（常规、粗体）。

用法：python make.py <NotoSansSC[wght].ttf> <NotoSansTC[wght].ttf> <输出目录>
（Google Fonts 仓库 ofl/notosanssc、ofl/notosanstc 里的可变字体，版本 2.004；fontTools 4.x）

- 简体：GB2312 全部字符 + ASCII + 拉丁-1 补充 + 常用标点符号，取自 Noto Sans SC（字形照大陆规范）；
- 繁体：Big5 常用字（一级字，0xA440–0xC67E，5401 个）里 GB2312 没有的 2073 个，以及 Big5 符号区里 GB2312 没有的，
  取自 Noto Sans TC（字形照台湾规范）；两边都有的字用简体那份（同一个码位只能有一个字形）；
- 两个可变字体各取字重 400、700 的实例，去掉提示（hinting），合并成一个字体，按 OFL 1.1 的保留名称条款改名为 YR Sans。
"""
import os
import sys
import tempfile

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

sc_src, tc_src, out_dir = sys.argv[1:4]


def gb2312_chars():
    chars = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100))
    chars |= set(range(0x2000, 0x2070)) | set(range(0x2190, 0x2200)) | set(range(0x2460, 0x24FF)) | set(range(0x2500, 0x2580))
    chars |= set(range(0x3000, 0x3040)) | set(range(0xFF00, 0xFFF0)) | {0x20AC, 0x2116, 0x2122, 0x00D7, 0x00F7, 0x30FB}
    for hi in range(0xA1, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(ord(bytes([hi, lo]).decode('gb2312')))
            except UnicodeDecodeError:
                pass
    return chars


def big5_chars(first, last):
    out = set()
    for code in range(first, last + 1):
        hi, lo = code >> 8, code & 0xFF
        if not (0x40 <= lo <= 0x7E or 0xA1 <= lo <= 0xFE):
            continue
        try:
            out.add(ord(bytes([hi, lo]).decode('big5')))
        except UnicodeDecodeError:
            pass
    return out


def subset_instance(src, weight, unicodes, keep_layout, path):
    f = TTFont(src)
    inst = instancer.instantiateVariableFont(f, {"wght": weight}, updateFontNames=False)
    for t in ('STAT', 'fvar', 'gvar', 'avar', 'HVAR', 'MVAR'):
        if t in inst:
            del inst[t]
    opts = subset.Options()
    opts.layout_features = ['kern', 'liga', 'locl', 'palt', 'halt'] if keep_layout else []
    opts.name_IDs = ['*']
    opts.name_languages = ['*']
    opts.hinting = False
    opts.notdef_outline = True
    opts.glyph_names = False
    if not keep_layout:
        opts.drop_tables += ['GSUB', 'GPOS', 'GDEF', 'BASE']
    # 横排用不到竖排度量，两份字体合并时也省事
    opts.drop_tables += ['vhea', 'vmtx', 'VORG']
    s = subset.Subsetter(opts)
    s.populate(unicodes=sorted(unicodes))
    s.subset(inst)
    have = set(inst.getBestCmap().keys())
    inst.save(path)
    return have


sc_chars = gb2312_chars()
tc_wanted = (big5_chars(0xA440, 0xC67E) | big5_chars(0xA140, 0xA3BF)) - sc_chars
tmp = tempfile.mkdtemp()
for weight, style in ((400, 'Regular'), (700, 'Bold')):
    sc_path = os.path.join(tmp, 'sc-%s.ttf' % style)
    tc_path = os.path.join(tmp, 'tc-%s.ttf' % style)
    sc_have = subset_instance(sc_src, weight, sc_chars, True, sc_path)
    tc_have = subset_instance(tc_src, weight, tc_wanted - sc_have, False, tc_path)
    merged = Merger().merge([sc_path, tc_path])
    name = merged['name']
    fam = 'YR Sans'
    for rec in list(name.names):
        if rec.nameID in (1, 16):
            rec.string = fam
        elif rec.nameID in (2, 17):
            rec.string = style
        elif rec.nameID == 4:
            rec.string = fam + ' ' + style
        elif rec.nameID == 6:
            rec.string = 'YRSans-' + style
        elif rec.nameID == 3:
            rec.string = 'YRSans-' + style + ';subset of Noto Sans SC 2.004 + Noto Sans TC 2.004'
    for rec in list(name.names):
        if rec.nameID >= 256 or rec.nameID in (21, 22, 25):
            name.names.remove(rec)
    os2 = merged['OS/2']
    os2.usWeightClass = weight
    if weight == 700:
        os2.fsSelection = (os2.fsSelection | 0x20) & ~0x40
        merged['head'].macStyle |= 1
    out = os.path.join(out_dir, 'YRSans-%s.ttf' % style)
    merged.save(out)
    cmap = set(merged.getBestCmap().keys())
    print(style, 'glyphs', merged['maxp'].numGlyphs, 'chars', len(cmap), 'from SC', len(sc_have), 'from TC', len(tc_have),
          'missing Big5 level-1', len(big5_chars(0xA440, 0xC67E) - cmap), 'bytes', os.path.getsize(out))
