"""Kullanım: python3 -I merge.py es  -> es_*.py parçalarını es.py'de birleştirir ve en.py ile doğrular."""
import sys, glob, re, importlib.util, os
d = os.path.dirname(os.path.abspath(__file__))
def load(p):
    s = importlib.util.spec_from_file_location("x", p); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m.S
lang = sys.argv[1]
en = load(d + "/en.py")
tr = load(d + "/tr.py")
parts = {}
for f in sorted(glob.glob("%s/%s_[0-9]*.py" % (d, lang))):
    parts.update(load(f))
ph = lambda s: sorted(re.findall(r"%(?:\d+\$)?[sd%]", s.replace("%%", "")))
bad = []
for k in en:
    if k not in parts: bad.append("eksik " + k); continue
    if ph(en[k]) != ph(parts[k]): bad.append("yer tutucu " + k)
    tail = ("\n",) if lang in ("zh", "ja", "ko") else ("\n", " ")
    if en[k].endswith(tail) != parts[k].endswith(tail): bad.append("bosluk " + k)
for k in parts:
    if k not in en: bad.append("fazla " + k)
if bad:
    print("HATA", len(bad)); print("\n".join(bad[:40])); sys.exit(1)
with open("%s/%s.py" % (d, lang), "w", encoding="utf8") as f:
    f.write("S = {\n")
    for k in en: f.write("%r: %r,\n" % (k, parts[k]))
    f.write("}\n")
print(lang, "tamam", len(parts))
