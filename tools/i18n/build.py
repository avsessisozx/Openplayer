"""strings.xml dosyalarını tools/i18n/*.py sözlüklerinden üretir.

Kullanım: python3 tools/i18n/build.py app/src/main/res
- en.py varsayılan (values), tr.py Türkçe (values-tr); diğer diller aşağıdaki LANGS'te.
- Dili eksik anahtarlı olabilir (Android eksik anahtarda İngilizceye düşer); eksikler uyarı olarak yazılır.
- STRICT=1 ile eksik anahtar hata sayılır (yayın öncesi).
"""
import sys, re, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import en

LANGS = {"tr": "values-tr", "es": "values-es", "fr": "values-fr", "it": "values-it", "de": "values-de",
         "pt": "values-pt", "zh": "values-zh-rCN", "ja": "values-ja", "ko": "values-ko", "ru": "values-ru",
         "ar": "values-ar"}
res = sys.argv[1]
STRICT = os.environ.get("STRICT") == "1"
PH = re.compile(r"%(\d+)\$([-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z])")


def esc(v):
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = v.replace("\\n", "\n").replace("\n", "\\n").replace("\t", "\\t")
    v = v.replace("'", "\\'").replace('"', '\\"')
    if v.startswith("@") or v.startswith("?"):
        v = "\\" + v
    return v


def write(folder, d):
    os.makedirs(os.path.join(res, folder), exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Bu dosya tools/i18n/build.py ile üretilir; elle değiştirmeyin. -->',
             '<resources>']
    for k in en.S:
        if k not in d:
            continue
        v = d[k]
        attr = ""
        if not PH.search(v) and "%" in v:
            attr = ' formatted="false"'
        lines.append(f'    <string name="{k}"{attr}>{esc(v)}</string>')
    lines.append('</resources>')
    with open(os.path.join(res, folder, "strings.xml"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


errors, warns = [], []
write("values", en.S)
done = 1
for code, folder in LANGS.items():
    try:
        m = __import__(code)
    except ImportError:
        warns.append(f"{code}: dosya yok, atlandı")
        continue
    missing = [k for k in en.S if k not in m.S]
    if missing:
        (errors if STRICT else warns).append(f"{code}: {len(missing)} anahtar eksik")
    for k, v in m.S.items():
        if k not in en.S:
            errors.append(f"{code}.{k}: İngilizcede yok")
        elif sorted(PH.findall(v)) != sorted(PH.findall(en.S[k])):
            errors.append(f"{code}.{k}: yer tutucular {PH.findall(v)} != {PH.findall(en.S[k])}")
    write(folder, m.S)
    done += 1
print("\n".join(warns))
if errors:
    print("\n".join(errors))
    sys.exit(1)
print("tamam:", len(en.S), "anahtar,", done, "dil")
