# -*- coding: utf-8 -*-
"""把桌面图标的矢量图导出成 PNG（5 个密度），修「QQ 浏览器解析安装包没有图标」。

为什么需要：包里如果只有 res/drawable/ic_launcher.xml（矢量图），安卓桌面画得出来，
但安装包预览/解析类工具（QQ 浏览器、各种包管理器）只认位图 PNG，于是一片空白。

做法：几何和色值**直接从** drawable/*.xml 里读，不重新设计——
改图标只需要改那份矢量 XML，然后重跑本脚本。

用法：
    .\\tools\\py.cmd scripts\\icon_raster.py          # 写进 app/src/main/res/mipmap-*/
    .\\tools\\py.cmd scripts\\icon_raster.py --preview # 只出预览图到 downloads/icon-preview
"""
import os
import re
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")
PREVIEW = os.path.join(ROOT, "downloads", "icon-preview")

# 几何照抄 ic_launcher.xml（108 视口，圆角矩形）
# (x0, y0, x1, y1, radius, corners[tl,tr,br,bl])
SHAPES = [
    (0.0, 0.0, 108.0, 108.0, 14.0, (1, 1, 1, 1)),   # 底
    (26.0, 26.0, 81.0, 82.0, 4.5, (1, 1, 1, 1)),    # 白纸
    (26.0, 26.0, 81.0, 38.0, 4.5, (1, 1, 0, 0)),    # 页眉（下两角方）
    (37.0, 52.0, 51.0, 66.0, 2.4, (1, 1, 1, 1)),    # 橙块
    (55.5, 51.0, 72.5, 57.5, 3.2, (1, 1, 1, 1)),    # 线 1
    (55.5, 60.5, 72.5, 67.0, 3.2, (1, 1, 1, 1)),    # 线 2
]

DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]

VARIANTS = [
    "ic_launcher",
    "ic_launcher_teal",
    "ic_launcher_indigo",
    "ic_launcher_sand",
    "ic_launcher_ink",
    "ic_launcher_pink",
    "ic_launcher_sakura",
]

SUPERSAMPLE = 8


def colors_of(xml_path):
    txt = open(xml_path, encoding="utf-8").read()
    return re.findall(r'android:fillColor="(#[0-9A-Fa-f]{6,8})"', txt)


def render(xml_path, size):
    cols = colors_of(xml_path)
    if len(cols) < len(SHAPES):
        raise SystemExit("%s 里的 fillColor 少于 %d 个，几何对不上" % (xml_path, len(SHAPES)))
    img = Image.new("RGBA", (size * SUPERSAMPLE, size * SUPERSAMPLE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = size * SUPERSAMPLE / 108.0
    for (x0, y0, x1, y1, r, corners), color in zip(SHAPES, cols):
        d.rounded_rectangle([x0 * k, y0 * k, x1 * k, y1 * k], radius=r * k,
                            fill=color, corners=corners)
    return img.resize((size, size), Image.LANCZOS)


def main():
    preview_only = "--preview" in sys.argv
    for name in VARIANTS:
        src = os.path.join(RES, "drawable", name + ".xml")
        if not os.path.exists(src):
            print("跳过（没有矢量图）：", name)
            continue
        if preview_only:
            os.makedirs(PREVIEW, exist_ok=True)
            render(src, 48).save(os.path.join(PREVIEW, name + "-48.png"))
            render(src, 192).save(os.path.join(PREVIEW, name + "-192.png"))
            print("预览：", name)
            continue
        for density, size in DENSITIES:
            out_dir = os.path.join(RES, "mipmap-" + density)
            os.makedirs(out_dir, exist_ok=True)
            render(src, size).save(os.path.join(out_dir, name + ".png"), optimize=True)
        print("写好 PNG：", name, [s for _, s in DENSITIES])
    if not preview_only:
        print("完成。记得 manifest 里指向 @mipmap/<名字>，并重跑构建。")


if __name__ == "__main__":
    main()
