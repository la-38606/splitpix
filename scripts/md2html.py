#!/usr/bin/env python3
"""Renders docs/design.md as print-ready HTML for the design PDF.

Standard library only, on purpose: the PDF pipeline should run anywhere
Python 3 and Chrome exist, with nothing to install. Handles exactly the
Markdown subset design.md uses (ATX headings, fenced code, pipe tables,
lists, bold/italic/inline code/links, horizontal rules). Anything outside
that subset should fail visibly in the output rather than be guessed at.
"""

import html
import re
import sys


def slug(text):
    text = re.sub(r"[^\w\s§.-]", "", text.lower())
    return re.sub(r"[\s.]+", "-", text).strip("-")


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<!\w)\*([^*\n]+)\*(?!\w)", r"<em>\1</em>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', text)
    return text


def render(lines):
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]

        if line.startswith("```"):
            lang = line[3:].strip()
            block = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                block.append(lines[i])
                i += 1
            i += 1
            caption = ""
            if lang == "mermaid":
                caption = "<p class='fig-caption'>Sequence diagram (mermaid notation)</p>"
            out.append("<pre><code>%s</code></pre>%s" % (html.escape("\n".join(block)), caption))
            continue

        m = re.match(r"^(#{1,4}) (.*)$", line)
        if m:
            level = len(m.group(1))
            text = m.group(2)
            out.append('<h%d id="%s">%s</h%d>' % (level, slug(text), inline(text), level))
            i += 1
            continue

        if re.match(r"^-{3,}$", line.strip()):
            out.append("<hr>")
            i += 1
            continue

        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            body = []
            header = rows[0]
            data = rows[2:] if len(rows) > 1 and set("".join(rows[1])) <= set(":- ") else rows[1:]
            body.append("<tr>%s</tr>" % "".join("<th>%s</th>" % inline(c) for c in header))
            for row in data:
                body.append("<tr>%s</tr>" % "".join("<td>%s</td>" % inline(c) for c in row))
            out.append("<table>%s</table>" % "".join(body))
            continue

        m = re.match(r"^(\d+)\. (.*)$", line)
        if m or line.startswith("- "):
            ordered = bool(m)
            items = []
            pattern = r"^\d+\. (.*)$" if ordered else r"^- (.*)$"
            while i < len(lines):
                m2 = re.match(pattern, lines[i])
                if m2:
                    items.append(m2.group(1))
                elif lines[i].startswith("  ") and items:
                    items[-1] += " " + lines[i].strip()
                else:
                    break
                i += 1
            tag = "ol" if ordered else "ul"
            out.append("<%s>%s</%s>" % (tag, "".join("<li>%s</li>" % inline(x) for x in items), tag))
            continue

        if not line.strip():
            i += 1
            continue

        paragraph = []
        while i < len(lines) and lines[i].strip() and not re.match(r"^(#|-{3,}$|\||```|- |\d+\. )", lines[i]):
            paragraph.append(lines[i].strip())
            i += 1
        out.append("<p>%s</p>" % inline(" ".join(paragraph)))

    return "\n".join(out)


STYLE = """
@page { size: A4; margin: 22mm 20mm; }
body { font-family: "Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif;
       font-size: 10.5pt; line-height: 1.5; color: #1c1a17; margin: 0; }
code, pre { font-family: "SF Mono", Menlo, Consolas, monospace; font-size: 8.8pt; }
pre { background: #f5f2ec; border: 1px solid #ddd6c8; padding: 8px 10px;
      white-space: pre-wrap; page-break-inside: avoid; }
h1 { font-size: 21pt; font-weight: normal; letter-spacing: -0.01em; }
h2 { font-size: 14pt; font-weight: 600; margin-top: 1.6em;
     border-bottom: 1px solid #ddd6c8; padding-bottom: 4px; page-break-before: always; }
h2:first-of-type { page-break-before: avoid; }
h3 { font-size: 11.5pt; margin-top: 1.4em; }
h4 { font-size: 10.5pt; }
table { border-collapse: collapse; width: 100%; margin: 0.8em 0; page-break-inside: avoid; }
th, td { border: 1px solid #ccc4b4; padding: 4px 8px; text-align: left;
         vertical-align: top; font-size: 9.5pt; }
th { background: #f5f2ec; }
a { color: #0e5b50; text-decoration: none; }
hr { border: 0; border-top: 1px solid #ddd6c8; margin: 1.6em 0; }
.fig-caption { font-size: 9pt; color: #6a6055; margin-top: -0.4em; }
.cover { page-break-after: always; padding-top: 90mm; }
.cover h1 { font-size: 28pt; margin-bottom: 4mm; }
.cover p { color: #4a443c; }
"""


def main():
    source = open(sys.argv[1], encoding="utf-8").read().splitlines()

    # The document's own first heading and status lines become the cover.
    title = next(l[2:] for l in source if l.startswith("# "))
    meta_lines = [l for l in source[:8] if l.startswith("**")]
    body_start = source.index("---") + 1 if "---" in source[:12] else 0
    body = render(source[body_start:])

    cover = '<div class="cover"><h1>%s</h1>%s</div>' % (
        inline(title), "".join("<p>%s</p>" % inline(l) for l in meta_lines))

    print("<!DOCTYPE html><html lang='en'><head><meta charset='utf-8'>"
          "<title>%s</title><style>%s</style></head><body>%s%s</body></html>"
          % (html.escape(title), STYLE, cover, body))


if __name__ == "__main__":
    main()
