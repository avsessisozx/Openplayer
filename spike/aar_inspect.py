import sys, struct, re, os, glob

WANT = """equalizer anequalizer superequalizer firequalizer aecho adelay pan volume loudnorm acompressor
dynaudnorm rubberband scaletempo atempo asetrate aresample stereotools extrastereo lavfi-bridge
bass treble highpass lowpass aformat anull hflip vflip rotate transpose crop scale""".split()


def cstrings(data, minlen=3):
    return set(m.group(0).decode('latin1') for m in re.finditer(rb'[\x20-\x7e]{%d,}' % minlen, data))


def load_aligns(d):
    is64 = d[4] == 2
    if is64:
        phoff = struct.unpack_from('<Q', d, 32)[0]
        phsz, phn = struct.unpack_from('<HH', d, 54)
    else:
        phoff = struct.unpack_from('<I', d, 28)[0]
        phsz, phn = struct.unpack_from('<HH', d, 42)
    out = set()
    for i in range(phn):
        o = phoff + i * phsz
        if struct.unpack_from('<I', d, o)[0] == 1:
            out.add(struct.unpack_from('<Q', d, o + 48)[0] if is64 else struct.unpack_from('<I', d, o + 28)[0])
    return sorted(out)


root = sys.argv[1]
for v in ('jdtech', 'abdallah'):
    print('#####', v)
    j = os.path.join(root, v, 'x', 'jni')
    for abi in ('arm64-v8a', 'x86_64'):
        bad = []
        for so in sorted(glob.glob(os.path.join(j, abi, '*.so'))):
            al = load_aligns(open(so, 'rb').read())
            if min(al) < 0x4000:
                bad.append((os.path.basename(so), [hex(a) for a in al]))
        print(abi, 'under-16K libs:', bad or 'none')
    af = open(os.path.join(j, 'arm64-v8a', 'libavfilter.so'), 'rb').read()
    s = cstrings(af)
    print('libavfilter present:', [w for w in WANT if w in s])
    print('libavfilter absent :', [w for w in WANT if w not in s])
    print('libavfilter total printable strings:', len(s))
    mp = open(os.path.join(j, 'arm64-v8a', 'libmpv.so'), 'rb').read()
    ms = cstrings(mp, 4)
    for pat in (r'^mpv v?\d', r'^v?0\.\d+\.\d+', r'scaletempo2', r'rubberband', r'libass', r'mediacodec', r'^GPLv|LGPL|GNU'):
        print('  mpv', pat, sorted(x for x in ms if re.search(pat, x))[:8])
    ac = open(os.path.join(j, 'arm64-v8a', 'libavcodec.so'), 'rb').read()
    cs = cstrings(ac, 4)
    print('  avcodec cfg', [x[:600] for x in cs if '--enable-' in x or '--disable-' in x][:2])
    print('  avcodec lic', [x for x in cs if re.search(r'GPL', x)][:6])
    print('  avcodec hevc/av1:', sorted(x for x in cs if x in ('hevc', 'hevc_mediacodec', 'av1', 'libdav1d', 'h264_mediacodec', 'subrip', 'ass', 'mov_text', 'pgssub', 'dvdsub')))
    for lib in sorted(glob.glob(os.path.join(j, 'arm64-v8a', '*.so'))):
        d = open(lib, 'rb').read()
        hits = sorted(set(m.group(0).decode() for m in re.finditer(rb'lib[a-zA-Z0-9_+\-]+\.so(?:\.[0-9]+)?', d)))
        print('   ', os.path.basename(lib), 'needs/mentions:', hits[:16])
    pl = open(os.path.join(j, 'arm64-v8a', 'libplayer.so'), 'rb').read()
    print('  JNI:', sorted(x for x in cstrings(pl, 8) if x.startswith('Java_')))
