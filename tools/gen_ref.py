#!/usr/bin/env python3
"""Генерирует RefCatalogData.java и RefForcedData.java из файла экспорта флагов референс-мода.

Запуск:  python3 tools/gen_ref.py экспорт.json app/src/main/java/dev/ttmlonger

RefCatalogData — все флаги из экспорта (ключ, тип, значение по умолчанию), сжатые gzip и
разрезанные на куски base64 (в class-файле строка не длиннее 65535 байт).
RefForcedData  — флаги, которые референс-мод принудительно включает (forced_by_mod):
скалярные идут в набор по умолчанию, json-типы только перечислены (подмена json не поддерживается).
"""
import base64
import gzip
import io
import json
import sys

CHUNK = 60000
TYPES = {"boolean": "b", "int": "i", "long": "l", "float": "f", "double": "d", "string": "s", "json": "j"}
NAMES = {"boolean": "bool", "int": "int", "long": "long", "float": "float", "double": "double", "string": "string"}


def esc(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


def jstr(s):
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif o < 32 or o > 126:
            out.append("\\u%04x" % o)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def norm_forced(t, v):
    """Значение forced_by_mod приводится к виду, который понимает FlagStore.coerce."""
    s = v.strip()
    low = s.lower()
    if t == "boolean":
        if low in ("true", "1"):
            return "true"
        if low in ("false", "0"):
            return "false"
        return None
    if t in ("int", "long"):
        if low == "true":
            return "1"
        if low == "false":
            return "0"
        try:
            int(s)
        except ValueError:
            return None
        return s
    if t in ("float", "double"):
        try:
            float(s)
        except ValueError:
            return None
        return s
    if t == "string":
        return v
    return None


def main():
    src, out = sys.argv[1], sys.argv[2]
    d = json.load(open(src, encoding="utf-8"))
    flags = d["flags"]
    version = d.get("tiktok", "?")

    lines = []
    for k in sorted(flags):
        v = flags[k]
        t = TYPES.get(v.get("type"))
        if t is None or "\t" in k or "\n" in k or not k.strip():
            continue
        line = k + "\t" + t
        if "default" in v and v["default"] is not None:
            line += "\t" + esc(str(v["default"]))
        lines.append(line)
    raw = "\n".join(lines).encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0, compresslevel=9) as g:
        g.write(raw)
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    chunks = [b64[i:i + CHUNK] for i in range(0, len(b64), CHUNK)]

    with open(out + "/RefCatalogData.java", "w", encoding="utf-8") as f:
        f.write("package dev.ttmlonger;\n\n")
        f.write("/** Сгенерировано tools/gen_ref.py из экспорта флагов TikTok %s. Не править вручную. */\n" % version)
        f.write("final class RefCatalogData {\n")
        f.write("    static final String TIKTOK = %s;\n" % jstr(version))
        f.write("    static final int COUNT = %d;\n" % len(lines))
        f.write("    static final String[] CHUNKS = {\n")
        for c in chunks:
            f.write("            %s,\n" % jstr(c))
        f.write("    };\n\n    private RefCatalogData() {\n    }\n}\n")

    scalar, js = [], []
    for k in sorted(flags):
        v = flags[k]
        fb = v.get("forced_by_mod")
        if fb is None:
            continue
        t = v.get("type")
        if t == "json":
            js.append(k)
            continue
        nv = norm_forced(t, fb)
        if nv is None:
            js.append(k)
            continue
        scalar.append((k, NAMES[t], nv))

    with open(out + "/RefForcedData.java", "w", encoding="utf-8") as f:
        f.write("package dev.ttmlonger;\n\n")
        f.write("/** Сгенерировано tools/gen_ref.py из экспорта флагов TikTok %s. Не править вручную. */\n" % version)
        f.write("final class RefForcedData {\n")
        f.write("    /** Скалярные флаги, которые референс-мод включает: {ключ, тип, значение}. */\n")
        f.write("    static final String[][] FORCED = {\n")
        for k, t, v in scalar:
            f.write("            {%s, %s, %s},\n" % (jstr(k), jstr(t), jstr(v)))
        f.write("    };\n\n")
        f.write("    /** Флаги json-типа из того же набора: подмена json пока не поддерживается. */\n")
        f.write("    static final String[] JSON_FORCED = {\n")
        for k in js:
            f.write("            %s,\n" % jstr(k))
        f.write("    };\n\n    private RefForcedData() {\n    }\n}\n")
    print("flags", len(lines), "chunks", len(chunks), "b64", len(b64), "scalar", len(scalar), "json", len(js))


main()
