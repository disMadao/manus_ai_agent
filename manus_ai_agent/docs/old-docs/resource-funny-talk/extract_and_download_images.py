#!/usr/bin/env python3
"""
从小红书 feeds HTML 中提取图片链接，批量下载，并更新 markdown 文档。
用法: 将 HTML 保存到 feeds_html.html，然后运行 python extract_and_download_images.py
"""
import re
import os
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
HTML_FILE = SCRIPT_DIR / "feeds_html.html"
IMAGES_DIR = SCRIPT_DIR / "images"
MD_FILE = SCRIPT_DIR / "test.md"

# HTML data-index 到 文档中 ## 编号的映射 (index 16 -> 18, 17->19, ... 42->43)
def index_to_note_num(html_index: int) -> int:
    return html_index + 2

def extract_images_from_html(html: str) -> list[tuple[int, str, str]]:
    """提取 (data-index, title, img_src) 列表"""
    results = []
    # 按 section 分割，每个 section 含 data-index, img src, title
    parts = re.split(r'<section[^>]*data-index="(\d+)"', html)
    for i in range(1, len(parts), 2):
        idx = int(parts[i])
        section = parts[i + 1]
        img_m = re.search(r'src="(https://sns-webpic[^"]+)"', section)
        title_m = re.search(r'class="title"[^>]*>.*?<span[^>]*>([^<]+)</span>', section, re.DOTALL)
        if not title_m:
            title_m = re.search(r'<span[^>]*>([^<]{2,50})</span>', section)
        if img_m:
            title = title_m.group(1).strip() if title_m else f"note_{idx}"
            results.append((idx, title, img_m.group(1)))
    return sorted(results, key=lambda x: x[0])

def download_image(url: str, filepath: Path) -> bool:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            filepath.parent.mkdir(parents=True, exist_ok=True)
            with open(filepath, "wb") as f:
                f.write(resp.read())
        return True
    except Exception as e:
        print(f"  下载失败 {url[:50]}...: {e}")
        return False

def main():
    if not HTML_FILE.exists():
        print(f"请将 feeds 的 HTML 保存到: {HTML_FILE}")
        return
    html = HTML_FILE.read_text(encoding="utf-8")
    items = extract_images_from_html(html)
    print(f"提取到 {len(items)} 个图片链接")
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    for idx, title, url in items:
        note_num = index_to_note_num(idx)
        ext = ".webp" if "webp" in url else ".jpg"
        filename = f"{note_num:02d}_{re.sub(r'[^\w\u4e00-\u9fff]', '_', title)[:30]}{ext}"
        filepath = IMAGES_DIR / filename
        print(f"  [{note_num}] {title[:20]}... -> {filename}")
        download_image(url, filepath)
    # 更新 markdown
    update_markdown(items)

def update_markdown(items: list[tuple[int, str, str]]):
    """在对应 ## 标题下插入图片引用"""
    if not MD_FILE.exists():
        print("未找到 markdown 文件")
        return
    text = MD_FILE.read_text(encoding="utf-8")
    for idx, title, url in items:
        note_num = index_to_note_num(idx)
        ext = ".webp" if "webp" in url else ".jpg"
        safe_title = re.sub(r'[^\w\u4e00-\u9fff]', '_', title)[:30]
        filename = f"{note_num:02d}_{safe_title}{ext}"
        rel_path = f"images/{filename}"
        # 若已存在该图片引用则跳过
        if f"]({rel_path})" in text:
            print(f"  跳过 ## {note_num}（已有图片）")
            continue
        # 找 ## note_num. 标题，在其后插入图片
        pattern = rf'(## {note_num}\.\s+[^\n]+)\n'
        def replacer(m):
            return m.group(1) + f"\n\n![{title}]({rel_path})\n"
        new_text, n = re.subn(pattern, replacer, text, count=1)
        if n > 0:
            text = new_text
            print(f"  已插入图片到 ## {note_num}")
    MD_FILE.write_text(text, encoding="utf-8")
    print("markdown 已更新")

if __name__ == "__main__":
    main()
