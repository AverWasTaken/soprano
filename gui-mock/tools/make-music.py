# synthesizes the title screen music into src/main/resources/assets/soprano/sounds/music/menu.ogg (stereo vorbis via
# ffmpeg) plus wav copies in gui-mock/assets/sounds/ and %TEMP%/am-preview/. run: python -I gui-mock/tools/make-music.py
# d minor, 90 bpm, 32 bars that loop forever. it's all saws, sines, one fm bell and some noise, no samples, so there's
# nothing to license. masters to about -22 dBFS rms, how loud it plays is MenuMusic's volume, not the file
# it measures itself afterwards and draws spectrograms into am-preview, so the seam can be checked without
# listening to the wrap forty times
import os
import subprocess
import sys
import tempfile
import time

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw
from scipy.ndimage import maximum_filter1d, minimum_filter1d, uniform_filter1d
from scipy.signal import butter, oaconvolve, sosfilt

SR = 48000
# 90 bpm at 48k is exactly 32000 samples a beat, so nothing ever rounds and the loop is exactly 4,096,000 frames
BEAT = SR * 60 // 90
assert BEAT * 90 == SR * 60
STEP = BEAT // 4
BAR = 4 * BEAT
BARS = 32
L = BARS * BAR
# two whole copies plus the first bar of a third. the second copy is the file: its start already has the first
# copy's tails under it. the extra bar is only there so the periodicity check can look at both ends of copy two
T = 2 * L + BAR
# longest anything rings past its slot (the bell). the loop buffer is this much longer than the loop
TAIL = 4 * SR
# filters that move get new coefficients every 256 samples. 256 divides the loop so every copy sees the same grid
BLOCK = 256
assert L % BLOCK == 0
SEED = 1990

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'soprano', 'sounds', 'music')
MOCK = os.path.join(ROOT, 'gui-mock', 'assets', 'sounds')
PREVIEW = os.path.join(tempfile.gettempdir(), 'am-preview')

# (root for the sub, pad voicing). every voicing lives in a3..e5 and the a3 never moves, it's in all four chords.
# dm9's e lives in the arp, not the pad: a pad e5 takes 1.2 s to let go and rubbed against every f5 that started
# the bbmaj9 bar
CHORDS = [
    (38, [57, 60, 65, 69, 74]),  # dm9: a3 c4 f4 a4 d5, the arp brings the 9th and the sub the root
    (34, [57, 62, 65, 69, 72]),  # bbmaj9: c4 up to d4, d5 down to c5, contrary motion
    (41, [57, 60, 64, 67, 72]),  # fmaj9: d4 f4 a4 all slide down a step, c5 stays
    (36, [57, 62, 64, 67, 72]),  # c6add9: c4 up to d4, the rest stays put
]
# arp notes per chord, a4 and up. no half steps in here and none against the pad, a bb right above the pad's a4 on
# every downbeat sounded like a wrong note, so the bb is left to the sub
ARP_TONES = [
    [69, 72, 74, 76, 81, 84, 86, 88],  # a c d e a c d e, the pad's f4 carries the minor third
    [69, 72, 74, 77, 81, 84, 86, 89],  # a c d f, the bb is the sub's
    [69, 72, 76, 79, 81, 84, 88, 91],  # a c e g a c e g
    [69, 72, 74, 76, 79, 81, 84, 86],  # a c d e g a c d
]
# overlapping rising triplets, so it rolls instead of running scales. the second bar of each chord climbs higher
ARP_A = [[0, 2, 4, 3, 1, 3, 5, 4, 2, 4, 6, 5, 3, 5, 4, 2],
         [0, 2, 4, 3, 1, 3, 5, 4, 2, 4, 6, 7, 6, 4, 5, 2]]
# the bell section drops the third 16th of every beat so the melody has room to breathe
ARP_B = [[0, 2, None, 4, 1, 3, None, 5, 2, 4, None, 6, 3, 5, None, 4],
         [0, 2, None, 4, 1, 3, None, 5, 2, 4, None, 7, 6, 4, None, 2]]
# 16th position inside a beat -> velocity. beats lead, the "e" is the softest
ACCENT = [1.0, 0.55, 0.72, 0.6]
# bars 17-24: (beat from bar 17, midi, velocity). d minor pentatonic only, every note a chord tone or a 9th, and the
# last d rings out over the start of the outro
BELL = [
    (0.0, 81, 1.0), (2.5, 74, 0.7), (5.0, 72, 0.8),
    (8.0, 77, 0.9), (9.5, 81, 0.7), (12.0, 84, 0.85), (14.5, 81, 0.6),
    (16.0, 79, 0.9), (18.5, 81, 0.7), (21.0, 72, 0.75),
    (24.0, 79, 0.85), (25.5, 74, 0.7), (28.0, 81, 0.8), (30.0, 74, 0.9),
]


def mtof(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def blep(p, dt):
    # polyblep: patches the two samples around each wrap. naive saws fold their harmonics back down into the
    # audible band and it sounds like a broken radio
    out = np.zeros_like(p)
    m = p < dt
    x = p[m] / dt
    out[m] = x + x - x * x - 1
    m = p > 1 - dt
    x = (p[m] - 1) / dt
    out[m] = x * x + x + x + 1
    return out


def saw(f, n, ph=0.0):
    p = (ph + f / SR * np.arange(n)) % 1.0
    return 2 * p - 1 - blep(p, f / SR)


def square(f, n, ph=0.0):
    dt = f / SR
    p = (ph + dt * np.arange(n)) % 1.0
    return np.where(p < 0.5, 1.0, -1.0) + blep(p, dt) - blep((p + 0.5) % 1.0, dt)


def rc(x):
    # raised cosine 0..1, a straight ramp has a corner you can hear
    x = np.clip(x, 0, 1)
    return 0.5 - 0.5 * np.cos(np.pi * x)


def seg(b, a0, a1):
    return rc((b - a0) / (a1 - a0))


def pan(p):
    # equal power, -1 left .. 1 right
    a = (p + 1) * np.pi / 4
    return np.cos(a), np.sin(a)


def place(buf, at, x):
    # x is mono (n,) or stereo (2, n)
    n = x.shape[-1]
    buf[..., at:at + n] += x


def tile(buf):
    # every copy plays the same notes with the same phases and the same noise, so copy k is just the loop buffer
    # moved k loops along. tails from the end of one copy land under the start of the next, which is the point
    out = np.zeros(buf.shape[:-1] + (T,))
    for k in range(3):
        a = k * L
        n = min(buf.shape[-1], T - a)
        out[..., a:a + n] += buf[..., :n]
    return out


def bar_of(n):
    return (np.asarray(n) % L) / BAR


def sos(kind, hz, order=2):
    return butter(order, hz, kind, fs=SR, output='sos')


def peaking(hz, gain_db, q):
    # rbj peaking eq, one biquad
    a = 10 ** (gain_db / 40)
    w = 2 * np.pi * hz / SR
    al = np.sin(w) / (2 * q)
    b = np.array([1 + al * a, -2 * np.cos(w), 1 - al * a])
    d = np.array([1 + al / a, -2 * np.cos(w), 1 - al / a])
    return np.concatenate([b / d[0], d / d[0]])[None]


def sweep(x, cutoff, q=0.707, stages=1):
    # a low pass whose cutoff follows cutoff(bar). one coefficient set per 256-sample block, state carried across so
    # the blocks join up. it's the whole bus, not per voice: a filter per voice would be 80 filters doing one job
    starts = np.arange(0, x.shape[-1], BLOCK)
    w = 2 * np.pi * cutoff(bar_of(starts)) / SR
    cw, al = np.cos(w), np.sin(w) / (2 * q)
    a0 = 1 + al
    co = np.stack([(1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, np.ones_like(w), -2 * cw / a0,
                   (1 - al) / a0], -1)
    y = np.empty_like(x)
    zi = np.zeros((stages,) + x.shape[:-1] + (2,))
    for i, s in enumerate(starts):
        y[..., s:s + BLOCK], zi = sosfilt(np.repeat(co[i][None], stages, 0), x[..., s:s + BLOCK], zi=zi)
    return y


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))))


def db(v):
    return 20 * np.log10(max(v, 1e-12))


def kept(x, a=0, b=BARS):
    # bars a..b of the second copy, the one that becomes the file
    return x[..., L + a * BAR:L + b * BAR]


def level(x, dbfs, a=0, b=BARS):
    return x * (10 ** (dbfs / 20) / rms(kept(x, a, b)))


# ---------------------------------------------------------------- voices


def pad_note(midi, n_on, rng):
    # five saws at 0, +-7, +-13 cents spread across the field, plus a sine an octave down for body. phases are rolled
    # per note so the copies of the loop get the exact same ones
    rel = int(1.2 * SR)
    n = n_on + rel
    t = np.arange(n) / SR
    f = mtof(midi)
    out = np.zeros((2, n))
    pans = rng.permutation([-0.85, -0.45, 0.0, 0.45, 0.85])
    for cents, p in zip([-13, -7, 0, 7, 13], pans):
        s = saw(f * 2 ** (cents / 1200), n, rng.random()) * 0.2
        gl, gr = pan(p)
        out[0] += gl * s
        out[1] += gr * s
    # kept quiet: five of these stacked at 110-190 Hz was most of the mud in the first render
    out += 0.18 * np.sin(2 * np.pi * f / 2 * t + 2 * np.pi * rng.random())
    env = rc(t / 0.8) * np.where(t < n_on / SR, 1.0, 1 - rc((t - n_on / SR) / 1.2))
    return out * env


def sub_note(midi, n):
    t = np.arange(n) / SR
    f = mtof(midi)
    # a touch of 2nd harmonic so it exists on speakers that can't do 60 Hz
    x = np.sin(2 * np.pi * f * t) + 0.12 * np.sin(4 * np.pi * f * t)
    env = rc(t / 0.008) * np.exp(-t / 1.4) * (1 - rc((t - (n / SR - 0.07)) / 0.07))
    return x * env


def pluck(midi, vel):
    n = int(0.9 * SR)
    t = np.arange(n) / SR
    f = mtof(midi)
    tone = 0.6 * saw(f, n) + 0.4 * square(f, n)
    # the brightness dies faster than the note, a poor man's filter envelope that costs nothing per voice
    bright = np.exp(-t / 0.07)
    x = tone * (0.3 + 0.7 * bright) + 0.5 * np.sin(2 * np.pi * f * t)
    env = rc(t / 0.002) * np.exp(-t / 0.16) * (1 - rc((t - 0.85) / 0.05))
    return vel * x * env


def bell_note(midi, vel):
    # 2-op fm at 3.5:1, the index starts glassy and falls off so the tail is nearly a sine
    n = int(3.8 * SR)
    t = np.arange(n) / SR
    f = mtof(midi)
    # an index of 2 put a third of the bell section's energy at 3-6k, which is the harsh band. 1.3 still rings
    index = 0.2 + 1.3 * np.exp(-t / 0.35)
    x = np.sin(2 * np.pi * f * t + index * np.sin(2 * np.pi * 3.5 * f * t))
    env = rc(t / 0.003) * np.exp(-t / 0.75) * (1 - rc((t - 3.6) / 0.2))
    return vel * x * env


def hat(rng, vel, tau):
    n = int(0.15 * SR)
    t = np.arange(n) / SR
    # fresh noise per hit, never a looped buffer
    return vel * rng.standard_normal(n) * rc(t / 0.001) * np.exp(-t / tau) * (1 - rc((t - 0.13) / 0.02))


def impulse(rng):
    # fake hall: two independent decaying noises (that's what makes it wide), highs dying faster than lows the way
    # air does it, 3.2 s to -60 dB, nothing above 6k
    n = int(3.4 * SR)
    t = np.arange(n) / SR
    pre = int(0.018 * SR)
    chans = []
    for _ in range(2):
        w = rng.standard_normal(n)
        k = np.exp(-t / 0.5)
        x = sosfilt(sos('low', 6000), w) * k + sosfilt(sos('low', 1800), w) * (1 - k)
        x *= 10 ** (-3 * t / 3.2) * rc(t / 0.004) * (1 - rc((t - 3.2) / 0.2))
        x = np.concatenate([np.zeros(pre), x])
        chans.append(x / np.sqrt(np.sum(x * x)))
    return chans


def pingpong(x, fb=0.35):
    # dotted 8th echoes bouncing left, right, left... odd repeats go left, even go right, each one fb quieter.
    # the recursion only looks back 2 delays, so it runs a 48000-sample chunk at a time instead of per sample
    d = 3 * BEAT // 4
    n = x.shape[-1]
    xl = np.zeros(n)
    xr = np.zeros(n)
    xl[d:] = x[:n - d]
    xr[2 * d:] = fb * x[:n - 2 * d]
    for y in (xl, xr):
        for c in range(2 * d, n, 2 * d):
            e = min(c + 2 * d, n)
            y[c:e] += fb * fb * y[c - 2 * d:e - 2 * d]
    # each repeat a little darker and thinner than the arp itself
    return sosfilt(sos('band', [300, 3200]), np.stack([xl, xr]))


def limit(y, ceiling_db):
    # brickwall with 5 ms look-ahead. g never goes above what the loudest sample within reach allows, so the output
    # can't pass the ceiling. on this mix it should barely ever do anything
    thr = 10 ** (ceiling_db / 20)
    la = int(0.005 * SR)
    m = maximum_filter1d(np.max(np.abs(y), axis=0), 2 * la + 1)
    g = np.minimum(1.0, thr / np.maximum(m, 1e-9))
    g = uniform_filter1d(minimum_filter1d(g, 2 * la + 1), la + 1)
    return y * g, float(g.min())


# ---------------------------------------------------------------- arrangement
# bars are 0-based here. 0-8 pad and sub, 8-16 arp and hats come in with the pump, 16-24 the bell, 24-32 everything
# thins back out until it looks like bar 0 again. every curve below is equal at bar 0 and bar 32 and flat-ish there,
# so the seam is just another bar


def pad_cutoff(b):
    # opens over the intro, closes over the outro, plus a slow breath every 4 bars (8 per loop, whole cycles)
    opened = seg(b, 0, 8) - seg(b, 24, 32)
    breath = 0.5 - 0.5 * np.cos(2 * np.pi * b / 4)
    return 900 * (2200 / 900) ** (0.8 * opened + 0.2 * breath)


def arp_cutoff(b):
    # 900 when silent, ~3k while it plays, a bit brighter under the bell, then it closes as it fades
    lc = (np.log(900) + np.log(3000 / 900) * seg(b, 7, 10) + np.log(3400 / 3000) * (seg(b, 16, 18) - seg(b, 24, 26))
          - np.log(3000 / 900) * seg(b, 25, 31.5))
    return np.exp(lc)


def pump(b, n):
    # quarter note duck, like a sidechain to a kick that isn't there. 15 ms in, then it decays to exactly zero by the
    # next beat so there's no step
    depth = 0.13 * (seg(b, 8, 9) - seg(b, 24, 31))
    t = (n % BEAT) / SR
    tb = BEAT / SR
    k = 0.14
    duck = rc(t / 0.015) * (np.exp(-t / k) - np.exp(-tb / k)) / (1 - np.exp(-tb / k))
    return 1 - depth * duck


def render():
    rng = np.random.default_rng(SEED)
    span = L + TAIL
    pad = np.zeros((2, span))
    sub = np.zeros(span)
    arp = np.zeros((2, span))
    bell = np.zeros((2, span))
    hats = np.zeros((2, span))

    for c in range(BARS // 2):
        root, voicing = CHORDS[c % 4]
        at = c * 2 * BAR
        for m in voicing:
            place(pad, at, pad_note(m, 2 * BAR, rng))
        for bar in range(2):
            # beat 1 and the "and" of 3
            place(sub, at + bar * BAR, sub_note(root, int(2.3 * BEAT)))
            place(sub, at + bar * BAR + int(2.5 * BEAT), sub_note(root, int(1.4 * BEAT)))

    for bar in range(8, 32):
        tones = ARP_TONES[(bar // 2) % 4]
        pattern = (ARP_B if 16 <= bar < 24 else ARP_A)[bar % 2]
        for s, i in enumerate(pattern):
            if i is None:
                continue
            v = ACCENT[s % 4] * (1.1 if s == 0 else 1.0)
            # a hair of width: alternate 16ths lean a little left and right, the delay does the rest
            gl, gr = pan(0.15 if s % 2 else -0.15)
            x = pluck(tones[i], v)
            place(arp, bar * BAR + s * STEP, np.stack([gl * x, gr * x]))

    for j, (beat, m, v) in enumerate(BELL):
        gl, gr = pan(0.25 if j % 2 else -0.2)
        x = bell_note(m, v)
        place(bell, 16 * BAR + int(beat * BEAT), np.stack([gl * x, gr * x]))

    for bar in range(8, 28):
        for e in range(8):
            off = e % 2 == 1
            gl, gr = pan(0.2 if off else -0.1)
            x = hat(rng, 0.85 if off else 0.55, 0.04 if off else 0.022)
            place(hats, bar * BAR + e * 2 * STEP, np.stack([gl * x, gr * x]))

    ir = impulse(rng)
    return [tile(x) for x in (pad, sub, arp, bell, hats)] + [ir]


def mix():
    pad, sub, arp, bell, hats, ir = render()
    n = np.arange(T)
    b = bar_of(n)

    # the sub owns everything under 120 Hz, the pad gets out of its way, and a small dip at 300 keeps the low
    # mids from going to mud with five voices stacked there
    pad = sosfilt(np.concatenate([sos('high', 120), peaking(260, -3.0, 0.7)]), pad)
    pad = sweep(pad, pad_cutoff, q=0.8)
    pad = level(pad * pump(b, n), -24)

    sub = sosfilt(sos('low', 220), sub)
    sub = level(sub * (0.85 + 0.15 * (seg(b, 8, 10) - seg(b, 24, 30))), -28.5)

    # 4 pole, so it's actually soft above 6k and not just "less"
    arp = sweep(arp, arp_cutoff, stages=2)
    arp = arp * (seg(b, 8, 10) - seg(b, 26, 31.5))
    arp = level(arp, -30, 10, 24)
    arp = arp + 0.3 * pingpong(arp.mean(0))

    bell = level(sosfilt(sos('low', 4000, 4), bell), -30, 16, 24)

    # air, not sizzle: 4 pole off the top too, and quiet enough that you notice it more when it leaves
    hats = sosfilt(np.concatenate([sos('high', 7000), sos('low', 11000, 4)]), hats)
    hats = level(hats * (seg(b, 8, 9) - seg(b, 24, 28)), -50, 9, 24)

    send = (0.35 * pad + 0.25 * arp + 0.5 * bell + 0.1 * hats).mean(0)
    send = sosfilt(sos('high', 200), send)
    rev = np.stack([oaconvolve(send, h)[:T] for h in ir])
    dry = pad + sub + arp + bell + hats
    rev = level(rev, db(rms(kept(dry))) - 9)

    y = sosfilt(sos('low', 16000), dry + rev)
    y = level(y, -22)
    y, gmin = limit(y, -3.5)
    return y, gmin


# ---------------------------------------------------------------- checks


def spectrogram(src, png, size):
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', src, '-lavfi',
                    f'showspectrumpic=s={size}:legend=1:fscale=log:color=intensity', png], check=True)


def seam_png(dec, png):
    # top: spectrogram of 2 s either side of the wrap. bottom: the waveform 15 ms either side, both channels
    w = 2 * SR
    tmp = os.path.join(PREVIEW, 'seam.wav')
    sf.write(tmp, np.concatenate([dec[-w:], dec[:w]]), SR, subtype='FLOAT')
    spec = os.path.join(PREVIEW, 'seam-spec.png')
    spectrogram(tmp, spec, '1200x600')
    os.remove(tmp)
    top = Image.open(spec).convert('RGB')
    os.remove(spec)
    h = 360
    k = int(0.015 * SR)
    seg2 = np.concatenate([dec[-k:], dec[:k]])
    wave = Image.new('RGB', (top.width, h), (12, 12, 16))
    d = ImageDraw.Draw(wave)
    xs = np.linspace(0, top.width - 1, len(seg2))
    peak = np.max(np.abs(seg2)) * 1.1
    for ch, col, mid in ((0, (120, 200, 255), h // 4), (1, (255, 170, 120), 3 * h // 4)):
        ys = mid - seg2[:, ch] / peak * (h // 4 - 6)
        d.line(list(zip(xs.tolist(), ys.tolist())), fill=col, width=1)
        d.line([(0, mid), (top.width, mid)], fill=(50, 50, 60))
    d.line([(top.width / 2, 0), (top.width / 2, h)], fill=(255, 80, 80))
    d.text((6, 4), 'L, last 15 ms | first 15 ms (red = wrap)', fill=(200, 200, 200))
    d.text((6, h // 2 + 4), 'R', fill=(200, 200, 200))
    out = Image.new('RGB', (top.width, top.height + h))
    out.paste(top, (0, 0))
    out.paste(wave, (0, top.height))
    out.save(png)


def check(y, gmin, ogg):
    n = 4800
    print('periodicity, end of copy 1 vs end of copy 2  max|d| =', f'{np.max(np.abs(y[:, L - n:L] - y[:, 2 * L - n:2 * L])):.2e}')
    print('periodicity, start of copy 2 vs start of copy 3  max|d| =', f'{np.max(np.abs(y[:, L:L + n] - y[:, 2 * L:2 * L + n])):.2e}')
    # copy 1 starts from silence, copy 2 starts on copy 1's tails. that difference is the reason we keep copy 2
    print('  (start of copy 1 vs copy 2, the tails we keep  max|d| =', f'{np.max(np.abs(y[:, :n] - y[:, L:L + n])):.2e})')
    print('limiter min gain', f'{db(gmin):.2f} dB')

    dec, sr = sf.read(ogg, always_2d=True)
    assert sr == SR and dec.shape == (L, 2), (sr, dec.shape)
    assert np.all(np.isfinite(dec))
    print('decoded frames', dec.shape[0], '(want', L, ')')
    peak = db(np.max(np.abs(dec)))
    print(f'decoded peak {peak:.2f} dBFS, clipped samples {int(np.sum(np.abs(dec) >= 0.999))}')
    print(f'decoded rms {db(rms(dec)):.2f} dBFS')
    secs = [db(rms(dec[a * 8 * BAR:(a + 1) * 8 * BAR])) for a in range(4)]
    print('section rms', ' '.join(f'{s:.2f}' for s in secs), ' neighbour jumps (wrapping)',
          ' '.join(f'{secs[(a + 1) % 4] - secs[a]:+.2f}' for a in range(4)))
    bar_rms = [db(rms(dec[a * BAR:(a + 1) * BAR])) for a in range(BARS)]
    print('bar rms', ' '.join(f'{v:.1f}' for v in bar_rms))
    print('largest bar to bar step (wrapping)', f'{max(abs(bar_rms[(a + 1) % BARS] - bar_rms[a]) for a in range(BARS)):.2f} dB')
    print(f'stereo correlation {np.corrcoef(dec[:, 0], dec[:, 1])[0, 1]:.3f}')

    # the wrap: the decoded file played twice vs the render running straight through copy 2 into copy 3
    wrap = np.concatenate([dec[-n:], dec[:n]])
    ref = np.concatenate([y[:, 2 * L - n:2 * L], y[:, 2 * L:2 * L + n]], axis=1).T
    mid = L // 2
    codec = np.max(np.abs(dec[mid - n:mid + n] - y[:, L + mid - n:L + mid + n].T))
    print(f'wrap vs straight-through render max|d| {np.max(np.abs(wrap - ref)):.4f}  (codec error mid-file {codec:.4f})')
    step = np.abs(dec[0] - dec[-1])
    local = np.percentile(np.abs(np.diff(wrap, axis=0)), 99.9, axis=0)
    print('sample step across the wrap', np.round(step, 5), ' 99.9th pct step nearby', np.round(local, 5))
    w = int(0.02 * SR)
    print(f'rms last 20 ms {db(rms(dec[-w:])):.2f} dBFS, first 20 ms {db(rms(dec[:w])):.2f} dBFS')
    # second opinion from ffmpeg's decoder, which handles the stream start its own way: same length, and the first
    # 100 ms line up with the render (anything off by a sample would show up way above codec error)
    raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', ogg, '-f', 'f32le', '-'], check=True,
                         capture_output=True).stdout
    alt = np.frombuffer(raw, np.float32).reshape(-1, 2)
    print('ffmpeg decode frames', alt.shape[0], ' first 100 ms vs render max|d|',
          f'{np.max(np.abs(alt[:n] - y[:, L:L + n].T)):.4f}')
    assert alt.shape[0] == L
    # decision 9 is true peak, the sample peak above can miss inter-sample overs
    meter = subprocess.run(['ffmpeg', '-hide_banner', '-nostats', '-i', ogg, '-af', 'ebur128=peak=true', '-f', 'null', '-'],
                           check=True, capture_output=True, text=True).stderr
    tp = float(meter.rsplit('Peak:', 1)[1].split('dBFS')[0])
    print(f'true peak {tp:.2f} dBTP')
    assert peak <= -3.0 and tp <= -3.0
    return dec


def write(y, gmin):
    for d in (OUT, MOCK, PREVIEW):
        os.makedirs(d, exist_ok=True)
    loop = y[:, L:2 * L].T.astype(np.float32)
    for d in (MOCK, PREVIEW):
        sf.write(os.path.join(d, 'menu.wav'), loop, SR, subtype='PCM_16')
    ogg = os.path.join(OUT, 'menu.ogg')
    # vorbis gets the float render, not the 16 bit wav, no point quantizing twice
    src = os.path.join(PREVIEW, 'menu-float.wav')
    sf.write(src, loop, SR, subtype='FLOAT')
    # q5 if it fits under 2 MB, q4 otherwise. bitexact keeps the ffmpeg version out of the file
    for q in ('5', '4'):
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', src, '-c:a', 'libvorbis', '-q:a', q,
                        '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact', ogg], check=True)
        if os.path.getsize(ogg) < 2_000_000:
            break
    os.remove(src)
    print(ogg, os.path.getsize(ogg), 'bytes at q' + q)
    dec = check(y, gmin, ogg)
    spectrogram(ogg, os.path.join(PREVIEW, 'menu-spectrogram.png'), '2048x900')
    seam_png(dec, os.path.join(PREVIEW, 'menu-seam.png'))
    print('pngs in', PREVIEW)


if __name__ == '__main__':
    t0 = time.time()
    y, gmin = mix()
    print(f'rendered in {time.time() - t0:.1f} s')
    write(y, gmin)
    sys.exit(0)
