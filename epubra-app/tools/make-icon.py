"""生成 Epubra 应用图标：几何极简，主色蓝底 + 白色「合上的书本」。

用法（项目隔离 venv，Windows）：

    C:/Users/robin/.workbuddy/binaries/python/envs/default/Scripts/python.exe \
        epubra-app/tools/make-icon.py

产物落在 `epubra-app/src/main/resources/org/chobit/epubra/app/icon/`：

    epubra-16.png … epubra-512.png   交给 stage.getIcons()，由窗口系统按需挑尺寸
    epubra.ico                       多尺寸 ICO，供将来 jpackage / exe 打包用

## 设计约定（改动后请同步 AppIconTest 的像素断言）

- 底色只用主题变量 `-epubra-accent`（`#1a5fb4`），不引第二色——图标跟着主题走，不额外发明配色；
- 圆角方底（半径 18.75%），四角留透明：任务栏 / Alt+Tab 里才不会是一块硬邦邦的方块；
- 白色书体 = **封面 + 底部书页两条**（页比封面略窄）：书脊缝与「书的厚度」是「书」在小尺寸下
  最省笔画的两个特征，而且只用蓝白两色就够——不需要引入第三种颜色；
- 4× 超采样（2048）后 LANCZOS 降采样，16px 下边缘不锯齿。
"""

from __future__ import annotations

import pathlib

from PIL import Image, ImageDraw

# 配色：与 app.css / theme.css 的变量一一对应
ACCENT = (26, 95, 180, 255)      # -epubra-accent        #1a5fb4
PAPER = (255, 255, 255, 255)     # -epubra-on-accent-fg  #ffffff

SIZES = (16, 32, 48, 64, 128, 256, 512)
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)

SUPERSAMPLE = 2048                  # 设计基准：512 的 4 倍
CORNER = 0.1875                     # 底圆角半径（占边长比例）
COVER = (0.25, 0.175, 0.75, 0.695)  # 封面：左 / 上 / 右 / 下
COVER_RADIUS = 0.028
PAGES = (0.262, 0.735, 0.738, 0.803)    # 底部书页：比封面略窄，读作「书的厚度」
PAGES_RADIUS = 0.016
SPINE_X, SPINE_W = 0.345, 0.018     # 书脊缝：位置 + 线宽（只画在封面上）

OUT_DIR = (pathlib.Path(__file__).resolve().parents[1]
           / "src/main/resources/org/chobit/epubra/app/icon")


def draw_master() -> Image.Image:
    """按 2048 基准画出母图（超采样，最后统一下采样）。"""
    s = SUPERSAMPLE
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # 底：圆角方
    draw.rounded_rectangle([0, 0, s - 1, s - 1], radius=round(s * CORNER), fill=ACCENT)

    # 封面：白色圆角矩形
    cx0, cy0, cx1, cy1 = (round(s * ratio) for ratio in COVER)
    radius = round(s * COVER_RADIUS)
    draw.rounded_rectangle([cx0, cy0, cx1, cy1], radius=radius, fill=PAPER)

    # 书脊缝：让正面视角下的「合上的书」能分出书脊与封面两块（两端收进圆角内，不切角）
    spine = round(s * SPINE_X)
    draw.rectangle([spine, cy0 + radius, spine + round(s * SPINE_W), cy1 - radius],
                   fill=ACCENT)

    # 底部书页：与封面之间隔一道底色缝，读作「书的厚度」
    px0, py0, px1, py1 = (round(s * ratio) for ratio in PAGES)
    draw.rounded_rectangle([px0, py0, px1, py1], radius=round(s * PAGES_RADIUS), fill=PAPER)

    return img


def main() -> None:
    master = draw_master()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for size in SIZES:
        master.resize((size, size), Image.LANCZOS).save(OUT_DIR / f"epubra-{size}.png")

    master.save(OUT_DIR / "epubra.ico", sizes=[(n, n) for n in ICO_SIZES])

    for path in sorted(OUT_DIR.iterdir()):
        print(f"{path.name:>16}  {path.stat().st_size:>7} B")


if __name__ == "__main__":
    main()
