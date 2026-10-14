#!/usr/bin/env python3
"""OpenPlayer emülatör duman testi.

Uygulamayı kurar, örnek medyayı yükler, ana ekranları açar, her ekrandaki tıklanabilir
öğelere tek tek dokunur, ekran görüntüsü alır ve çökmeleri (FATAL EXCEPTION / ANR) kaydeder.
Sonuçlar out/ klasörüne yazılır: screens/*.png, ui/*.xml, steps.tsv, crashes.txt, logcat.txt, report.md
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

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
    o = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2")
    m = re.search(r"([a-zA-Z0-9_.]+)/", o)
    return m.group(1) if m else ""


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
    sh(f"am start -W -n {PKG}/.MainActivity", timeout=40)
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
    sh(f"am start -W -a android.intent.action.VIEW -t '{mime}' -d 'file://{path}' -n {PKG}/.PlayerActivity", timeout=40)
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


    for page in ("Oynatıcı", "Hareketler", "Ses", "Altyazı", "Liste ve dosyalar"):
        crawl(f"Ayarlar › {page}", open_settings_page(page), limit=18, skip=("Ayarları içe aktar", "Tema içe aktar", "Tüm dosyalara erişim"))
        crawl(f"Ayarlar › {page} (alt)", open_settings_page(page, 2), limit=18, skip=("dosyadan", "Dosyadan", "Tüm dosyalara erişim"))

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

# Rastgele dokunma testi (monkey)
start_main()
mon = sh(f"monkey -p {PKG} -s 4242 --throttle 120 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v 2500", timeout=900)
open(f"{OUT}/monkey.txt", "w").write(mon)
record("Monkey 2500 rastgele olay", 2, "monkey çökme buldu" if "// CRASH" in mon or "ANR" in mon else "")

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
