#!/usr/bin/env python3
"""OpenPlayer emülatör duman testi.

Uygulamayı kurar, örnek medyayı yükler, ana ekranları açar, her ekrandaki tıklanabilir
öğelere tek tek dokunur, ekran görüntüsü alır ve çökmeleri (FATAL EXCEPTION / ANR) kaydeder.
Sonuçlar out/ klasörüne yazılır: screens/*.png, ui/*.xml, steps.tsv, crashes.txt, logcat.txt, report.md
"""
import os, re, struct, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "org.openplayer.app"
APK = sys.argv[1]
MEDIA = sys.argv[2]
OUT = sys.argv[3] if len(sys.argv) > 3 else "out"
os.makedirs(f"{OUT}/screens", exist_ok=True)
os.makedirs(f"{OUT}/ui", exist_ok=True)
steps = []      # (adım, sonuç, not)
crashes = []    # (adım, metin)
seen_crash_len = 0
counter = 0


def adb(*args, check=False, timeout=60, binary=False):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} -> {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def slug(s):
    s = re.sub(r"[^0-9A-Za-zçğıöşüÇĞİÖŞÜ]+", "_", s).strip("_")
    return s[:40] or "x"


def shot(name):
    global counter
    counter += 1
    fn = f"{OUT}/screens/{counter:03d}_{slug(name)}.png"
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=30)
    if data:
        open(fn, "wb").write(data)
    return fn


def dump():
    """Ekrandaki UI ağacını döndürür (ET kökü) veya None."""
    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1", timeout=30)
        raw = adb("exec-out", "cat", "/sdcard/ui.xml", binary=True, timeout=20)
        try:
            return ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1)
    return None


def bounds_center(b):
    m = re.findall(r"\d+", b or "")
    if len(m) != 4:
        return None
    x1, y1, x2, y2 = map(int, m)
    if x2 - x1 < 4 or y2 - y1 < 4:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def label(n):
    t = (n.get("text") or "").strip()
    d = (n.get("content-desc") or "").strip()
    rid = (n.get("resource-id") or "").split("/")[-1]
    if t or d:
        return (t or d)[:50]
    # Metni olmayan düğmede alt öğelerin metnini kullan.
    for c in n.iter():
        ct = (c.get("text") or c.get("content-desc") or "").strip()
        if ct:
            return ct[:50]
    return rid or n.get("class", "").split(".")[-1]


def clickables(root):
    out = []
    if root is None:
        return out
    for n in root.iter("node"):
        if n.get("package") != PKG:
            continue
        if n.get("clickable") == "true" or n.get("long-clickable") == "true":
            c = bounds_center(n.get("bounds"))
            if c:
                out.append((label(n), c, n.get("long-clickable") == "true" and n.get("clickable") != "true"))
    # Aynı konumdaki tekrarları ele
    uniq, keys = [], set()
    for l, c, lo in out:
        k = (c[0] // 10, c[1] // 10)
        if k not in keys:
            keys.add(k)
            uniq.append((l, c, lo))
    return uniq


def texts(root):
    if root is None:
        return []
    r = []
    for n in root.iter("node"):
        for k in ("text", "content-desc"):
            v = (n.get(k) or "").strip()
            if v:
                r.append(v)
    return r


def find_text(root, *wanted):
    if root is None:
        return None
    for w in wanted:
        for n in root.iter("node"):
            if n.get("package") != PKG:
                continue
            if (n.get("text") or "").strip() == w or (n.get("content-desc") or "").strip() == w:
                c = bounds_center(n.get("bounds"))
                if c:
                    return c
    return None


def cur_size():
    """Ekranın şu anki (döndürmeye göre) boyutu; yatay videoda wm size dikey değeri verir."""
    o = sh("dumpsys window displays | grep -m1 -o 'cur=[0-9]*x[0-9]*'")
    m = re.search(r"cur=(\d+)x(\d+)", o)
    return (int(m.group(1)), int(m.group(2))) if m else screen


def tap(c):
    sh(f"input tap {c[0]} {c[1]}")


def foreground_pkg():
    # Önce etkinlik yöneticisinin "en üstte çalışan" etkinliği; olmazsa pencere odağı.
    for cmd in ("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -2",
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2"):
        o = sh(cmd)
        m = re.search(r"\s([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.$]+", o)
        if m:
            return m.group(1)
    return ""


def frame_stats():
    """Ekranın orta bölgesinin parlaklığı: (ortalama 0..255, parlak piksel oranı). Okunamazsa None."""
    raw = adb("exec-out", "screencap", binary=True, timeout=30)
    if not raw or len(raw) < 16:
        return None
    w, h, fmt = struct.unpack("<III", raw[:12])
    off = 16 if len(raw) - 16 >= w * h * 4 else 12
    if len(raw) - off < w * h * 4:
        return None
    total = n = bright = 0
    for gy in range(14):
        y = int(h * (0.25 + 0.45 * gy / 13))
        for gx in range(24):
            x = int(w * (0.25 + 0.5 * gx / 23))
            i = off + (y * w + x) * 4
            lum = (raw[i] + raw[i + 1] + raw[i + 2]) / 3
            total += lum; n += 1
            if lum > 40:
                bright += 1
    return total / n, bright / n


def check_crash(step):
    """Çökme tamponunu okur; yeni çökme varsa kaydeder. True = çöktü."""
    global seen_crash_len
    buf = adb("logcat", "-d", "-b", "crash")
    new = buf[seen_crash_len:]
    seen_crash_len = len(buf)
    hit = False
    if PKG in new or "FATAL EXCEPTION" in new:
        crashes.append((step, new.strip()))
        hit = True
    anr = sh("dumpsys activity processes | grep -i 'notResponding\\|ANR' | head -3")
    if PKG in anr and "notResponding" in anr:
        crashes.append((step, "ANR: " + anr.strip()))
        hit = True
    return hit


def record(step, wait=2.0, note=""):
    time.sleep(wait)
    crashed = check_crash(step)
    fg = foreground_pkg()
    alive = bool(sh(f"pidof {PKG}").strip())
    res = "ÇÖKTÜ" if crashed else ("ok" if alive else "süreç yok")
    if fg and fg != PKG and not crashed:
        note = (note + f" ön planda: {fg}").strip()
    shot(step)
    steps.append((step, res, note))
    print(f"[{res}] {step} {note}", flush=True)
    return not crashed


def start_main():
    # Önceki adımda açık kalan dosya seçici (documentsui) ana ekranı örtmesin.
    sh("am force-stop com.google.android.documentsui")
    sh(f"am force-stop {PKG}")
    sh(f"am start -W -f 0x10008000 -n {PKG}/.MainActivity", timeout=40)
    time.sleep(3)


def start_music():
    start_main()
    c = find_text(dump(), "Müzik")
    if c:
        tap(c)
        time.sleep(3)
        return True
    return False


def play(path, mime="video/*"):
    sh("am force-stop com.google.android.documentsui")
    sh(f"am force-stop {PKG}")
    sh(f"am start -W -f 0x10008000 -a android.intent.action.VIEW -t '{mime}' -d 'file://{path}' -n {PKG}/.PlayerActivity", timeout=40)
    time.sleep(4)


def play_controls(path):
    play(path)
    # Video oynarken ekran hiç "durgun" olmadığı için arayüz ağacı okunamıyor; önce duraklat.
    sh("input keyevent KEYCODE_MEDIA_PAUSE")
    time.sleep(0.8)
    w, h = cur_size()
    tap((w // 2, h // 2))
    time.sleep(1)


def top_right_icon(root):
    """Üst çubuktaki en sağdaki tıklanabilir öğe (genelde ⋮)."""
    h = cur_size()[1]
    cands = [c for c in clickables(root) if c[1][1] < h * 0.12]
    return max(cands, key=lambda c: c[1][0])[1] if cands else None


def open_overflow(reset):
    def go():
        reset()
        c = top_right_icon(dump())
        if c:
            tap(c)
            time.sleep(1.5)
    return go


def open_settings():
    start_main()
    c = top_right_icon(dump())
    if c:
        tap(c); time.sleep(1.5)
        s = find_text_any(dump(), "Ayarlar")
        if s:
            tap(s); time.sleep(2.5)


def find_text_any(root, *wanted):
    """find_text gibi ama sistem açılır menülerini de (başka paket adıyla) kabul eder."""
    if root is None:
        return None
    for w in wanted:
        for n in root.iter("node"):
            if (n.get("text") or "").strip() == w or (n.get("content-desc") or "").strip() == w:
                c = bounds_center(n.get("bounds"))
                if c:
                    return c
    return None


def find_contains(root, part):
    if root is None:
        return None
    for n in root.iter("node"):
        t = (n.get("text") or "") + " " + (n.get("content-desc") or "")
        if part in t:
            c = bounds_center(n.get("bounds"))
            if c:
                return c
    return None


def play_song(title):
    """Müzik ekranında şarkıyı bulup dokunur; gerekirse Şarkılar sekmesine geçer, aşağı kaydırır."""
    for attempt in range(4):
        root = dump()
        c = find_contains(root, title)
        if c:
            tap(c)
            return True
        tab = find_contains(root, "Şarkılar")
        if tab and attempt == 0:
            tap(tab); time.sleep(2); continue
        w, h = screen
        sh(f"input swipe {w//2} {int(h*0.75)} {w//2} {int(h*0.3)} 300"); time.sleep(1)
    steps.append((f"Şarkı bulunamadı: {title}", "bilgi", ""))
    return False


def count_permission_requests():
    log = adb("logcat", "-d", "-b", "main,system", "-s", "ActivityTaskManager:I")
    return len(re.findall(r"START u0 .*(REQUEST_PERMISSIONS|GrantPermissionsActivity)", log))


def crawl(name, reset, limit=30, skip=()):
    reset()
    root = dump()
    if root is not None:
        open(f"{OUT}/ui/{slug(name)}.xml", "wb").write(ET.tostring(root))
    items = [it for it in clickables(root) if not any(s in it[0] for s in skip)]
    steps.append((f"{name}: {len(items)} tıklanabilir öğe", "bilgi", " | ".join(i[0] for i in items)))
    print(f"== {name}: {len(items)} öğe", flush=True)
    for i, (lab, c, longonly) in enumerate(items[:limit]):
        reset()
        if longonly:
            sh(f"input swipe {c[0]} {c[1]} {c[0]} {c[1]} 900")
        else:
            tap(c)
        ok = record(f"{name} > {lab}", 2.5)
        if ok and foreground_pkg() == PKG:
            # Açılan ekranda bir adım daha: geri tuşu da çökmemeli.
            sh("input keyevent KEYCODE_BACK")
            record(f"{name} > {lab} > geri", 1.5)


import atexit


def _emergency_report():
    """Betik yarıda kesilirse de adımlar ve çökmeler yazılsın."""
    if os.path.exists(f"{OUT}/report.md"):
        return
    try:
        with open(f"{OUT}/steps.tsv", "w") as fh:
            for st in steps:
                fh.write("\t".join(st) + "\n")
        with open(f"{OUT}/crashes.txt", "w") as fh:
            for st, txt in crashes:
                fh.write(f"===== ADIM: {st}\n{txt}\n\n")
            fh.write("===== ÇÖKME TAMPONU\n" + adb("logcat", "-d", "-b", "crash"))
        open(f"{OUT}/report.md", "w").write(f"# Yarıda kalan test\nAdım: {len(steps)}  Çökme: {len(crashes)}\n")
    except Exception:
        pass


atexit.register(_emergency_report)


def checkpoint(name):
    """Ara sonuçları ayrı bir dala gönderir; çalıştırıcı yarıda düşse de sonuçlar kaybolmasın."""
    tok = os.environ.get("GH_TOKEN"); repo = os.environ.get("GITHUB_REPOSITORY"); run = os.environ.get("GITHUB_RUN_ID")
    if not (tok and repo and run):
        return
    try:
        with open(f"{OUT}/steps.tsv", "w") as fh:
            for st in steps:
                fh.write("\t".join(st) + "\n")
        with open(f"{OUT}/crashes.txt", "w") as fh:
            for st, txt in crashes:
                fh.write(f"===== ADIM: {st}\n{txt}\n\n")
        open(f"{OUT}/checkpoint.txt", "w").write(name + "\n")
        b = f"emulator-results/{run}-ara"
        d = "/tmp/ckpt"
        subprocess.run(f"rm -rf {d} && mkdir -p {d} && cp {OUT}/steps.tsv {OUT}/crashes.txt {OUT}/checkpoint.txt {d}/ && "
                       f"cp -r {OUT}/screens {d}/ 2>/dev/null; cd {d} && git init -q -b x && git -c user.name=ci -c user.email=ci@x add -A && "
                       f"git -c user.name=ci -c user.email=ci@x commit -qm ara && "
                       f"git push -qf https://x-access-token:{tok}@github.com/{repo}.git HEAD:refs/heads/{b}",
                       shell=True, timeout=120)
        print(f"[ara sonuç] {name} -> {b}", flush=True)
    except Exception as e:
        print("checkpoint hata", e, flush=True)


# ---------------------------------------------------------------- kurulum
adb("wait-for-device")
sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
sh("settings put system screen_off_timeout 1800000; svc power stayon true; input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
# "Tam ekran görüntüleniyor" bilgi balonu testleri örtmesin
sh("settings put secure immersive_mode_confirmations confirmed")
print(adb("install", "-r", "-g", APK, timeout=300), flush=True)
sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
# Müzik sekmesini açık başlat (varsayılanda kapalı): hata ayıklanabilir derlemede run-as ile ayar dosyası yazılır.
open("/tmp/settings.xml", "w").write(
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
    '    <boolean name="music_mode" value="true" />\n</map>\n')
adb("push", "/tmp/settings.xml", "/data/local/tmp/settings.xml")
sh("chmod 644 /data/local/tmp/settings.xml")
print(sh(f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/settings.xml shared_prefs/settings.xml && ls -l shared_prefs'"), flush=True)
sh("mkdir -p /sdcard/Movies/Test /sdcard/Music/Test")
for sub, dst in (("Movies", "/sdcard/Movies/Test/"), ("Music", "/sdcard/Music/Test/")):
    for f in sorted(os.listdir(f"{MEDIA}/{sub}")):
        adb("push", f"{MEDIA}/{sub}/{f}", dst, timeout=120)
sh("content call --uri content://media --method scan_volume --arg external_primary", timeout=120)
time.sleep(5)
m = re.search(r"(\d+)x(\d+)", sh("wm size"))
screen = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
adb("logcat", "-c")
adb("logcat", "-b", "crash", "-c")
logcat = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=open(f"{OUT}/logcat.txt", "wb"))

def settings_page(title, scrolls=0):
    def go():
        open_settings()
        c = find_text(dump(), title)
        if c:
            tap(c); time.sleep(2)
            for _ in range(scrolls):
                sh(f"input swipe {screen[0]//2} {int(screen[1]*0.8)} {screen[0]//2} {int(screen[1]*0.25)} 400"); time.sleep(1)
    return go


if os.environ.get("SMOKE_ONLY") == "ayar":
    for page in ("Altyazı", "Liste ve dosyalar", "Tema ve görünüm", "Video ve çözücü"):
        crawl(f"Ayarlar › {page}", settings_page(page), limit=14, skip=("içe aktar", "Tüm dosyalara erişim"))
        crawl(f"Ayarlar › {page} (alt)", settings_page(page, 2), limit=10, skip=("dosyadan", "Dosyadan", "içe aktar", "Tüm dosyalara erişim"))
        crawl(f"Ayarlar › {page} (en alt)", settings_page(page, 4), limit=10, skip=("dosyadan", "Dosyadan", "içe aktar", "Tüm dosyalara erişim"))
        checkpoint(f"ayarlar {page}")
    start_main()
    mon = sh(f"monkey -p {PKG} -s 777 --throttle 120 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v 1500", timeout=900)
    open(f"{OUT}/monkey.txt", "w").write(mon)
    record("Monkey 1500 rastgele olay", 2, "monkey çökme buldu" if "// CRASH" in mon or "ANR" in mon else "")
    logcat.terminate()
    with open(f"{OUT}/steps.tsv", "w") as fh:
        for st in steps:
            fh.write("\t".join(st) + "\n")
    with open(f"{OUT}/crashes.txt", "w") as fh:
        for st, txt in crashes:
            fh.write(f"===== ADIM: {st}\n{txt}\n\n")
        fh.write("===== ÇÖKME TAMPONU\n" + adb("logcat", "-d", "-b", "crash"))
    open(f"{OUT}/report.md", "w").write(f"# Ayar sayfaları testi\nAdım: {len(steps)}  Çökme: {len(crashes)}\n")
    sys.exit(0)

# ---------------------------------------------------------------- senaryolar
start_main(); record("Ana ekran (ilk açılış)", 2)
root = dump()
open(f"{OUT}/ui/ana_ekran.xml", "wb").write(ET.tostring(root) if root is not None else b"")
steps.append(("Ana ekran yazıları", "bilgi", " | ".join(texts(root)[:60])))

# Videolar doğrudan
for f, note in (("Yatay video.mp4", ""), ("Dikey video.mp4", ""), ("Bolumlu.mkv", "bölüm + altyazı"),
                ("HEVC 10bit.mp4", "çözücü zinciri"), ("Eski bicim.wmv", "VLC yedeği beklenir"),
                ("Bozuk dosya.mp4", "hata mesajı beklenir")):
    play(f"/sdcard/Movies/Test/{f}")
    record(f"Video aç: {f}", 3, note)
    w, h = cur_size()
    tap((w // 2, h // 2)); record(f"Video kontrolleri: {f}", 1.5)
    sh(f"input swipe {w//2} {h//2} {w//2 + w//3} {h//2} 400"); record(f"Video yatay kaydırma (sarma): {f}", 1.5)
    sh("input keyevent KEYCODE_BACK"); record(f"Video kapat: {f}", 1.5)

# Altyazı: zamanlı ekran görüntüleri (satır 1: 1-4 sn, satır 2: 5-9 sn)
for f in ("Bolumlu.mkv", "Yatay video.mp4"):
    sh(f"am force-stop {PKG}")
    sh(f"am start -a android.intent.action.VIEW -t 'video/*' -d 'file:///sdcard/Movies/Test/{f}' -n {PKG}/.PlayerActivity")
    for k in range(12):
        time.sleep(0.9); shot(f"Altyazı {f} kare {k:02d}")
    steps.append((f"Altyazı ekran görüntüleri: {f}", "bilgi", "ekranda 'Merhaba…' ve 'İkinci satır' görünmeli"))

# Bozuk dosya: sessizce kapanmamalı, hata mesajı göstermeli
play("/sdcard/Movies/Test/Bozuk dosya.mp4")
time.sleep(6)
root = dump()
msg = [t for t in texts(root) if "açılamadı" in t or "oynatılamadı" in t]
fg = foreground_pkg()
record("Bozuk dosya: hata mesajı", 0.5, (msg[0] if msg else "MESAJ YOK") + f" (ön plan: {fg})")
steps.append(("Bozuk dosya sonucu", "ok" if msg and fg == PKG else "SORUN", msg[0] if msg else "ekran kapandı / mesaj yok"))
sh("input keyevent KEYCODE_BACK")

QUICK = os.environ.get("SMOKE_QUICK") == "1"
if QUICK:
    play("/sdcard/Movies/Test/Yatay video.mp4")
    for attempt in range(3):
        sh("input keyevent KEYCODE_MEDIA_PAUSE"); time.sleep(0.8)
        root = dump(); shot(f"Paylaş hazırlık {attempt}")
        c = top_right_icon(root)
        steps.append((f"Paylaş deneme {attempt}", "bilgi", f"öğeler: {[i[0] for i in clickables(root)]}"))
        if c:
            break
        w, h = cur_size(); tap((w // 2, h // 2)); time.sleep(1.2)
    if c:
        tap(c); time.sleep(1.5)
        root = dump(); shot("Paylaş menü")
        s2 = find_text(root, "Paylaş")
        if s2:
            tap(s2)
            ok = record("Dosya adresli videoyu paylaş", 3, "file:// paylaşımı")
            steps.append(("Paylaş sonucu", "ok" if ok else "SORUN", f"ön plan: {foreground_pkg()}"))
        else:
            steps.append(("Paylaş düğmesi yok", "SORUN", " | ".join(texts(root))))
    logcat.terminate()
    full = open(f"{OUT}/logcat.txt", "rb").read().decode(errors="replace")
    open(f"{OUT}/opsub.txt", "w").write("\n".join(l for l in full.splitlines() if "OPSUB" in l))
    with open(f"{OUT}/steps.tsv", "w") as fh:
        for st in steps:
            fh.write("\t".join(st) + "\n")
    with open(f"{OUT}/crashes.txt", "w") as fh:
        for st, txt in crashes:
            fh.write(f"===== ADIM: {st}\n{txt}\n\n")
    open(f"{OUT}/report.md", "w").write(f"# Hızlı test\nÇökme: {len(crashes)}\n")
    sys.exit(0)

checkpoint("videolar")
# Döndürme ve arka plana alma
play("/sdcard/Movies/Test/Yatay video.mp4")
sh("settings put system accelerometer_rotation 0; settings put system user_rotation 1"); record("Oynatırken ekran yatay", 3)
sh("settings put system user_rotation 0"); record("Oynatırken ekran dikey", 3)
sh("input keyevent KEYCODE_HOME"); record("Oynatırken ana ekrana dön (PiP olabilir)", 3)
sh(f"am start -W -n {PKG}/.MainActivity"); record("Uygulamaya geri dön", 3)

# Ekran gezintileri (her tıklanabilir öğeye tek tek)
crawl("Ana ekran", start_main, limit=30)
if start_music():
    record("Müzik sekmesi", 2)
    crawl("Müzik", start_music, limit=30)
crawl("Oynatıcı", lambda: play_controls("/sdcard/Movies/Test/Bolumlu.mkv"), limit=35)
crawl("Oynatıcı ⋮ menüsü", open_overflow(lambda: play_controls("/sdcard/Movies/Test/Bolumlu.mkv")), limit=25)
crawl("Ana ekran ⋮ menüsü", open_overflow(start_main), limit=12)
crawl("Ayarlar", open_settings, limit=30)
checkpoint("gezintiler")

def find_scroll(*wanted):
    """Kısa (yatay) ekranda kaydırmalı menülerde öğeyi bulana dek yukarı kaydırır."""
    w, h = cur_size()
    for _ in range(6):
        root = dump()
        e = find_text_any(root, *wanted)
        if not e:
            for t in wanted:
                e = find_contains(root, t)
                if e:
                    break
        if e:
            return e
        sh(f"input swipe {int(w*0.85)} {int(h*0.8)} {int(w*0.85)} {int(h*0.2)} 250")
        time.sleep(0.8)
    return None

# --- Beta15: 360° menüleri ---
def vr_open_menu():
    play_controls("/sdcard/Movies/Test/Yatay video.mp4")
    c = top_right_icon(dump())
    if not c:
        return False
    tap(c); time.sleep(1.5)
    r = find_scroll("360 olarak oynat", "360° ayarları")
    if not r:
        return False
    tap(r); time.sleep(1.5)
    return True

def vr_pick(label, texts):
    """Oynatıcı ⋮ → 360 satırı → verilen menü öğelerini sırayla seç."""
    if not vr_open_menu():
        steps.append((f"360 {label}", "SORUN", "360 satırı yok")); return
    shot(f"360_menu_{label}")
    for t in texts:
        e = find_scroll(t)
        if not e:
            steps.append((f"360 {label}: {t}", "SORUN", "öğe bulunamadı")); return
        tap(e); time.sleep(2.5)
        shot(f"360_{label}_{t}")
    record(f"360 {label}", 2)
    st = frame_stats()
    if st is None:
        steps.append((f"360 {label}: görüntü", "bilgi", "ekran okunamadı"))
    else:
        mean, frac = st
        note = f"parlaklık {mean:.0f}, parlak piksel %{frac*100:.0f}"
        # Sensör/VR gözlük emülatörde düz yatan sanal telefona bağlı olabilir; bunlar yalnız bilgi.
        soft = label in ("sensor", "vrgozluk")
        ok = frac >= 0.15
        steps.append((f"360 {label}: görüntü siyah değil", "ok" if ok else ("bilgi" if soft else "SORUN"), note))

# Sanal telefonu dik tut (yerçekimi + manyetik alan): sensör modu anlamlı görüntü versin.
adb("emu", "sensor", "set", "acceleration", "0:9.81:0")
adb("emu", "sensor", "set", "magnetic-field", "0:25:-40")

vr_pick("360", ["360° olarak oynat"])
# 360 seçildikten sonra menü "Lens türü: …" olur; lens listesinden diğerlerini dene
for lbl, lens in (("180", "180°"), ("balikgozu", "Balık gözü"), ("normal", "Normal")):
    vr_pick(lbl, ["Lens türü", lens])
vr_pick("3B", ["3B biçimi", "Yan yana"])
vr_pick("ufuk", ["Ufku düzelt"])
vr_pick("otodondur", ["Otomatik döndür"])
vr_pick("sensor", ["Hareket sensörüyle bak"])
vr_pick("vrgozluk", ["VR gözlük modu"])
# jest denemeleri
w, h = cur_size()
play_controls("/sdcard/Movies/Test/Yatay video.mp4")
sh(f"input swipe {w//2} {h//2} {w//2+200} {h//2} 300"); time.sleep(1)
sh(f"input tap {w//2} {h//2}"); sh(f"input tap {w//2} {h//2}"); time.sleep(1)
sh(f"input swipe {w//2} {h//2} {w//2} {h//2} 900"); time.sleep(1)
shot("360_uzun_basma")
sh("input keyevent KEYCODE_BACK"); sh("input keyevent KEYCODE_BACK")

# 360° ayar sayfasını gez
def open_vr_settings():
    open_settings()
    e = find_scroll("360° / VR")
    if e:
        tap(e); time.sleep(2)
crawl("360° ayarları", open_vr_settings, limit=15)

# 360° fotoğraf: dışa açık olmayan ekranı root kabuğuyla başlat
adb("root"); adb("wait-for-device"); time.sleep(4)
logcat.terminate()
logcat = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=open(f"{OUT}/logcat.txt", "ab"))
sh(f"am start -W -n {PKG}/.Vr360PhotoActivity -d file:///sdcard/Movies/Test/Panorama360.jpg", timeout=40)
time.sleep(5)
shot("360_foto")
record("360 fotoğraf ekranı", 2)
pw, ph = cur_size()
sh(f"input swipe {pw//2} {ph//2} {pw//2+300} {ph//2} 300"); time.sleep(1)
shot("360_foto_kaydir")
sh(f"input tap {pw//2} {ph//2}"); time.sleep(1)
shot("360_foto_cubuk")
checkpoint("360")

# Paylaş: dosya (file://) adresiyle açılmış videoyu paylaşmak
play_controls("/sdcard/Movies/Test/Yatay video.mp4")
c = top_right_icon(dump())
if c:
    tap(c); time.sleep(1.5)
    s = find_text(dump(), "Paylaş")
    if s:
        tap(s)
        ok = record("Dosya adresli videoyu paylaş", 3, "file:// paylaşımı")
        steps.append(("Paylaş sonucu", "ok" if ok else "SORUN", f"ön plan: {foreground_pkg()}"))
    else:
        steps.append(("Paylaş düğmesi bulunamadı", "bilgi", ""))
sh("input keyevent KEYCODE_BACK"); sh("input keyevent KEYCODE_BACK")

# Müzik çalarken video aç, kapat, müziğe dön
if start_music():
    play_song("Birinci Şarkı"); record("Müzik: şarkıya dokun", 3)
    sh("input keyevent KEYCODE_HOME")
    play("/sdcard/Movies/Test/Dikey video.mp4"); record("Müzik çalarken video aç", 3)
    sh("input keyevent KEYCODE_BACK"); record("Videodan çık", 2)
    start_music(); record("Müziğe geri dön", 2)
    play_song("Kayıpsız Parça"); record("Müzik: başka şarkı çal (videodan sonra)", 3)
    print(sh("dumpsys media_session | grep -E 'state=|description=' | head -6"), flush=True)
    steps.append(("Medya oturumu durumu", "bilgi", sh("dumpsys media_session | grep -E 'state=PlaybackState' | head -3").strip()))

# İzin reddedilince (bir daha sorma) ana ekran davranışı
sh(f"am force-stop {PKG}")
sh(f"pm revoke {PKG} android.permission.READ_MEDIA_VIDEO")
sh(f"pm set-permission-flags {PKG} android.permission.READ_MEDIA_VIDEO user-fixed")
adb("logcat", "-b", "main,system", "-c")
sh(f"am start -n {PKG}/.MainActivity")
time.sleep(10)
n = count_permission_requests()
record("İzin kalıcı reddedildi, ana ekran 10 sn", 0.5, f"izin ekranı {n} kez açıldı")
steps.append(("İzin isteği sayısı (10 sn)", "SORUN" if n > 3 else "ok", str(n)))
sh(f"pm clear-permission-flags {PKG} android.permission.READ_MEDIA_VIDEO user-fixed")
sh(f"pm grant {PKG} android.permission.READ_MEDIA_VIDEO")

checkpoint("temel akışlar")
# ---------------------------------------------------------------- beta11 özellik testleri
def write_prefs(extra):
    """Ayar dosyasını yazar (uygulama kapalıyken). extra: {ad: (tür, değer)}"""
    sh(f"am force-stop {PKG}")
    items = {"music_mode": ("boolean", "true")}
    items.update(extra)
    lines = []
    for k, (t, v) in items.items():
        if t == "string":
            lines.append(f'    <string name="{k}">{v}</string>')
        else:
            lines.append(f'    <{t} name="{k}" value="{v}" />')
    open("/tmp/settings.xml", "w").write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + "\n".join(lines) + "\n</map>\n")
    adb("push", "/tmp/settings.xml", "/data/local/tmp/settings.xml")
    sh("chmod 644 /data/local/tmp/settings.xml")
    sh(f"run-as {PKG} sh -c 'cp /data/local/tmp/settings.xml shared_prefs/settings.xml'")


def feat_log():
    return adb("logcat", "-d", "-s", "OPFEAT:*")


def feat(name, ok, note=""):
    steps.append((name, "ok" if ok else "SORUN", note))
    print(f"[{'ok' if ok else 'SORUN'}] {name} {note}", flush=True)


def resumed_activity():
    return sh("dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'")


def feature_tests():
    # 1) Uzun bas = hızlı oynat
    write_prefs({})
    adb("logcat", "-c")
    play("/sdcard/Movies/Test/Yatay video.mp4")
    w, h = cur_size()
    lp = subprocess.Popen(["adb", "shell", f"input swipe {w//2} {h//2} {w//2} {h//2} 3000"])
    time.sleep(1.6); shot("Uzun bas hızlı oynat (basılıyken)")
    lp.wait(timeout=20)
    time.sleep(0.8)
    lg = feat_log()
    feat("Uzun bas: hızlı oynatma açıldı", "fast on" in lg, lg.strip()[-200:])
    feat("Uzun bas: bırakınca normale döndü", "fast off" in lg, "")
    record("Uzun bas sonrası", 1)
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)

    # 2) Kaldığı yerden devam: "Sor"
    write_prefs({"resume_mode": ("string", "ASK")})
    play("/sdcard/Movies/Test/Yatay video.mp4")
    time.sleep(8)
    sh("input keyevent KEYCODE_BACK"); time.sleep(2)
    adb("logcat", "-c")
    play("/sdcard/Movies/Test/Yatay video.mp4")
    time.sleep(1.5)
    root = dump(); shot("Devam sorusu")
    c = find_text_any(root, "Devam et", "DEVAM ET")
    feat("Devam et sorusu göründü", c is not None or "resume ask" in feat_log(), " | ".join(texts(root)[:12]))
    if c:
        tap(c); record("Devam et seçildi", 2)
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)

    # 3) Köşe bilgisi, sarma düğmeleri, altyazı hizalama/italik/yazı tipi/kenar
    write_prefs({"osd_info": ("string", "BOTH"), "seek_buttons": ("boolean", "true"), "sub_align": ("int", "0"),
                 "sub_italic_slash": ("boolean", "true"), "sub_edge": ("int", "2"), "sub_font": ("string", "serif"),
                 "top_bar_clock": ("boolean", "true")})
    adb("logcat", "-c")
    play("/sdcard/Movies/Test/Bolumlu.mkv")
    time.sleep(2); shot("Köşe bilgisi + sol hizalı altyazı")
    time.sleep(5); shot("Eğik çizgi satırı (italik beklenir)")
    feat("Köşede saat/pil/süre görünüyor", "osd visible" in feat_log(), feat_log().strip()[-160:])
    sh("input keyevent KEYCODE_MEDIA_PAUSE"); time.sleep(0.8)
    w, h = cur_size(); tap((w // 2, h // 2)); time.sleep(1.2)
    root = dump(); shot("Kontroller: sarma düğmeleri")
    feat("İleri/geri sarma düğmeleri var", find_text(root, "İleri sar") is not None and find_text(root, "Geri sar") is not None)
    c = find_text(root, "İleri sar")
    if c:
        tap(c); record("İleri sar düğmesi", 1.5)
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)

    # 4) Ses: ters stereo + yankı + yumuşak başlatma, tek dokunuş = oynat/duraklat
    write_prefs({"reverse_stereo": ("boolean", "true"), "reverb_preset": ("int", "3"), "audio_fade": ("boolean", "true"),
                 "tap_action": ("string", "PLAY_PAUSE")})
    adb("logcat", "-c")
    play("/sdcard/Movies/Test/Yatay video.mp4")
    w, h = cur_size(); tap((w // 2, h // 2))
    record("Tek dokunuş = duraklat (ses efektleri açık)", 2, feat_log().strip()[-200:])
    tap((w // 2, h // 2)); record("Tek dokunuş = oynat", 2)
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)

    # 5) Geri tuşuna iki kez basarak çık, bitince listeye dön
    write_prefs({"double_back_exit": ("boolean", "true"), "back_to_list": ("boolean", "true")})
    play("/sdcard/Movies/Test/Dikey video.mp4")
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    r1 = resumed_activity()
    sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    r2 = resumed_activity()
    feat("Geri: ilk basışta kapanmadı", "PlayerActivity" in r1, r1.strip()[-120:])
    feat("Geri: ikinci basışta kapandı", "PlayerActivity" not in r2, r2.strip()[-120:])
    play("/sdcard/Movies/Test/Dikey video.mp4")
    time.sleep(12)
    r3 = resumed_activity()
    feat("Bitince listeye döndü", "PlayerActivity" not in r3, r3.strip()[-120:])
    record("Bitince listeye dön", 1)

    # 5b) Altyazı çevirisi: sahte OpenAI uyumlu sunucu (emülatörden 10.0.2.2 ile erişilir)
    import threading, http.server, json as _json
    hits = []

    class Mock(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
            hits.append(self.path)
            try:
                msgs = _json.loads(body)["messages"]
                arr = _json.loads(msgs[-1]["content"])
                out = ["ÇEVİRİ: " + str(x) for x in arr]
            except Exception:
                out = ["ÇEVİRİ"]
            resp = _json.dumps({"choices": [{"message": {"role": "assistant", "content": _json.dumps(out, ensure_ascii=False)}}]}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp))); self.end_headers(); self.wfile.write(resp)

    srv = http.server.ThreadingHTTPServer(("0.0.0.0", 18080), Mock)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        write_prefs({"tr_provider": ("string", "openai"), "tr_url": ("string", "http://10.0.2.2:18080/v1"),
                     "tr_model": ("string", "test-model"), "tr_target": ("string", "tr"), "tr_source": ("string", "en")})
        play("/sdcard/Movies/Test/Yatay video.mp4")
        time.sleep(3)
        w, h = cur_size(); tap((w // 2, h // 2)); time.sleep(1.2)
        c = find_text_any(dump(), "Altyazı")
        feat("Çeviri: altyazı simgesi bulundu", c is not None)
        if c:
            tap(c); time.sleep(1.2)
            root = dump(); shot("Altyazı menüsü (çeviri girişi)")
            c = find_contains(root, "Altyazıyı çevir")
            feat("Çeviri: menüde 'Altyazıyı çevir' var", c is not None, " | ".join(texts(root)[:14]))
            if c:
                tap(c); time.sleep(1.2)
                root = dump(); shot("Çeviri dil seçimi")
                c = find_contains(root, "Türkçe")
                if c:
                    tap(c)
                time.sleep(8)
                shot("Çeviri sonrası")
                files = sh(f"run-as {PKG} sh -c \"ls files/subtitles 2>&1\"")
                feat("Çeviri: .tr.srt dosyası yazıldı", ".tr.srt" in files, files.strip()[-160:])
                feat("Çeviri: sahte sunucu istek aldı", len(hits) > 0, str(hits[:3]))
                if ".tr.srt" in files:
                    content = sh(f"run-as {PKG} sh -c 'cat files/subtitles/*.tr.srt'")
                    feat("Çeviri: dosyada çevrilmiş metin var", "ÇEVİRİ" in content, content.strip()[:120])
    finally:
        srv.shutdown()
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    check_crash("Altyazı çevirisi")

    # 5c) beta14: kısayol rayı, klasör ızgarası, kenar şeridi
    write_prefs({"show_shortcuts": ("boolean", "true")})
    play("/sdcard/Movies/Test/Yatay video.mp4")
    time.sleep(2)
    w, h = cur_size(); tap((w // 2, h // 2)); time.sleep(1.2)
    def rail_count():
        """Kısayol satırındaki (üst şerit altı) tıklanabilir simge sayısı; okunamazsa None."""
        sh("input keyevent 127")  # duraklat: oynarken UI ağacı okunamıyor
        time.sleep(1.0)
        root = dump()
        if root is None:
            return None
        return len([1 for _, c, _ in clickables(root) if h * 0.10 < c[1] < h * 0.34])

    shot("Kısayol rayı: varsayılan")
    n0 = rail_count()
    y = int(h * 0.23)
    sh(f"input swipe {int(w*0.40)} {y} {int(w*0.02)} {y} 350"); time.sleep(1.0)
    shot("Kısayol rayı: çekilince açık")
    n1 = rail_count()
    sh(f"input swipe {int(w*0.02)} {y} {int(w*0.40)} {y} 350"); time.sleep(1.0)
    shot("Kısayol rayı: geri çekilince kapalı")
    n2 = rail_count()
    if None in (n0, n1, n2):
        steps.append(("Kısayol rayı: simge sayısı", "bilgi", f"okunamadı ({n0}/{n1}/{n2})"))
    else:
        good = n0 <= 5 and n1 > n0 and n2 == n0
        steps.append(("Kısayol rayı: simge sayısı", "ok" if good else "SORUN",
                      f"varsayılan {n0}, açık {n1}, kapanınca {n2} (beklenen: ≤5, artış, aynı)"))
    # Satırın sağındaki boş alan sürüklemeye tepki vermemeli.
    sh(f"input swipe {int(w*0.95)} {y} {int(w*0.60)} {y} 350"); time.sleep(1.0)
    n3 = rail_count()
    if n3 is not None and n2 is not None:
        steps.append(("Kısayol rayı: boş alan tepkisiz", "ok" if n3 == n2 else "SORUN", f"önce {n2}, sonra {n3}"))
    check_crash("Kısayol rayı")
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    write_prefs({"folder_grid": ("boolean", "true"), "folder_order": ("string", "count_desc")})
    start_main(); time.sleep(2.5)
    shot("Klasör ızgara görünümü (çok videolu önce)")
    check_crash("Klasör ızgarası")
    write_prefs({})

    # 6) Ayar sayfaları: her sayfada her satıra dokun (üst ve kaydırılmış alt kısım)
    write_prefs({})


    def open_settings_page(title, scrolls=0):
        def go():
            open_settings()
            c = find_text(dump(), title)
            if c:
                tap(c); time.sleep(2)
                for _ in range(scrolls):
                    sh(f"input swipe {screen[0]//2} {int(screen[1]*0.8)} {screen[0]//2} {int(screen[1]*0.25)} 400"); time.sleep(1)
        return go


    for page in ("Oynatıcı", "Hareketler", "Ses", "Altyazı", "Altyazı çevirisi", "Liste ve dosyalar"):
        crawl(f"Ayarlar › {page}", open_settings_page(page), limit=12, skip=("Ayarları içe aktar", "Tema içe aktar", "Tüm dosyalara erişim"))
        crawl(f"Ayarlar › {page} (alt)", open_settings_page(page, 2), limit=8, skip=("dosyadan", "Dosyadan", "Tüm dosyalara erişim"))
        checkpoint(f"ayarlar {page}")

    # Ana ekranda aşağı çekerek yenile
    start_main()
    sh(f"input swipe {screen[0]//2} {int(screen[1]*0.3)} {screen[0]//2} {int(screen[1]*0.8)} 500")
    record("Aşağı çekerek yenile", 3)



try:
    feature_tests()
except Exception as e:
    import traceback
    tb = traceback.format_exc()
    print(tb, flush=True)
    steps.append(("Özellik testleri yarıda kaldı", "SORUN", tb.strip().splitlines()[-1][:300]))
    open(f"{OUT}/feature_error.txt", "w").write(tb)

checkpoint("özellik testleri")
# Rastgele dokunma testi (monkey)
start_main()
mon = sh(f"monkey -p {PKG} -s 4242 --throttle 120 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v 1500", timeout=900)
open(f"{OUT}/monkey.txt", "w").write(mon)
record("Monkey 1500 rastgele olay", 2, "monkey çökme buldu" if "// CRASH" in mon or "ANR" in mon else "")

# ---------------------------------------------------------------- rapor
time.sleep(2)
logcat.terminate()
full = open(f"{OUT}/logcat.txt", "rb").read().decode(errors="replace")
crash_buf = adb("logcat", "-d", "-b", "crash")
with open(f"{OUT}/crashes.txt", "w") as fh:
    for st, txt in crashes:
        fh.write(f"===== ADIM: {st}\n{txt}\n\n")
    fh.write("===== ÇÖKME TAMPONU (tümü)\n" + crash_buf)
with open(f"{OUT}/steps.tsv", "w") as fh:
    for s in steps:
        fh.write("\t".join(s) + "\n")
# Uygulamanın kendi hata/uyarı satırları
pid_errors = [l for l in full.splitlines() if re.search(r" [EW] ", l) and
              re.search(r"openplayer|ExoPlayer|VLC|AndroidRuntime|StrictMode", l, re.I)]
open(f"{OUT}/app_errors.txt", "w").write("\n".join(pid_errors[-3000:]))
with open(f"{OUT}/report.md", "w") as fh:
    fh.write(f"# OpenPlayer emülatör testi\n\nAdım: {len(steps)}  Çökme: {len(crashes)}\n\n")
    for st, txt in crashes:
        first = next((l for l in txt.splitlines() if "Exception" in l or "Error" in l or "ANR" in l), txt[:200])
        fh.write(f"- **{st}** → `{first.strip()[:300]}`\n")
print(open(f"{OUT}/report.md").read())
