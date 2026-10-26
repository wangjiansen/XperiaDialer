#!/usr/bin/env python3
"""把 metowolf/vCards 的黄页数据转换成 App 用的 assets。

产出两个东西：
  1. `assets/phonebook.tsv`   号码索引，UTF-8 制表符分隔，按号码升序
  2. `assets/logos/<hash>.webp`  机构 logo，160×160 白底圆形

TSV 格式（4 列）：
    <归一化号码>\t<机构名>\t<分类>\t<logo 文件名>

用法：
    python tools/gen_phonebook.py --src <vcards 仓库的 data 目录>
    python tools/gen_phonebook.py --src <...> --no-logos     # 只生成索引

为什么不用数据库：数据量只有几百条，一个 TSV 几十 KB，读进 HashMap 不到 1ms。
App 端见 data/PhoneBook.kt。

logo 为什么是白底圆形：来电/通话记录里的头像是圆形裁切的，而图标素材本身
有圆形、正方形、长方形三种画布规格，直接裁切会切掉长方形的两端。
统一铺一层白色圆底再居中缩放，任何规格都不会被切。
WebP 而不是 PNG：同样画质体积约为 PNG 的 1/3，317 个图标总共 1 MB 出头。

注意：vCards 仓库没有 LICENSE，本脚本只做格式转换，生成的数据文件默认不提交到 git。
"""

import argparse
import hashlib
import os
import re
import sys
from collections import Counter

try:
    from PIL import Image, ImageDraw
except ImportError:
    Image = None

DEFAULT_SRC = os.environ.get("VCARDS_DATA", "")
ASSETS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets",
)
DEFAULT_OUT = os.path.join(ASSETS, "phonebook.tsv")
DEFAULT_LOGO_DIR = os.path.join(ASSETS, "logos")

LOGO_SIZE = 160
LOGO_INNER = 104


def parse_yaml(path):
    """极简解析：只取 organization 和 cellPhone 列表，不引第三方 yaml 库。"""
    name = None
    phones = []
    in_phone = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.rstrip("\n")
            if s.startswith("  organization:"):
                name = s.split(":", 1)[1].strip().strip("\"'")
                in_phone = False
            elif s.startswith("  cellPhone:"):
                in_phone = True
            elif s.startswith("    - ") and in_phone:
                phones.append(s[6:].strip().strip("\"'"))
            elif s.startswith("  ") and not s.startswith("    "):
                in_phone = False
    return name, phones


def normalize(raw):
    """归一化成来电时系统给的数字串；返回 None 表示这类号码不要。

    - 国际号：来电识别用不上，直接丢
    - 106 短信推送号段：README 明确不收录，且不会来电
    - +86 / 0086 前缀：去掉
    """
    s = raw.strip()
    if s.startswith("+"):
        return None
    d = re.sub(r"\D", "", s)
    if not d:
        return None
    if d.startswith("0086"):
        d = d[4:]
    elif d.startswith("86") and len(d) >= 13:
        d = d[2:]
    if d.startswith("106"):
        return None
    return d


def variants(number):
    """同一个号码的多种来电显示形态。

    带区号的固话 `01053153053`，有的机型/运营商只给本地号 `53153053`，
    所以额外补一份去掉前导 0 的形态。手机号以 1 开头、400 以 4 开头，不会误伤。
    """
    out = {number}
    if number.startswith("0") and len(number) >= 9:
        out.add(number[1:])
    return out


def logo_name(org):
    return hashlib.md5(org.encode("utf-8")).hexdigest()[:10] + ".webp"


def build_logo(src_png, dst_path):
    """白底圆形 + logo 居中，输出 160×160 WebP。"""
    img = Image.open(src_png).convert("RGBA")

    canvas = Image.new("RGBA", (LOGO_SIZE, LOGO_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    draw.ellipse((0, 0, LOGO_SIZE - 1, LOGO_SIZE - 1), fill=(255, 255, 255, 255))

    w, h = img.size
    scale = min(LOGO_INNER / w, LOGO_INNER / h)
    new_size = (max(1, round(w * scale)), max(1, round(h * scale)))
    img = img.resize(new_size, Image.LANCZOS)

    left = (LOGO_SIZE - new_size[0]) // 2
    top = (LOGO_SIZE - new_size[1]) // 2
    canvas.alpha_composite(img, (left, top))

    canvas.save(dst_path, "WEBP", quality=90, method=6)
    return os.path.getsize(dst_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC, help="vcards 的 data 目录")
    ap.add_argument("--out", default=DEFAULT_OUT, help="输出 tsv 路径")
    ap.add_argument("--logo-dir", default=DEFAULT_LOGO_DIR, help="输出 logo 目录")
    ap.add_argument("--no-logos", action="store_true", help="只生成索引，不处理图标")
    args = ap.parse_args()

    if not args.src or not os.path.isdir(args.src):
        sys.exit(f"找不到数据目录: {args.src!r}（用 --src 指定 metowolf/vCards 的 data/）")
    if not args.no_logos and Image is None:
        sys.exit("需要 Pillow 处理图标：pip install Pillow（或用 --no-logos 跳过）")

    rows = {}
    conflicts = []
    org_count = 0
    skipped = Counter()
    logos = {}          # org -> tsv 里的文件名（"" 表示没有图标）
    logo_total = 0
    logo_missing = 0

    if not args.no_logos:
        os.makedirs(args.logo_dir, exist_ok=True)
        # 清掉上一轮的产物，避免删掉的机构留下孤儿文件
        for old in os.listdir(args.logo_dir):
            if old.endswith(".webp"):
                os.remove(os.path.join(args.logo_dir, old))

    for category in sorted(os.listdir(args.src)):
        cat_dir = os.path.join(args.src, category)
        if not os.path.isdir(cat_dir):
            continue
        for fn in sorted(os.listdir(cat_dir)):
            if not fn.endswith(".yaml"):
                continue
            yaml_path = os.path.join(cat_dir, fn)
            name, phones = parse_yaml(yaml_path)
            if not name:
                skipped["无机构名"] += 1
                continue
            org_count += 1

            # 图标文件名与 yaml 同名，同目录
            if name in logos:
                pass
            elif args.no_logos:
                logos[name] = ""
            else:
                png_path = yaml_path[:-5] + ".png"
                if os.path.isfile(png_path):
                    filename = logo_name(name)
                    try:
                        logo_total += build_logo(png_path, os.path.join(args.logo_dir, filename))
                        logos[name] = filename
                    except Exception as e:      # noqa: BLE001
                        print(f"  [warn] 图标处理失败 {png_path}: {e}")
                        logos[name] = ""
                        logo_missing += 1
                else:
                    logos[name] = ""
                    logo_missing += 1

            for raw in phones:
                number = normalize(raw)
                if not number:
                    skipped["国际号/106/空"] += 1
                    continue
                for v in variants(number):
                    if v in rows and rows[v][0] != name:
                        conflicts.append((v, rows[v][0], name))
                        continue
                    rows[v] = (name, category, logos[name])

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for number in sorted(rows):
            name, category, logo = rows[number]
            f.write(f"{number}\t{name}\t{category}\t{logo}\n")

    size = os.path.getsize(args.out)
    print(f"机构 {org_count} 家 → 索引 {len(rows)} 条（{size} 字节）")
    if not args.no_logos:
        with_logo = sum(1 for v in logos.values() if v)
        print(f"图标 {with_logo} 个（{logo_total / 1024:.0f} KB，平均 {logo_total / max(with_logo, 1) / 1024:.1f} KB）")
        if logo_missing:
            print(f"缺图标 {logo_missing} 家")
    if conflicts:
        print(f"号码冲突 {len(conflicts)} 条（保留先出现的机构）:")
        for v, kept, dropped in conflicts[:10]:
            print(f"    {v}: 保留 {kept}，忽略 {dropped}")
    if skipped:
        print(f"跳过: {dict(skipped)}")
    print(f"已写出 {args.out}")


if __name__ == "__main__":
    main()
