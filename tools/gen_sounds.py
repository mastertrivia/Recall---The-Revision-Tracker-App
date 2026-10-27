#!/usr/bin/env python3
"""
Procedurally synthesizes royalty-free notification + alarm tones for Goal Tracker.

Outputs 16-bit mono PCM WAV files into app/src/main/res/raw so they are:
  - bundled in the APK (available after install AND factory reset)
  - readable with no storage permission
  - independent of any external/user file

9 SHORT notification sounds  (brief, pleasant, played once)
5 LONG  alarm/warning sounds (looping patterns; the shell loops them to
                              1-60s so they ring like a real alarm)

All tones are generated from simple math (sine partials + envelopes) so they
are 100% original and royalty-free.
"""

import math
import os
import struct
import wave

SR = 32000  # sample rate (Hz) - good quality, compact size


def _clamp(x):
    if x > 1.0:
        return 1.0
    if x < -1.0:
        return -1.0
    return x


def write_wav(path, samples):
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = bytearray()
        for s in samples:
            frames += struct.pack("<h", int(_clamp(s) * 32767))
        w.writeframes(bytes(frames))
    print(f"  wrote {path}  ({len(samples)/SR:.2f}s, {os.path.getsize(path)//1024} KB)")


def silence(dur):
    return [0.0] * int(SR * dur)


def tone(freq, dur, partials=(1.0,), vol=0.6, attack=0.005, release=0.08, decay=None):
    """A note built from harmonic partials with an ADSR-ish envelope."""
    n = int(SR * dur)
    out = [0.0] * n
    for i in range(n):
        t = i / SR
        v = 0.0
        for k, amp in enumerate(partials, start=1):
            v += amp * math.sin(2 * math.pi * freq * k * t)
        # envelope
        env = 1.0
        if t < attack:
            env = t / attack
        elif t > dur - release:
            env = max(0.0, (dur - t) / release)
        if decay is not None:
            env *= math.exp(-decay * t)
        out[i] = v * env * vol
    return out


def mix(a, b):
    n = max(len(a), len(b))
    out = [0.0] * n
    for i in range(n):
        va = a[i] if i < len(a) else 0.0
        vb = b[i] if i < len(b) else 0.0
        out[i] = va + vb
    return out


def seq(*chunks):
    out = []
    for c in chunks:
        out.extend(c)
    return out


# Simple bell-like partial set (fundamental + a few overtones that decay)
BELL = (1.0, 0.5, 0.28, 0.14)
SOFT = (1.0, 0.35, 0.12)
PURE = (1.0,)

# Note frequencies
A5, C6, D6, E6, G6, A6, C7, E7 = 880, 1046.5, 1174.7, 1318.5, 1568, 1760, 2093, 2637
C5, E5, G5, F5, B5 = 523.25, 659.25, 783.99, 698.46, 987.77


def build_short():
    out_dir = os.path.join(RES_RAW)
    # 1) Chime - two-note rising bell
    write_wav(os.path.join(out_dir, "snd_short_chime.wav"),
              seq(tone(C6, 0.28, BELL, 0.5, decay=6),
                  tone(E6, 0.55, BELL, 0.5, decay=5)))

    # 2) Ding - single bright bell
    write_wav(os.path.join(out_dir, "snd_short_ding.wav"),
              tone(A6, 0.75, BELL, 0.55, decay=4.5))

    # 3) Marimba - three quick warm notes
    write_wav(os.path.join(out_dir, "snd_short_marimba.wav"),
              seq(tone(C6, 0.16, SOFT, 0.5, decay=9),
                  tone(E6, 0.16, SOFT, 0.5, decay=9),
                  tone(G6, 0.45, SOFT, 0.5, decay=6)))

    # 4) Pop - soft two-blip alert
    write_wav(os.path.join(out_dir, "snd_short_pop.wav"),
              seq(tone(G5, 0.11, SOFT, 0.5, decay=14),
                  silence(0.05),
                  tone(C6, 0.22, SOFT, 0.5, decay=10)))

    # 5) Glass - glassy 4-note arpeggio
    write_wav(os.path.join(out_dir, "snd_short_glass.wav"),
              seq(tone(C6, 0.13, BELL, 0.42, decay=8),
                  tone(E6, 0.13, BELL, 0.42, decay=8),
                  tone(G6, 0.13, BELL, 0.42, decay=8),
                  tone(C7, 0.5, BELL, 0.42, decay=5)))

    # 6) Soft harp - gentle rising pentatonic
    write_wav(os.path.join(out_dir, "snd_short_harp.wav"),
              seq(tone(C5, 0.18, BELL, 0.38, decay=7),
                  tone(E5, 0.18, BELL, 0.38, decay=7),
                  tone(G5, 0.18, BELL, 0.38, decay=7),
                  tone(B5, 0.18, BELL, 0.38, decay=7),
                  tone(E6, 0.55, BELL, 0.40, decay=4)))

    # 7) Woodblock - warm two-tap
    write_wav(os.path.join(out_dir, "snd_short_wood.wav"),
              seq(tone(E5, 0.09, SOFT, 0.48, decay=16),
                  silence(0.07),
                  tone(G5, 0.18, SOFT, 0.46, decay=12)))

    # 8) Piano - soft major third
    write_wav(os.path.join(out_dir, "snd_short_piano.wav"),
              mix(tone(C5, 0.85, (1.0, 0.45, 0.18, 0.08), 0.42, decay=3.2),
                  tone(E5, 0.85, (1.0, 0.40, 0.14), 0.32, decay=3.4)))

    # 9) Drop - descending water-drop ping
    write_wav(os.path.join(out_dir, "snd_short_drop.wav"),
              seq(tone(A6, 0.16, BELL, 0.42, decay=10),
                  tone(E6, 0.55, BELL, 0.38, decay=5)))


def build_long():
    out_dir = os.path.join(RES_RAW)

    # 1) Classic - repeating "beep beep ... beep beep" (loops seamlessly)
    beep = tone(A6, 0.18, PURE, 0.55, release=0.03)
    gap_s = silence(0.12)
    gap_l = silence(0.55)
    pattern = seq(beep, gap_s, beep, gap_l)
    write_wav(os.path.join(out_dir, "snd_long_classic.wav"),
              seq(*([pattern] * 6)))

    # 2) Siren - smooth rising/falling sweep (continuous, loopable)
    def sweep(dur, f_lo, f_hi):
        n = int(SR * dur)
        out = [0.0] * n
        phase = 0.0
        for i in range(n):
            frac = i / n
            f = f_lo + (f_hi - f_lo) * (0.5 - 0.5 * math.cos(2 * math.pi * frac))
            phase += 2 * math.pi * f / SR
            out[i] = 0.5 * math.sin(phase)
        return out
    write_wav(os.path.join(out_dir, "snd_long_siren.wav"),
              seq(*([sweep(1.4, 660, 1320)] * 5)))

    # 3) Pulse - urgent fast pulsing single tone
    pulse = tone(A6, 0.1, PURE, 0.5, release=0.02)
    ppat = seq(pulse, silence(0.1))
    write_wav(os.path.join(out_dir, "snd_long_pulse.wav"),
              seq(*([ppat] * 30)))

    # 4) Bells - repeating bell phrase
    phrase = seq(tone(E6, 0.22, BELL, 0.5, decay=5),
                 tone(C6, 0.22, BELL, 0.5, decay=5),
                 tone(G6, 0.5, BELL, 0.5, decay=4),
                 silence(0.25))
    write_wav(os.path.join(out_dir, "snd_long_bells.wav"),
              seq(*([phrase] * 5)))

    # 5) Digital - three-tone digital alarm burst, repeated
    burst = seq(tone(C7, 0.12, PURE, 0.5, release=0.02),
                tone(A6, 0.12, PURE, 0.5, release=0.02),
                tone(E7, 0.12, PURE, 0.5, release=0.02),
                silence(0.35))
    write_wav(os.path.join(out_dir, "snd_long_digital.wav"),
              seq(*([burst] * 7)))


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    RES_RAW = os.path.abspath(os.path.join(
        here, "..", "app", "src", "main", "res", "raw"))
    os.makedirs(RES_RAW, exist_ok=True)
    print("Generating SHORT notification sounds...")
    build_short()
    print("Generating LONG alarm sounds...")
    build_long()
    print("Done ->", RES_RAW)
