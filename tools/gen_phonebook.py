#!/usr/bin/env python3
"""把 metowolf/vCards 的黄页数据转换成 App 用的 assets/phonebook.tsv。

用法：
    python tools/gen_phonebook.py --src <vcards 仓库的 data 目录>
    python tools/gen_phonebook.py --src <...> --out app/src/main/assets/phonebook.tsv

输出格式（UTF-8，制表符分隔，按号码升序）：
    <归一化号码>\t<机构名>\t<分类>

为什么不用数据库：数据量只有几百条，一个 TSV 几十 KB，读进 HashMap 不到 1ms。
App 端见 data/PhoneBook.kt。

注意：vCards 仓库没有 LICENSE，本脚本只做格式转换，生成的数据文件默认不提交到 git。
"""

import argparse
import os
import re
import sys
from collections import Counter

DEFAULT_SRC = os.environ.get("VCARDS_DATA", "")
DEFAULT_OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "phonebook.tsv",
)


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC, help="vcards 的 data 目录")
    ap.add_argument("--out", default=DEFAULT_OUT, help="输出 tsv 路径")
    args = ap.parse_args()

    if not args.src or not os.path.isdir(args.src):
        sys.exit(f"找不到数据目录: {args.src!r}（用 --src 指定 metowolf/vCards 的 data/）")

    rows = {}
    conflicts = []
    org_count = 0
    skipped = Counter()

    for category in sorted(os.listdir(args.src)):
        cat_dir = os.path.join(args.src, category)
        if not os.path.isdir(cat_dir):
            continue
        for fn in sorted(os.listdir(cat_dir)):
            if not fn.endswith(".yaml"):
                continue
            name, phones = parse_yaml(os.path.join(cat_dir, fn))
            if not name:
                skipped["无机构名"] += 1
                continue
            org_count += 1
            for raw in phones:
                number = normalize(raw)
                if not number:
                    skipped["国际号/106/空"] += 1
                    continue
                for v in variants(number):
                    if v in rows and rows[v][0] != name:
                        conflicts.append((v, rows[v][0], name))
                        continue
                    rows[v] = (name, category)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for number in sorted(rows):
            name, category = rows[number]
            f.write(f"{number}\t{name}\t{category}\n")

    size = os.path.getsize(args.out)
    print(f"机构 {org_count} 家 → 索引 {len(rows)} 条（{size} 字节）")
    if conflicts:
        print(f"号码冲突 {len(conflicts)} 条（保留先出现的机构）:")
        for v, kept, dropped in conflicts[:10]:
            print(f"    {v}: 保留 {kept}，忽略 {dropped}")
    if skipped:
        print(f"跳过: {dict(skipped)}")
    print(f"已写出 {args.out}")


if __name__ == "__main__":
    main()
