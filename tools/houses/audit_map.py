"""Audit of houses.bin against the Bijnor map (bijnor.bin) and itself.  python3 tools/houses/audit_map.py   -> prints FAIL lines and a verdict."""
import os, struct, sys, math
import numpy as np
from scipy import ndimage
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from mapio import read_bin, road_mask
ROOT = os.path.dirname(os.path.dirname(HERE))
mp = read_bin(os.path.join(ROOT, 'app/src/main/assets/maps/bijnor.bin'))
b = open(os.path.join(ROOT, 'app/src/main/assets/models/houses/houses.bin'), 'rb').read()
n, cell, x0, gh = mp['n'], mp['cell'], mp['x0'], mp['gh']
RC = 2.5; mk = road_mask(mp, RC); dist = ndimage.distance_transform_edt(mk == 0) * RC
fails = []
def F(msg): fails.append(msg); print('FAIL', msg)
def road_d(x, z):
    i = int(round((x - x0) / RC)); k = int(round((z - x0) / RC))
    return 0.0 if (i < 0 or k < 0 or i >= mk.shape[1] or k >= mk.shape[0]) else float(dist[k, i])
def ground(x, z):
    fx = min(max((x - x0) / cell, 0), n - 1.001); fz = min(max((z - x0) / cell, 0), n - 1.001); i, k = int(fx), int(fz); tx, tz = fx - i, fz - k
    return gh[k, i] * (1 - tx) * (1 - tz) + gh[k, i + 1] * tx * (1 - tz) + gh[k + 1, i] * (1 - tx) * tz + gh[k + 1, i + 1] * tx * tz
assert b[:4] == b'BJH1'; o = 4; nh, = struct.unpack_from('<i', b, o); o += 4
if nh < 7: F('expected at least 7 houses, got %d' % nh)
H = []
for h in range(nh):
    minX, maxX, minZ, maxZ, y0, cx, cz = struct.unpack_from('<7f', b, o); o += 28
    nv, ni, nb, ns, nl = struct.unpack_from('<5i', b, o); o += 20
    V = np.frombuffer(b, '<f4', nv * 11, o).reshape(-1, 11); o += nv * 44
    I = np.frombuffer(b, '<i4', ni, o); o += ni * 4
    B = np.frombuffer(b, '<f4', nb * 6, o).reshape(-1, 6); o += nb * 24
    S = np.frombuffer(b, '<f4', ns * 18, o).reshape(-1, 18); o += ns * 72
    L = np.frombuffer(b, '<f4', nl * 3, o).reshape(-1, 3); o += nl * 12
    H.append((minX, maxX, minZ, maxZ, y0, cx, cz, nl)); t = 'house %d: ' % (h + 1)
    if not np.isfinite(V).all(): F(t + 'NaN/inf in vertices')
    if ni % 3 or (ni and (I.min() < 0 or I.max() >= nv)): F(t + 'bad indices')
    if not np.isfinite(B).all() or (B[:, 0] > B[:, 1]).any() or (B[:, 2] > B[:, 3]).any() or (B[:, 4] > B[:, 5]).any(): F(t + 'bad collision box')
    if not np.isfinite(S).all(): F(t + 'bad surface')
    if V[:, 0].min() < minX or V[:, 0].max() > maxX or V[:, 2].min() < minZ or V[:, 2].max() > maxZ: F(t + 'mesh outside its footprint box')
    if nl == 0 and (len(B) < 1 or len(S) < 1): F(t + 'plain building without box / roof')
    if abs(ground(cx, cz) - y0) > 0.02: F(t + 'ground %.2f != y0 %.2f' % (ground(cx, cz), y0))
    # footprint corners + samples: flat dry land, clear of the road
    worst = 1e9; flat = 0
    for x in np.arange(minX - 6, maxX + 6.1, 2.0):
        for z in np.arange(minZ - 6, maxZ + 6.1, 2.0):
            flat = max(flat, abs(ground(x, z) - y0))
            if minX <= x <= maxX and minZ <= z <= maxZ: worst = min(worst, road_d(x, z))
    if flat > 0.05: F(t + 'ground not flat within 6 m (%.2f m)' % flat)
    if worst < 6.0 and nl > 0: F(t + 'road only %.1f m from the footprint' % worst)
    # loot: inside the footprint, not inside any wall / furniture box (player r 0.42), on a floor surface
    for p in L:
        if not (minX < p[0] < maxX and minZ < p[2] < maxZ): F(t + 'loot outside footprint %s' % np.round(p, 1)); continue
        for a in B:
            if p[0] > a[0] - .42 and p[0] < a[1] + .42 and p[2] > a[4] - .42 and p[2] < a[5] + .42 and p[1] < a[3] - .3 and p[1] + 1.7 > a[2]: F(t + 'loot inside a blocker %s' % np.round(p, 1)); break
        if not any(int(s[16]) & 1 and s[0] <= p[0] <= s[1] and s[2] <= p[2] <= s[3] and min(s[4 + q * 3] * p[0] + s[5 + q * 3] * p[2] + s[6 + q * 3] for q in range(4)) <= p[1] + 0.05 for s in S): F(t + 'loot floats without a floor %s' % np.round(p, 1))
    print('house', h + 1, 'ok-checked: loot', nl, 'min road dist %.1f m' % worst)
ENT = [i for i in range(nh) if True]
for i in range(nh):
    for j in range(i + 1, nh):
        if H[i][7] == 0 or H[j][7] == 0: continue
        d = math.hypot(H[i][5] - H[j][5], H[i][6] - H[j][6])
        if d < 50: F('houses %d and %d only %.0f m apart' % (i + 1, j + 1, d))
sp = mp['spawn']
for i, h in enumerate(H):
    if h[0] - 30 < sp[0] < h[1] + 30 and h[2] - 30 < sp[2] < h[3] + 30: F('house %d within 30 m of the spawn point' % (i + 1))
if o != len(b): F('houses.bin has %d unread bytes' % (len(b) - o))
print('AUDIT', 'FAILED %d' % len(fails) if fails else 'OK')
