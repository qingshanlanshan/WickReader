"""Generate small EPUB3 test books (original placeholder text) that exercise the bookshelf:
series from EPUB3 metadata, calibre metadata, title patterns, 繁體 text, no cover, long text.

    python3 tools/make_test_books.py out_dir
Covers need rsvg-convert (brew install librsvg); without it books are made without covers.
"""
import os, shutil, subprocess, sys, tempfile, uuid, zipfile
from xml.sax.saxutils import escape

CN = [
    "清晨的雾还没有散开，山路像一条灰白的丝带，从脚下一直伸进林子深处。",
    "他背着旧布包，走得不快，却一步也没有停，仿佛前面有人在等他。",
    "溪水在石头之间绕来绕去，声音很轻，像是有人在低声讲一个很长的故事。",
    "到了中午，太阳终于从云后面出来，把整座山照得明亮而安静。",
    "她说，路走得远了，就会忘记为什么出发；可是只要记得回来的方向，就不算迷路。",
    "夜里风大，屋檐下的灯笼晃个不停，影子在墙上一会儿长，一会儿短。",
    "第二天，他们在渡口等船。江面很宽，对岸的房子小得像一排积木。",
]
TW = [
    "雲從山後慢慢升起來，把整個山谷蓋得嚴嚴實實，連鳥叫聲都變得遙遠。",
    "他翻開那本舊書，紙頁已經發黃，邊角捲起，像是經過了許多人的手。",
    "書裡記載著一座城，城裡的人白天讀書，晚上點燈寫字，從不關門。",
    "後來戰亂來了，城門第一次關上，燈也一盞一盞地熄滅了。",
    "多年以後，有個孩子在廢墟裡撿到一枝筆，筆桿上刻著兩個字：「雲中」。",
    "於是他開始寫，寫那座城，寫那些燈，也寫那些再也回不來的人。",
]
EN = [
    "The lighthouse stood at the end of a long stone pier, white against the grey water.",
    "Every evening the keeper climbed the spiral stairs, counting them out of habit.",
    "Ships passed far out at sea, their lights no bigger than stars that had fallen low.",
    "In winter the wind came straight from the north and found every gap in the windows.",
    "He kept a logbook of weather, tides, and the occasional visitor who rowed out from town.",
]


def paragraphs(pool, n, seed):
    return [pool[(seed * 3 + i * 5) % len(pool)] + pool[(seed + i) % len(pool)] for i in range(n)]


def cover_png(path, title, subtitle, colors):
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="600" height="900">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{colors[0]}"/><stop offset="1" stop-color="{colors[1]}"/></linearGradient></defs>
<rect width="600" height="900" fill="url(#g)"/>
<rect x="40" y="40" width="520" height="820" fill="none" stroke="#ffffff" stroke-opacity="0.5" stroke-width="3"/>
<text x="300" y="400" font-size="64" font-family="PingFang SC, Heiti SC, sans-serif" fill="#fff" text-anchor="middle" font-weight="bold">{escape(title)}</text>
<text x="300" y="480" font-size="34" font-family="PingFang SC, Heiti SC, sans-serif" fill="#fff" fill-opacity="0.85" text-anchor="middle">{escape(subtitle)}</text>
</svg>"""
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg)
    try:
        subprocess.run(["rsvg-convert", f.name, "-o", path], check=True)
        return True
    except Exception:
        return False
    finally:
        os.unlink(f.name)


def make_epub(out, *, title, author, lang, chapters, extra_meta="", cover=None):
    tmp = tempfile.mkdtemp()
    try:
        os.makedirs(f"{tmp}/META-INF")
        os.makedirs(f"{tmp}/OEBPS")
        open(f"{tmp}/mimetype", "w").write("application/epub+zip")
        open(f"{tmp}/META-INF/container.xml", "w").write(
            '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        manifest, spine, nav = [], [], []
        for i, (ctitle, paras) in enumerate(chapters, 1):
            body = "".join(f"<p>{escape(p)}</p>" for p in paras)
            open(f"{tmp}/OEBPS/c{i}.xhtml", "w").write(
                f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="{lang}">'
                f'<head><title>{escape(ctitle)}</title></head><body><h2>{escape(ctitle)}</h2>{body}</body></html>')
            manifest.append(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>')
            spine.append(f'<itemref idref="c{i}"/>')
            nav.append(f'<li><a href="c{i}.xhtml">{escape(ctitle)}</a></li>')
        open(f"{tmp}/OEBPS/nav.xhtml", "w").write(
            '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
            f'<head><title>目录</title></head><body><nav epub:type="toc"><ol>{"".join(nav)}</ol></nav></body></html>')
        cover_meta = ""
        if cover and cover_png(f"{tmp}/OEBPS/cover.png", *cover):
            manifest.append('<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>')
            cover_meta = '<meta name="cover" content="cover"/>'
        open(f"{tmp}/OEBPS/content.opf", "w").write(f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:uuid:{uuid.uuid4()}</dc:identifier>
<dc:title>{escape(title)}</dc:title><dc:creator>{escape(author)}</dc:creator><dc:language>{lang}</dc:language>
<meta property="dcterms:modified">2026-09-24T00:00:00Z</meta>{cover_meta}{extra_meta}
</metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>{"".join(manifest)}</manifest>
<spine>{"".join(spine)}</spine></package>""")
        with zipfile.ZipFile(out, "w") as z:
            z.write(f"{tmp}/mimetype", "mimetype", compress_type=zipfile.ZIP_STORED)
            for root, _, files in os.walk(tmp):
                for name in files:
                    full = os.path.join(root, name)
                    rel = os.path.relpath(full, tmp)
                    if rel != "mimetype":
                        z.write(full, rel, compress_type=zipfile.ZIP_DEFLATED)
    finally:
        shutil.rmtree(tmp)


def epub3_series(name, pos):
    return (f'<meta property="belongs-to-collection" id="series">{escape(name)}</meta>'
            f'<meta refines="#series" property="collection-type">series</meta>'
            f'<meta refines="#series" property="group-position">{pos}</meta>')


def main(out):
    os.makedirs(out, exist_ok=True)
    greens = ("#2E5E4E", "#6A9C89")
    for i, sub in enumerate(["启程", "过江", "归来"], 1):
        make_epub(f"{out}/shanhai-{i}.epub", title=f"山海行记·{sub}", author="林远", lang="zh-CN",
                  chapters=[(f"第{c}章", paragraphs(CN, 18, i * 10 + c)) for c in range(1, 6)],
                  extra_meta=epub3_series("山海行记", i), cover=(f"山海行记", sub, greens))
    for i, num in enumerate(["一", "二"], 1):
        make_epub(f"{out}/雲中書 第{num}卷.epub", title=f"雲中書 第{num}卷", author="沈書雲", lang="zh-TW",
                  chapters=[(f"第{c}回", paragraphs(TW, 16, i * 7 + c)) for c in range(1, 5)],
                  cover=("雲中書", f"第{num}卷", ("#5B4B8A", "#9A8FC7")))
    for i in (1, 2):
        make_epub(f"{out}/lighthouse-{i}.epub", title=f"The Lighthouse Keeper, Vol. {i}", author="A. Marsh", lang="en",
                  chapters=[(f"Chapter {c}", paragraphs(EN, 14, i * 5 + c)) for c in range(1, 5)],
                  cover=("Lighthouse", f"Vol. {i}", ("#3E5C76", "#748CAB")))
    make_epub(f"{out}/moon-poems.epub", title="月下集", author="陈白", lang="zh-CN",
              chapters=[("序", paragraphs(CN, 6, 3)), ("卷上", paragraphs(CN, 20, 4))],
              extra_meta='<meta name="calibre:series" content="夜读小集"/><meta name="calibre:series_index" content="2"/>',
              cover=("月下集", "夜读小集 2", ("#7B4B3A", "#C08A6B")))
    make_epub(f"{out}/long-standalone.epub", title="一本没有封面的长书", author="佚名", lang="zh-CN",
              chapters=[(f"第{c}章 长路", paragraphs(CN, 40, c)) for c in range(1, 13)])
    print("\n".join(sorted(os.listdir(out))))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "test-books")
