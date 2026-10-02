import numpy as np, wave
from scipy.signal import butter, sosfilt
SR = 48000

def write(name, x):
    x = np.clip(x, -1, 1)
    with wave.open(name, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype('<i2').tobytes())

def tap(modes, noise_amp, noise_tau, noise_band, body=None, length=0.07, pitch=1.0, seed=0, peak=0.6, attack=0.0004):
    rng = np.random.default_rng(seed)
    n = int(length * SR); t = np.arange(n) / SR
    x = np.zeros(n)
    # resonant modes: (freq, amp, decay tau)
    for f, a, tau in modes:
        x += a * np.sin(2*np.pi*f*pitch*t + rng.uniform(0, 0.3)) * np.exp(-t / tau)
    # body: low sine with a quick downward pitch glide = the soft "thump"
    if body:
        f0, a, tau, glide = body
        f = f0 * pitch * (1 + glide * np.exp(-t / 0.004))
        phase = 2*np.pi*np.cumsum(f) / SR
        x += a * np.sin(phase) * np.exp(-t / tau)
    # contact transient: band-limited noise burst
    noise = rng.standard_normal(n)
    sos = butter(2, [noise_band[0], noise_band[1]], btype='band', fs=SR, output='sos')
    x += noise_amp * sosfilt(sos, noise) * np.exp(-t / noise_tau)
    # smooth attack and tail so there are no clicks from the edges
    a_n = max(1, int(attack * SR))
    x[:a_n] *= 0.5 - 0.5*np.cos(np.linspace(0, np.pi, a_n))
    f_n = int(0.008 * SR)
    x[-f_n:] *= np.linspace(1, 0, f_n)
    return x / np.max(np.abs(x)) * peak

packs = {
  # Soft: light, rounded tap like a phone keyboard, centered ~1.3 kHz with a little air on top
  'soft': dict(
    key   = dict(modes=[(1300, 1.0, 0.0055), (2650, 0.35, 0.0030), (4300, 0.12, 0.0016)],
                 noise_amp=0.35, noise_tau=0.0012, noise_band=(2500, 9000), body=(300, 0.45, 0.004, 0.6), peak=0.55),
    space = dict(modes=[(820, 1.0, 0.0075), (1750, 0.30, 0.0040), (3100, 0.10, 0.0020)],
                 noise_amp=0.30, noise_tau=0.0016, noise_band=(1800, 7000), body=(210, 0.6, 0.006, 0.6), peak=0.6),
    delete= dict(modes=[(1050, 1.0, 0.0050), (2200, 0.30, 0.0028), (3700, 0.10, 0.0015)],
                 noise_amp=0.30, noise_tau=0.0011, noise_band=(2200, 8000), body=(260, 0.45, 0.004, 0.6), peak=0.52),
    enter = dict(modes=[(700, 1.0, 0.0090), (1500, 0.35, 0.0050), (2800, 0.12, 0.0025)],
                 noise_amp=0.30, noise_tau=0.0018, noise_band=(1500, 6000), body=(180, 0.7, 0.008, 0.7), peak=0.65, length=0.09),
  ),
  # Thock: deeper, cushioned, like a lubed mechanical keyboard
  'thock': dict(
    key   = dict(modes=[(620, 1.0, 0.0070), (1450, 0.30, 0.0035), (2900, 0.10, 0.0018)],
                 noise_amp=0.25, noise_tau=0.0012, noise_band=(1500, 6000), body=(150, 0.9, 0.007, 0.8), peak=0.6),
    space = dict(modes=[(430, 1.0, 0.0095), (1050, 0.30, 0.0050), (2200, 0.08, 0.0022)],
                 noise_amp=0.22, noise_tau=0.0018, noise_band=(1000, 5000), body=(110, 1.0, 0.010, 0.8), peak=0.65, length=0.1),
    delete= dict(modes=[(540, 1.0, 0.0065), (1300, 0.28, 0.0032), (2600, 0.08, 0.0016)],
                 noise_amp=0.22, noise_tau=0.0011, noise_band=(1300, 5500), body=(135, 0.9, 0.006, 0.8), peak=0.58),
    enter = dict(modes=[(380, 1.0, 0.0110), (900, 0.35, 0.0060), (1900, 0.10, 0.0028)],
                 noise_amp=0.22, noise_tau=0.0020, noise_band=(900, 4500), body=(95, 1.0, 0.012, 0.9), peak=0.7, length=0.12),
  ),
}

out = {}
for pack, sounds in packs.items():
    for kind, p in sounds.items():
        variants = [(1.0, 1), (1.035, 2), (0.965, 3)] if kind == 'key' else [(1.0, 1)]
        for idx, (pitch, seed) in enumerate(variants, 1):
            x = tap(pitch=pitch, seed=seed, **p)
            name = f'keysound_{pack}_{kind}' + (f'_{idx}' if kind == 'key' else '') + '.wav'
            write(name, x); out[name] = x

# preview: someone typing "hey whats up" ⌫⌫ "p" ⏎, in each pack
def preview(pack):
    rng = np.random.default_rng(7)
    seq = list('hey whats up') + ['del', 'del'] + list('up') + ['enter']
    total = np.zeros(int(SR * 4.0)); pos = int(0.2 * SR)
    for c in seq:
        if c == 'enter': s = out[f'keysound_{pack}_enter.wav']
        elif c == 'del': s = out[f'keysound_{pack}_delete.wav']
        elif c == ' ': s = out[f'keysound_{pack}_space.wav']
        else: s = out[f'keysound_{pack}_key_{rng.integers(1,4)}.wav']
        total[pos:pos+len(s)] += s
        pos += int(rng.uniform(0.11, 0.2) * SR)
    return total[:pos + int(0.3*SR)]
gap = np.zeros(int(0.6*SR))
write('preview.wav', np.concatenate([preview('soft'), gap, preview('thock')]))
print(sorted(out))
