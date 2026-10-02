# Builds the "clicky" pack from OpenClickSound (https://github.com/Nigh/OpenClickSound, CC BY-NC 4.0, by Nigh)
import subprocess, numpy as np, wave, sys
SRC = sys.argv[1]; OUT = sys.argv[2]; SR = 44100
def load(name):
    raw = subprocess.run(['ffmpeg','-v','error','-i',f'{SRC}/{name}.wav','-f','f32le','-ac','1','-'],capture_output=True,check=True).stdout
    return np.frombuffer(raw, dtype='<f4').astype(float)
def trim(x, tail_ms, peak):
    env = np.abs(x); pk = env.max()
    act = np.where(env > pk * 0.03)[0]
    s = max(0, act[0] - int(0.001 * SR)); e = min(len(x), act[-1] + int(tail_ms / 1000 * SR))
    y = x[s:e].copy()
    y -= np.mean(y[:max(1, int(0.001*SR))])  # remove offset
    fi = int(0.0008 * SR); y[:fi] *= np.linspace(0, 1, fi)
    fo = min(len(y)//3, int(0.012 * SR)); y[-fo:] *= np.linspace(1, 0, fo)
    return y / np.max(np.abs(y)) * peak
def write(name, y):
    with wave.open(f'{OUT}/{name}.wav','wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(y,-1,1)*32767).astype('<i2').tobytes())
for i, src in enumerate(['kt01-press-00-01','kt01-press-00-03','kt01-press-00-04','kt01-press-00-06'], 1):
    write(f'keysound_clicky_key_{i}', trim(load(src), 25, 0.5))
write('keysound_clicky_space', trim(load('kt01-click-03-02'), 25, 0.55))
write('keysound_clicky_enter', trim(load('kt01-click-05-02'), 25, 0.6))
write('keysound_clicky_delete', trim(load('kt01-press-00-02'), 25, 0.42))
