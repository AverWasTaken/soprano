# synthesizes the title screen's ui sounds into src/main/resources/assets/soprano/sounds/ui/ (mono ogg vorbis via
# ffmpeg) plus wav copies in gui-mock/assets/sounds/ for the mock. run: python -I gui-mock/tools/make-sounds.py
# everything is a couple of sines and some filtered noise, no samples, so there's nothing to license
import os
import subprocess
import sys

import numpy as np
import soundfile as sf
from scipy.signal import butter, sosfilt

SR = 48000
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'soprano', 'sounds', 'ui')
MOCK = os.path.join(ROOT, 'gui-mock', 'assets', 'sounds')


def env(n, attack, tau):
    t = np.arange(n) / SR
    a = np.clip(t / attack, 0, 1)
    # a raised-cosine attack, a straight ramp clicks at the start
    a = 0.5 - 0.5 * np.cos(np.pi * a)
    return a * np.exp(-t / tau)


def fade_tail(x, ms=12):
    k = int(SR * ms / 1000)
    x[-k:] *= np.linspace(1, 0, k)
    return x


def lowpass(x, hz, order=2):
    return sosfilt(butter(order, hz, 'low', fs=SR, output='sos'), x)


def bandpass(x, lo, hi, order=2):
    return sosfilt(butter(order, [lo, hi], 'band', fs=SR, output='sos'), x)


def norm(x, dbfs):
    return x / np.max(np.abs(x)) * 10 ** (dbfs / 20)


def hover():
    # soft wooden "tup": a sine at e5 with a quiet octave, rounded off hard by a low pass so it sits under everything.
    # the game pitches this per tile, so it has to sound fine from 0.67x to 1.5x
    n = int(SR * 0.16)
    t = np.arange(n) / SR
    f = 659.25
    tone = np.sin(2 * np.pi * f * t) + 0.22 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t / 0.02)
    x = tone * env(n, 0.004, 0.045)
    x = lowpass(x, 1600, 2)
    return fade_tail(norm(x, -15.0))


def click():
    # higher and crisper: e6 with a marimba-ish inharmonic partial and a 4 ms noise tick on top. still low passed so
    # it's a tap, not a beep
    n = int(SR * 0.11)
    t = np.arange(n) / SR
    f = 1318.5
    tone = np.sin(2 * np.pi * f * t) + 0.18 * np.sin(2 * np.pi * 2.76 * f * t) * np.exp(-t / 0.008)
    x = tone * env(n, 0.0015, 0.022)
    rng = np.random.default_rng(7)
    tick = bandpass(rng.standard_normal(n), 2500, 6000) * env(n, 0.0005, 0.0025)
    x = x + 0.35 * tick / np.max(np.abs(tick))
    x = lowpass(x, 6000, 2)
    return fade_tail(norm(x, -11.0))


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(MOCK, exist_ok=True)
    wav = os.path.join(MOCK, name + '.wav')
    sf.write(wav, x.astype(np.float32), SR, subtype='PCM_16')
    ogg = os.path.join(OUT, name + '.ogg')
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-ac', '1', '-c:a', 'libvorbis', '-q:a', '6',
                    '-map_metadata', '-1', ogg], check=True)
    print(ogg, os.path.getsize(ogg), 'bytes')


if __name__ == '__main__':
    write('hover', hover())
    write('click', click())
    sys.exit(0)
