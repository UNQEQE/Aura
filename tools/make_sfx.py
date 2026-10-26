"""Genera los efectos de sonido (res/raw/sfx_*.wav). Requiere: pip install numpy scipy
   Uso: python tools/make_sfx.py [nombre ...]   (desde la raíz del proyecto; sin nombres genera todos). Edita las funciones de abajo para cambiar un sonido."""
import numpy as np, wave, os
from scipy.signal import butter, sosfilt, fftconvolve

SR = 32000
rng = np.random.default_rng(7)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res', 'raw')
os.makedirs(OUT, exist_ok=True)

# ---------- utilidades ----------
def canvas(d): return np.zeros(int(SR * d))
def place(c, s, start, gain=1.0):
    s = np.array(s, dtype=float)
    k = min(int(SR * 0.03), len(s) // 3)          # fade-out corto: evita el "clic" al cortar una capa
    if k > 0: s[-k:] *= np.linspace(1, 0, k) ** 2
    i = int(SR * start); n = min(len(s), len(c) - i)
    if n > 0: c[i:i + n] += s[:n] * gain
def phase(f): return 2 * np.pi * np.cumsum(f) / SR
def const(f, n): return np.full(n, float(f))
def sine(f): return np.sin(phase(f))
def saw(f): return 2 * ((phase(f) / (2 * np.pi)) % 1) - 1
def square(f): return np.sign(np.sin(phase(f)))
def tri(f): return 2 * np.abs(saw(f)) - 1
def expsweep(f0, f1, n): return f0 * (f1 / f0) ** (np.arange(n) / n)
def noise(n): return rng.standard_normal(n)
def env_ad(n, a, tau):
    t = np.arange(n) / SR
    return np.exp(-t / tau) * np.minimum(1, t / max(a, 1e-4))
def lp(x, fc, order=2): return sosfilt(butter(order, fc / (SR / 2), 'low', output='sos'), x)
def hp(x, fc, order=2): return sosfilt(butter(order, fc / (SR / 2), 'high', output='sos'), x)
def bp(x, lo, hi, order=2): return sosfilt(butter(order, [lo / (SR / 2), hi / (SR / 2)], 'band', output='sos'), x)
def norm(x): return x / (np.max(np.abs(x)) + 1e-9)

def svf(x, fc, q):
    """Filtro de variable de estado con corte variable en el tiempo -> (pasa-bajos, pasa-banda)."""
    fc = np.minimum(fc, SR / 6.0)
    f = 2 * np.sin(np.pi * fc / SR)
    if np.isscalar(f): f = np.full(len(x), f)
    low = band = 0.0
    L = np.zeros(len(x)); B = np.zeros(len(x)); iq = 1.0 / q
    for i in range(len(x)):
        low += f[i] * band
        high = x[i] - low - iq * band
        band += f[i] * high
        L[i] = low; B[i] = band
    return L, B

def reverb(x, wet=0.25, decay=0.6):
    n = int(SR * decay)
    ir = noise(n) * np.exp(-np.arange(n) / (SR * decay / 5.0)); ir[0] = 0
    w = fftconvolve(x, ir)[:len(x)]
    w *= (np.sqrt(np.mean(x ** 2)) + 1e-9) / (np.sqrt(np.mean(w ** 2)) + 1e-9)
    return x * (1 - wet) + w * wet

def finalize(x, name, rms=0.15):
    x = x - np.mean(x)
    x = x * (rms / (np.sqrt(np.mean(x ** 2)) + 1e-9))
    x = np.tanh(1.3 * x) / np.tanh(1.3)                # limitador suave
    x *= 0.95 / max(np.max(np.abs(x)), 1e-9)
    fi, fo = int(SR * 0.002), int(SR * 0.05)
    x[:fi] *= np.linspace(0, 1, fi); x[-fo:] *= np.linspace(1, 0, fo)
    # recortar silencio final
    idx = np.where(np.abs(x) > 0.004)[0]
    x = x[:min(len(x), idx[-1] + int(SR * 0.05))] if len(idx) else x
    pcm = (x * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    return x

# ---------- sonidos ----------
def six_seven():                       # "doot - doot - DOOOT" saltarín
    c = canvas(1.0)
    for st, f, dur in [(0.0, 523.25, 0.15), (0.2, 659.25, 0.15), (0.4, 783.99, 0.45)]:
        n = int(SR * dur * 2)
        fr = expsweep(f * 0.94, f, n)
        if dur > 0.3: fr = fr * (1 + 0.012 * np.sin(2 * np.pi * 6 * np.arange(n) / SR))
        s = lp(0.6 * square(fr) + 0.4 * tri(fr), 2800) * env_ad(n, 0.004, dur * 0.5)
        place(c, s, st)
    n = int(SR * 0.2); place(c, sine(expsweep(150, 60, n)) * env_ad(n, 0.002, 0.07), 0, 0.9)
    return reverb(c, 0.12, 0.3)

def dab():                             # whoosh + golpe hip-hop
    c = canvas(1.0)
    n = int(SR * 0.32)
    _, w = svf(noise(n), expsweep(4200, 500, n), 1.6)
    place(c, norm(w) * np.sin(np.pi * np.arange(n) / n) ** 1.5, 0.0, 0.8)
    n = int(SR * 0.4); place(c, sine(expsweep(160, 48, n)) * env_ad(n, 0.002, 0.11), 0.22, 1.4)
    n = int(SR * 0.22); place(c, hp(noise(n), 1500) * env_ad(n, 0.001, 0.045), 0.22, 0.7)
    n = int(SR * 0.5)
    place(c, (sine(const(1975, n)) * env_ad(n, 0.001, 0.12) + 0.4 * sine(const(2960, n)) * env_ad(n, 0.001, 0.06)), 0.22, 0.25)
    return reverb(c, 0.12, 0.3)

def swish(dur, f0, f1):
    n = int(SR * dur)
    _, w = svf(noise(n), expsweep(f0, f1, n), 2.2)
    return norm(w) * np.sin(np.pi * np.arange(n) / n) ** 2

def floss():                           # swish ... swish
    c = canvas(1.1)
    place(c, swish(0.42, 500, 4200), 0.02, 1.0)
    place(c, swish(0.42, 4400, 600), 0.48, 1.0)
    n = int(SR * 1.0); place(c, hp(noise(n), 5000) * 0.03, 0.02, 1.0)
    return c

def horn(dur, f0, fend=None):
    n = int(SR * dur)
    fr = const(f0, n) if fend is None else expsweep(f0, fend, n)
    fr = fr * (1 + 0.006 * np.sin(2 * np.pi * 5.5 * np.arange(n) / SR))
    s = np.zeros(n)
    for ratio, g in [(1, 1.0), (1.26, 0.9), (1.5, 0.8)]:
        for det in (-0.004, 0, 0.004): s += g * saw(fr * ratio * (1 + det))
    s = np.tanh(1.5 * lp(s / 9, 4200))
    i = np.arange(n)
    return s * np.minimum(1, i / (0.006 * SR)) * np.minimum(1, (n - i) / (0.05 * SR))

def hype():                            # bocina: pa - pa - PAAAA
    c = canvas(1.7)
    place(c, horn(0.17, 466), 0.0); place(c, horn(0.17, 466), 0.24); place(c, horn(0.6, 466, 415), 0.48)
    return reverb(c, 0.15, 0.4)

def t_pose():                          # riser + boom
    c = canvas(2.1)
    n = int(SR * 0.3); r = hp(noise(n), 2500) * (np.arange(n) / n) ** 2.5; place(c, norm(r), 0.0, 0.5)
    n = int(SR * 1.4); place(c, sine(expsweep(125, 36, n)) * env_ad(n, 0.003, 0.45), 0.3, 1.2)
    n = int(SR * 0.4); place(c, lp(noise(n), 900) * env_ad(n, 0.001, 0.08), 0.3, 0.8)
    return reverb(c, 0.3, 0.9)

def gigachad():                        # acorde de poder que se abre + platillo
    n = int(SR * 1.6); c = canvas(2.0)
    s = np.zeros(n)
    for f in (110, 164.81, 220, 329.63):
        for det in (-0.005, 0.005): s += saw(const(f * (1 + det), n))
    t = np.arange(n) / SR
    low, _ = svf(s, np.minimum(3800, 400 * (3800 / 400) ** np.minimum(1, t / 0.4)), 0.9)
    env = np.minimum(1, t / 0.03) * np.exp(-np.maximum(0, t - 1.0) / 0.25)
    place(c, np.tanh(1.4 * norm(low)) * env, 0.0, 1.0)
    n2 = int(SR * 1.2); place(c, hp(noise(n2), 6000) * env_ad(n2, 0.002, 0.5), 0.0, 0.25)
    return reverb(c, 0.2, 0.7)

def tom(f0, f1, tau, dur=0.6):
    n = int(SR * dur)
    return sine(expsweep(f0, f1, n)) * env_ad(n, 0.002, tau)

def wakanda():                         # tambores + acorde menor
    c = canvas(2.4)
    place(c, tom(140, 85, 0.22), 0.0); place(c, tom(160, 95, 0.22), 0.28); place(c, tom(110, 60, 0.4, 0.9), 0.56, 1.3)
    n = int(SR * 1.7); pad = np.zeros(n)
    for f in (220, 261.63, 329.63, 440):
        for d in (-0.003, 0.003): pad += sine(const(f * (1 + d), n)) + 0.3 * sine(const(2 * f * (1 + d), n))
    place(c, pad / 12 * np.sin(np.linspace(0, np.pi, n)) ** 1.5, 0.5, 0.9)
    return reverb(c, 0.3, 1.0)

def vowel(f0, formants):
    src = saw(f0) + 0.02 * noise(len(f0))
    return sum(g * norm(bp(src, fc * 0.93, fc * 1.07)) for fc, g in formants)

def siuuu():                           # "SI" corto + "UUU" que sube + multitud
    c = canvas(2.1)
    n = int(SR * 0.16)
    place(c, vowel(const(210, n), [(320, 1), (2300, 0.7), (3000, 0.3)]) * env_ad(n, 0.01, 0.08), 0.0, 0.8)
    n = int(SR * 1.15); t = np.arange(n) / SR
    f = expsweep(190, 340, n) * (1 + 0.015 * np.sin(2 * np.pi * 5.5 * t) * np.clip((t - 0.4) / 0.3, 0, 1))
    env = np.minimum(1, t / 0.08) * np.minimum(1, (n / SR - t) / 0.3)
    place(c, vowel(f, [(310, 1), (800, 0.6), (2300, 0.15)]) * env, 0.16, 1.0)
    n = int(SR * 1.7); r = bp(noise(n), 300, 2200); place(c, norm(r) * np.sin(np.pi * np.arange(n) / n) ** 2, 0.1, 0.35)
    return reverb(c, 0.18, 0.7)

def pluck(f, dur=1.2):
    n = int(SR * dur)
    return (sine(const(f, n)) + 0.35 * sine(const(2 * f, n)) + 0.12 * sine(const(3 * f, n))) * env_ad(n, 0.002, 0.45)

def alabanza():                        # arpa ascendente + brillo celestial
    c = canvas(2.8)
    for i, f in enumerate([523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1174.66, 1318.5, 1567.98, 1760.0]):
        place(c, pluck(f), 0.05 * i, 0.5)
    n = int(SR * 1.9); pad = np.zeros(n)
    for f in (523.25, 659.25, 783.99, 1046.5):
        for d in (-0.003, 0.003): pad += sine(const(f * (1 + d), n))
    place(c, pad / 8 * np.sin(np.linspace(0, np.pi, n)) ** 2, 0.35, 0.6)
    return reverb(c, 0.3, 1.2)

def pensador():                        # boom + "hmm-mm"
    c = canvas(1.6)
    n = int(SR * 0.6); place(c, sine(expsweep(95, 42, n)) * env_ad(n, 0.002, 0.2), 0, 1.2)
    n = int(SR * 0.05); place(c, lp(noise(n), 1200) * env_ad(n, 0.001, 0.012), 0, 0.6)
    for st, f in [(0.42, 329.63), (0.68, 261.63)]:
        n = int(SR * 0.5)
        place(c, (sine(const(f, n)) + 0.5 * sine(const(2 * f, n)) + 0.2 * sine(const(4.1 * f, n))) * env_ad(n, 0.002, 0.14), st, 0.5)
    return reverb(c, 0.15, 0.5)

def bell(f, dur=0.5, tau=0.16):
    n = int(SR * dur); t = np.arange(n) / SR
    s = sine(const(f, n)) + 0.5 * sine(const(2.76 * f, n)) * np.exp(-t / 0.05) + 0.3 * sine(const(5.4 * f, n)) * np.exp(-t / 0.03)
    return s * env_ad(n, 0.001, tau)

def combo():                           # ding-ding (se reproduce con más agudo según el multiplicador)
    c = canvas(0.8)
    place(c, bell(1318.5), 0, 1); place(c, bell(1760), 0.09, 1)
    return reverb(c, 0.1, 0.3)

def brass(f, dur):
    n = int(SR * dur); t = np.arange(n) / SR
    s = saw(const(f, n)) + saw(const(f * 1.004, n))
    low, _ = svf(s, np.minimum(3500, 600 + 6000 * t), 0.9)
    return norm(low) * np.minimum(1, t / 0.02) * np.exp(-np.maximum(0, t - dur * 0.6) / 0.2)

def rankup():                          # fanfarria
    c = canvas(2.6)
    for i, f in enumerate([523.25, 659.25, 783.99, 1046.5]): place(c, brass(f, 0.25), 0.1 * i, 0.6)
    for f in (523.25, 659.25, 783.99, 1046.5): place(c, brass(f, 1.1), 0.45, 0.45)
    for i in range(10): place(c, bell(2093 * (1 + 0.1 * i), 0.4, 0.1), 0.6 + 0.07 * i, 0.12)
    return reverb(c, 0.25, 0.9)

# ---------- emotes nuevos ----------
def corazon():                         # latido lub-dub + campanitas brillantes
    c = canvas(1.7)
    for st, g in [(0.0, 1.2), (0.2, 0.9)]:
        n = int(SR * 0.3); place(c, sine(expsweep(125, 48, n)) * env_ad(n, 0.004, 0.09), st, g)
    for i, f in enumerate([880.0, 1108.7, 1318.5, 1760.0]):
        place(c, bell(f, 0.9, 0.3), 0.45 + 0.09 * i, 0.45)
    return reverb(c, 0.25, 0.8)

def saludo():                          # redoble de tambor + trompeta militar
    c = canvas(1.5)
    for i in range(5):
        n = int(SR * 0.06); place(c, bp(noise(n), 1500, 6000) * env_ad(n, 0.001, 0.015), 0.05 * i, 0.5 + 0.1 * i)
    place(c, brass(392.0, 0.14), 0.3, 0.7); place(c, brass(523.25, 0.7), 0.5, 0.8)
    return reverb(c, 0.15, 0.4)

def poder():                           # subida + acorde heroico
    c = canvas(2.0)
    n = int(SR * 0.45); place(c, sine(expsweep(180, 900, n)) * np.sin(np.pi * np.arange(n) / n) ** 1.5, 0.0, 0.5)
    for f in (261.63, 329.63, 392.0, 523.25): place(c, brass(f, 1.1), 0.42, 0.45)
    n = int(SR * 0.6); place(c, sine(expsweep(110, 45, n)) * env_ad(n, 0.003, 0.2), 0.42, 1.0)
    return reverb(c, 0.25, 0.8)

def zen():                             # cuenco tibetano
    c = canvas(2.8)
    n = int(SR * 2.6); t = np.arange(n) / SR; f = 220.0
    s = (sine(const(f, n)) + 0.8 * sine(const(f * 1.004, n)) + 0.5 * sine(const(f * 2.0, n))
         + 0.35 * sine(const(f * 2.71, n)) * np.exp(-t / 0.9) + 0.2 * sine(const(f * 5.4, n)) * np.exp(-t / 0.4))
    place(c, s * env_ad(n, 0.004, 1.0), 0.0, 0.6)
    return reverb(c, 0.3, 1.3)

def presente():                        # timbre alegre de sala de clases (ding-DING)
    c = canvas(1.2)
    place(c, bell(987.77, 0.6, 0.2), 0.0, 0.7); place(c, bell(1318.5, 0.9, 0.3), 0.14, 0.9)
    n = int(SR * 0.08); place(c, sine(expsweep(300, 900, n)) * env_ad(n, 0.002, 0.03), 0.0, 0.4)
    return reverb(c, 0.15, 0.4)

def grulla():                          # whoosh + golpe de madera + gong
    c = canvas(2.4)
    n = int(SR * 0.3)
    _, w = svf(noise(n), expsweep(600, 3500, n), 1.8)
    place(c, norm(w) * np.sin(np.pi * np.arange(n) / n) ** 1.5, 0.0, 0.7)
    n = int(SR * 0.2)
    place(c, (sine(const(920, n)) + 0.4 * sine(const(1850, n))) * env_ad(n, 0.001, 0.03), 0.28, 0.9)
    place(c, bp(noise(n), 1800, 5000) * env_ad(n, 0.001, 0.02), 0.28, 0.5)
    place(c, bell(196.0, 1.9, 0.75), 0.3, 0.7)
    return reverb(c, 0.25, 0.9)

def ni_idea():                         # trombón triste: wah-wah-wah-waaah
    c = canvas(2.4)
    for st, f, d in [(0.0, 311.13, 0.32), (0.38, 293.66, 0.32), (0.76, 277.18, 0.32)]:
        place(c, brass(f, d), st, 0.8)
    n = int(SR * 0.9); fr = expsweep(233.08, 200.0, n) * (1 + 0.02 * np.sin(2 * np.pi * 6 * np.arange(n) / SR))
    place(c, lp(saw(fr) + saw(fr * 1.004), 1800) * env_ad(n, 0.02, 0.5) * 0.5, 1.15, 1.0)
    return reverb(c, 0.15, 0.4)

def aplauso():                         # aplausos sueltos + ovación
    c = canvas(1.8)
    for _ in range(46):
        st = rng.uniform(0, 1.3); n = int(SR * 0.05)
        place(c, bp(noise(n), 1200, 5500) * env_ad(n, 0.0005, 0.008), st, rng.uniform(0.3, 0.9))
    n = int(SR * 1.5); place(c, norm(bp(noise(n), 300, 2500)) * np.sin(np.pi * np.arange(n) / n) ** 2, 0.0, 0.22)
    return reverb(c, 0.2, 0.5)

SOUNDS = [('sfx_six_seven', six_seven), ('sfx_dab', dab), ('sfx_floss', floss), ('sfx_hype', hype),
          ('sfx_t_pose', t_pose), ('sfx_gigachad', gigachad), ('sfx_wakanda', wakanda), ('sfx_siuuu', siuuu),
          ('sfx_alabanza', alabanza), ('sfx_pensador', pensador), ('sfx_combo', combo), ('sfx_rankup', rankup),
          ('sfx_corazon', corazon), ('sfx_saludo', saludo), ('sfx_poder', poder), ('sfx_zen', zen),
          ('sfx_presente', presente), ('sfx_grulla', grulla), ('sfx_ni_idea', ni_idea), ('sfx_aplauso', aplauso)]

if __name__ == '__main__':
    import sys
    only = set(sys.argv[1:])            # opcional: python tools/make_sfx.py sfx_zen sfx_aplauso  (genera solo esos)
    res = {}
    for name, fn in SOUNDS:
        if only and name not in only: continue
        x = finalize(fn(), name)
        res[name] = x
        print(f"{name:14s} {len(x)/SR:4.2f}s  pico={np.max(np.abs(x)):.2f}  rms={np.sqrt(np.mean(x**2)):.3f}  nan={np.isnan(x).any()}")
