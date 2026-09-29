#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

def annotation_block(text, start):
    open_pos = text.find("(", start)
    if open_pos < 0:
        return None, None
    depth = 0
    for i in range(open_pos, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_pos + 1:i], i + 1
    return None, None

def literal(block, key):
    m = re.search(r"\b" + re.escape(key) + r'\s*=\s*"([^"]*)"', block, re.S)
    return m.group(1) if m else None

def expression(block, key):
    m = re.search(r"\b" + re.escape(key) + r"\s*=\s*([^,\n]+)", block)
    return m.group(1).strip() if m else None

def module_path(rel):
    parts = rel.parts
    try:
        idx = parts.index("src")
        return "/".join(parts[:idx])
    except ValueError:
        return str(rel.parent)

def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: transform-source-catalog.py <hop-root> <output-json>")
    root = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    if not root.is_dir():
        raise SystemExit(f"Hop root not found: {root}")

    rows = []
    for path in root.rglob("*.java"):
        rel = path.relative_to(root)
        rel_text = rel.as_posix()
        if "/src/test/" in f"/{rel_text}" or "/target/" in f"/{rel_text}":
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        cursor = 0
        while True:
            start = text.find("@Transform(", cursor)
            if start < 0:
                break
            block, end = annotation_block(text, start)
            if block is None:
                break
            tail = text[end:end + 3000]
            cls = re.search(r"\b(?:public\s+)?(?:abstract\s+)?class\s+(\w+)", tail)
            pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
            plugin_id = literal(block, "id")
            row = {
                "id": plugin_id,
                "idExpression": None if plugin_id else expression(block, "id"),
                "name": literal(block, "name"),
                "categoryDescription": literal(block, "categoryDescription"),
                "class": (pkg.group(1) + "." if pkg and cls else "") + (cls.group(1) if cls else ""),
                "module": module_path(rel),
                "source": rel_text,
            }
            rows.append(row)
            cursor = end

    rows.sort(key=lambda r: ((r["id"] or "~").lower(), r["source"]))
    ids = [r["id"] for r in rows if r["id"]]
    duplicates = sorted({x for x in ids if ids.count(x) > 1})
    unknown = [r["source"] for r in rows if not r["id"]]

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"SOURCE_TRANSFORM_TOTAL={len(rows)}")
    print(f"SOURCE_TRANSFORM_LITERAL_IDS={len(ids)}")
    print(f"SOURCE_TRANSFORM_UNKNOWN_IDS={len(unknown)}")
    print(f"SOURCE_TRANSFORM_DUPLICATE_IDS={duplicates}")
    if unknown:
        print("SOURCE_TRANSFORM_UNKNOWN_SOURCES=" + json.dumps(unknown))
    print(f"SOURCE_TRANSFORM_CATALOG={out}")

    if not rows:
        raise SystemExit("no @Transform plugins discovered")
    if duplicates:
        raise SystemExit("duplicate literal Transform ids discovered")

if __name__ == "__main__":
    main()
