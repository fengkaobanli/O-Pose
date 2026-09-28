#!/usr/bin/env python3
# SpazPeek 标定拟合脚本 (v1, 2026-09-25)
# 输入: calib_v2/v3 日志 (PHONE行=手机真值, DATA行=耳机输出)
# 输出: "耳机输出 → 真实头部姿态" 线性回归系数 + 延迟扫描
# 用法: python3 fit_regression.py <logfile>

import re, math, sys

def parse(path):
    phone = []; htd = []
    for line in open(path, errors='ignore'):
        m = re.match(r'\d\d-\d\d (\d+):(\d+):([\d.]+) \[PHONE\] yawW=([+-][\d.]+) pitchW=([+-][\d.]+) rollW=([+-][\d.]+)', line)
        if m:
            t = int(m.group(1))*3600 + int(m.group(2))*60 + float(m.group(3))
            phone.append((t, float(m.group(4)), float(m.group(5)), float(m.group(6))))
            continue
        m2 = re.match(r'\d\d-\d\d (\d+):(\d+):([\d.]+) \[DATA\].*pitch=([+-][\d.]+) yaw=([+-][\d.]+)', line)
        if m2:
            t = int(m2.group(1))*3600 + int(m2.group(2))*60 + float(m2.group(3))
            htd.append((t, float(m2.group(4)), float(m2.group(5))))
    return phone, htd

def smooth(vals, w=6):
    out = []
    for i in range(len(vals)):
        lo = max(0, i-w//2); hi = min(len(vals), i+w//2+1)
        out.append(sum(vals[lo:hi])/(hi-lo))
    return out

def corr(xs, ys):
    n = len(xs)
    if n < 5: return 0.0
    mx = sum(xs)/n; my = sum(ys)/n
    sx = math.sqrt(sum((x-mx)**2 for x in xs)); sy = math.sqrt(sum((y-my)**2 for y in ys))
    if sx == 0 or sy == 0: return 0.0
    return sum((xs[i]-mx)*(ys[i]-my) for i in range(n))/(sx*sy)

def det3(m):
    return (m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1])
           -m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0])
           +m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0]))

def lsq(P, yidx):
    # y = a*hp + b*hy + c   (x1=耳机pitch, x2=耳机yaw)
    n = len(P)
    x1 = [p[3] for p in P]; x2 = [p[4] for p in P]; yv = [p[yidx] for p in P]
    S11 = sum(x*x for x in x1); S12 = sum(x1[i]*x2[i] for i in range(n)); S22 = sum(x*x for x in x2)
    Sy1 = sum(x1[i]*yv[i] for i in range(n)); Sy2 = sum(x2[i]*yv[i] for i in range(n))
    S1 = sum(x1); S2 = sum(x2); Sy = sum(yv)
    A = [[S11,S12,S1],[S12,S22,S2],[S1,S2,float(n)]]
    B = [Sy1,Sy2,Sy]
    D = det3(A)
    if abs(D) < 1e-9: return None
    def detrep(col):
        M = [row[:] for row in A]
        for r in range(3): M[r][col] = B[r]
        return det3(M)
    a, b, c = detrep(0)/D, detrep(1)/D, detrep(2)/D
    my = Sy/n
    sst = sum((v-my)**2 for v in yv)
    pred = [a*x1[i]+b*x2[i]+c for i in range(n)]
    sse = sum((yv[i]-pred[i])**2 for i in range(n))
    r2 = 1-sse/sst if sst > 0 else 0
    return a, b, c, r2

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else '/sdcard/Download/fit_v3.log'
    phone, htd = parse(path)
    print('PHONE=%d DATA=%d' % (len(phone), len(htd)))
    if not phone or not htd: return

    pt = [p[0] for p in phone]
    sm = [smooth([p[1] for p in phone]), smooth([p[2] for p in phone]), smooth([p[3] for p in phone])]
    ht = [h[0] for h in htd]
    hp = smooth([h[1] for h in htd])  # 耳机pitch
    hy = smooth([h[2] for h in htd])  # 耳机yaw

    # 活跃段：|任一部手机轴|>30 且合并间隔
    segs_raw = []; cur = None
    for t, y, p, r in phone:
        if max(abs(y), abs(p), abs(r)) > 30:
            if cur is None: cur = [t, t]
            else: cur[1] = t
        else:
            if cur: segs_raw.append(tuple(cur)); cur = None
    if cur: segs_raw.append(tuple(cur))
    segs = []
    for s in segs_raw:
        if segs and s[0]-segs[-1][1] < 2.5:
            segs[-1] = (segs[-1][0], s[1])
        else:
            segs.append(s)
    segs = [s for s in segs if s[1]-s[0] > 2.0]
    print('活跃段:', [('%.0f~%.0f' % s) for s in segs])

    # 延迟扫描
    best = None; bestscore = 0
    for k in range(-5, 16):
        lag = k*0.02
        r1s = []; r2s = []
        for t0, t1 in segs:
            for j, th in enumerate(ht):
                tt = th+lag
                if not (t0 <= tt <= t1): continue
                i = min(range(len(pt)), key=lambda q: abs(pt[q]-tt))
                if abs(pt[i]-tt) < 0.3:
                    r1s.append((sm[1][i], hy[j]))   # 手机pitchW <> 耳机yaw
                    r2s.append((sm[0][i], hp[j]))   # 手机yawW <> 耳机pitch
        if len(r1s) >= 10 and len(r2s) >= 10:
            r1 = abs(corr([a for a,b in r1s], [b for a,b in r1s]))
            r2 = abs(corr([a for a,b in r2s], [b for a,b in r2s]))
            sc = (r1+r2)/2
            if sc > bestscore: bestscore = sc; best = (lag, r1, r2)
    print('最优lag=%.2fs |r|左右=%.3f 上下=%.3f' % best)
    lag = best[0]

    P = []
    for t0, t1 in segs:
        for j, th in enumerate(ht):
            tt = th+lag
            if not (t0 <= tt <= t1): continue
            i = min(range(len(pt)), key=lambda q: abs(pt[q]-tt))
            if abs(pt[i]-tt) < 0.3:
                P.append((sm[0][i], sm[1][i], sm[2][i], hp[j], hy[j]))
    print('配对点数:', len(P))

    print()
    print('=== 回归: y = a*耳机pitch + b*耳机yaw + c ===')
    for nm, idx in [('左右(pitchW)', 1), ('上下(yawW)', 0), ('倾斜(rollW)', 2)]:
        r = lsq(P, idx)
        if r:
            a, b, c, r2 = r
            print('%s = %.4f*耳机pitch + %.4f*耳机yaw + %.2f   R2=%.3f' % (nm, a, b, c, r2))

if __name__ == '__main__':
    main()