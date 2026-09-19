package ru.arthaix.meshimporter.collision;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.AxisAlignedBB;

/**
 * World-space triangle soup of one placed model, bucketed into 4-block cells, for collision boxes, ray casts and
 * sky occlusion. Coordinates are kept relative to the instance anchor (float precision is fine there); the anchor
 * is added when talking to the world.
 *
 * Collision: vanilla physics only understands axis-aligned boxes, so every triangle that touches the query box is
 * turned into thin slabs on a {@value #SLAB} block raster. Floors and ceilings become horizontal slabs that follow
 * the surface height, walls become vertical slabs. The player's step height (0.6) walks up the raster like up a very
 * fine staircase, so slopes feel smooth.
 *
 * The geometry is immutable. Queries need scratch space: the game thread uses the built-in one, other threads
 * create their own with {@link #newQuery()}.
 */
public final class TriangleGrid {

    public static final int CELL_SHIFT = 2;
    public static final double SLAB = 0.25;

    /** Scratch space of one thread. */
    public static final class Query {
        final int[] stamp;
        int counter;
        final double[] poly = new double[24], polyTmp = new double[24];
        final double[] p = new double[9], n = new double[3], q0 = new double[3], q1 = new double[3], lo = new double[3], hi = new double[3];

        Query(int triangles) {
            stamp = new int[triangles];
        }

        int next() {
            if (++counter == Integer.MAX_VALUE) {
                Arrays.fill(stamp, 0);
                counter = 1;
            }
            return counter;
        }
    }

    public static final class Hit {
        /** Fraction of the segment (0..1). */
        public double t;
        public double x, y, z;
        public double nx, ny, nz;
        public int triangle;
    }

    /** 9 floats per triangle: ax ay az bx by bz cx cy cz (relative to the anchor). */
    private final float[] v;
    private final int triCount;
    private final double ax, ay, az;
    private final double[] bounds;

    private final long[] cellKeys;
    private final int[] cellStart;
    private final int[] cellTris;

    private final Query main;

    /** @param localTriangles 9 floats per triangle relative to the anchor */
    public TriangleGrid(float[] localTriangles, int triCount, double anchorX, double anchorY, double anchorZ) {
        this.v = localTriangles;
        this.triCount = triCount;
        this.ax = anchorX;
        this.ay = anchorY;
        this.az = anchorZ;
        double[] b = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        // cell → [count, t0, t1, ...]
        Map<Long, int[]> cells = new HashMap<>();
        for (int t = 0; t < triCount; t++) {
            int o = 9 * t;
            float minX = min3(v[o], v[o + 3], v[o + 6]), maxX = max3(v[o], v[o + 3], v[o + 6]);
            float minY = min3(v[o + 1], v[o + 4], v[o + 7]), maxY = max3(v[o + 1], v[o + 4], v[o + 7]);
            float minZ = min3(v[o + 2], v[o + 5], v[o + 8]), maxZ = max3(v[o + 2], v[o + 5], v[o + 8]);
            b[0] = Math.min(b[0], minX);
            b[1] = Math.min(b[1], minY);
            b[2] = Math.min(b[2], minZ);
            b[3] = Math.max(b[3], maxX);
            b[4] = Math.max(b[4], maxY);
            b[5] = Math.max(b[5], maxZ);
            int cx0 = cell(minX), cx1 = cell(maxX), cy0 = cell(minY), cy1 = cell(maxY), cz0 = cell(minZ), cz1 = cell(maxZ);
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cy = cy0; cy <= cy1; cy++)
                    for (int cz = cz0; cz <= cz1; cz++) {
                        long key = key(cx, cy, cz);
                        int[] arr = cells.get(key);
                        if (arr == null) {
                            arr = new int[5];
                            cells.put(key, arr);
                        } else if (arr[0] + 1 == arr.length) {
                            arr = Arrays.copyOf(arr, arr.length * 2);
                            cells.put(key, arr);
                        }
                        arr[++arr[0]] = t;
                    }
        }
        this.bounds = triCount == 0 ? new double[6] : b;
        int nc = cells.size();
        cellKeys = new long[nc];
        int i = 0;
        for (Long k : cells.keySet()) cellKeys[i++] = k;
        Arrays.sort(cellKeys);
        cellStart = new int[nc + 1];
        int total = 0;
        for (int c = 0; c < nc; c++) {
            cellStart[c] = total;
            total += cells.get(cellKeys[c])[0];
        }
        cellStart[nc] = total;
        cellTris = new int[total];
        for (int c = 0; c < nc; c++) {
            int[] arr = cells.get(cellKeys[c]);
            System.arraycopy(arr, 1, cellTris, cellStart[c], arr[0]);
        }
        main = new Query(triCount);
    }

    public Query newQuery() {
        return new Query(triCount);
    }

    public int triangleCount() {
        return triCount;
    }

    public AxisAlignedBB worldBounds() {
        return new AxisAlignedBB(bounds[0] + ax, bounds[1] + ay, bounds[2] + az, bounds[3] + ax, bounds[4] + ay, bounds[5] + az);
    }

    // ---- collision (game thread) ----

    /** Appends collision boxes (world coordinates) for every triangle touching the world-space query box. */
    public void collectBoxes(AxisAlignedBB query, List<AxisAlignedBB> out) {
        Query q = main;
        double qx0 = query.minX - ax, qy0 = query.minY - ay, qz0 = query.minZ - az;
        double qx1 = query.maxX - ax, qy1 = query.maxY - ay, qz1 = query.maxZ - az;
        if (qx1 < bounds[0] || qx0 > bounds[3] || qy1 < bounds[1] || qy0 > bounds[4] || qz1 < bounds[2] || qz0 > bounds[5]) return;
        int s = q.next();
        int cx0 = cell(qx0), cx1 = cell(qx1), cy0 = cell(qy0), cy1 = cell(qy1), cz0 = cell(qz0), cz1 = cell(qz1);
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cz = cz0; cz <= cz1; cz++) {
                    int c = findCell(key(cx, cy, cz));
                    if (c < 0) continue;
                    for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                        int t = cellTris[k];
                        if (q.stamp[t] == s) continue;
                        q.stamp[t] = s;
                        slabs(q, t, qx0, qy0, qz0, qx1, qy1, qz1, out);
                    }
                }
    }

    private void slabs(Query q, int t, double qx0, double qy0, double qz0, double qx1, double qy1, double qz1, List<AxisAlignedBB> out) {
        int o = 9 * t;
        double[] p = q.p;
        for (int i = 0; i < 9; i++) p[i] = v[o + i];
        if (max3(p[0], p[3], p[6]) < qx0 || min3(p[0], p[3], p[6]) > qx1 || max3(p[1], p[4], p[7]) < qy0 || min3(p[1], p[4], p[7]) > qy1
            || max3(p[2], p[5], p[8]) < qz0 || min3(p[2], p[5], p[8]) > qz1) return;
        double nx = (p[4] - p[1]) * (p[8] - p[2]) - (p[5] - p[2]) * (p[7] - p[1]);
        double ny = (p[5] - p[2]) * (p[6] - p[0]) - (p[3] - p[0]) * (p[8] - p[2]);
        double nz = (p[3] - p[0]) * (p[7] - p[1]) - (p[4] - p[1]) * (p[6] - p[0]);
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12) return;
        double[] n = q.n;
        n[0] = nx / len;
        n[1] = ny / len;
        n[2] = nz / len;
        // d: the slab's thin direction (vertical for floors and slopes up to 60°, horizontal for walls)
        int d;
        if (Math.abs(n[1]) >= 0.5) d = 1;
        else d = Math.abs(n[0]) >= Math.abs(n[2]) ? 0 : 2;
        int ua = d == 0 ? 1 : 0, wa = d == 2 ? 1 : 2;
        double pd = -(n[0] * p[0] + n[1] * p[1] + n[2] * p[2]);
        double[] q0 = q.q0, q1 = q.q1;
        q0[0] = qx0;
        q0[1] = qy0;
        q0[2] = qz0;
        q1[0] = qx1;
        q1[1] = qy1;
        q1[2] = qz1;
        double uMin = Math.max(q0[ua], min3(p[ua], p[3 + ua], p[6 + ua])), uMax = Math.min(q1[ua], max3(p[ua], p[3 + ua], p[6 + ua]));
        double wMin = Math.max(q0[wa], min3(p[wa], p[3 + wa], p[6 + wa])), wMax = Math.min(q1[wa], max3(p[wa], p[3 + wa], p[6 + wa]));
        if (uMin > uMax || wMin > wMax) return;
        int iu0 = (int) Math.floor(uMin / SLAB), iu1 = (int) Math.floor(uMax / SLAB);
        int iw0 = (int) Math.floor(wMin / SLAB), iw1 = (int) Math.floor(wMax / SLAB);
        double[] lo = q.lo, hi = q.hi;
        for (int iu = iu0; iu <= iu1; iu++) {
            double cu0 = iu * SLAB, cu1 = cu0 + SLAB;
            for (int iw = iw0; iw <= iw1; iw++) {
                double cw0 = iw * SLAB, cw1 = cw0 + SLAB;
                int cnt = clip(q, p, ua, wa, cu0, cw0, cu1, cw1);
                if (cnt < 3) continue;
                double dMin = Double.POSITIVE_INFINITY, dMax = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < cnt; i++) {
                    double u = q.poly[2 * i], w = q.poly[2 * i + 1];
                    double dv = -(n[ua] * u + n[wa] * w + pd) / n[d];
                    if (dv < dMin) dMin = dv;
                    if (dv > dMax) dMax = dv;
                }
                if (dMax < q0[d] || dMin > q1[d]) continue;
                if (dMax - dMin < 0.02) {
                    double mid = (dMin + dMax) / 2;
                    dMin = mid - 0.01;
                    dMax = mid + 0.01;
                }
                lo[ua] = Math.max(cu0, uMin);
                hi[ua] = Math.min(cu1, uMax);
                lo[wa] = Math.max(cw0, wMin);
                hi[wa] = Math.min(cw1, wMax);
                lo[d] = dMin;
                hi[d] = dMax;
                out.add(new AxisAlignedBB(lo[0] + ax, lo[1] + ay, lo[2] + az, hi[0] + ax, hi[1] + ay, hi[2] + az));
            }
        }
    }

    /** Sutherland–Hodgman: triangle (projected on axes ua, wa) ∩ rectangle → q.poly; returns the vertex count. */
    private static int clip(Query q, double[] p, int ua, int wa, double u0, double w0, double u1, double w1) {
        double[] in = q.poly, tmp = q.polyTmp;
        int n = 3;
        for (int i = 0; i < 3; i++) {
            in[2 * i] = p[3 * i + ua];
            in[2 * i + 1] = p[3 * i + wa];
        }
        for (int edge = 0; edge < 4 && n > 0; edge++) {
            int m = 0;
            for (int i = 0; i < n; i++) {
                double cuA = in[2 * i], cwA = in[2 * i + 1];
                int j = (i + 1) % n;
                double cuB = in[2 * j], cwB = in[2 * j + 1];
                boolean inA = inside(edge, cuA, cwA, u0, w0, u1, w1), inB = inside(edge, cuB, cwB, u0, w0, u1, w1);
                if (inA) {
                    tmp[2 * m] = cuA;
                    tmp[2 * m + 1] = cwA;
                    m++;
                }
                if (inA != inB) {
                    double s = edgeParam(edge, cuA, cwA, cuB, cwB, u0, w0, u1, w1);
                    tmp[2 * m] = cuA + (cuB - cuA) * s;
                    tmp[2 * m + 1] = cwA + (cwB - cwA) * s;
                    m++;
                }
            }
            double[] swap = in;
            in = tmp;
            tmp = swap;
            n = m;
        }
        if (in != q.poly) System.arraycopy(in, 0, q.poly, 0, 2 * n);
        return n;
    }

    private static boolean inside(int edge, double u, double w, double u0, double w0, double u1, double w1) {
        switch (edge) {
            case 0: return u >= u0;
            case 1: return u <= u1;
            case 2: return w >= w0;
            default: return w <= w1;
        }
    }

    private static double edgeParam(int edge, double ua, double wa, double ub, double wb, double u0, double w0, double u1, double w1) {
        switch (edge) {
            case 0: return (u0 - ua) / (ub - ua);
            case 1: return (u1 - ua) / (ub - ua);
            case 2: return (w0 - wa) / (wb - wa);
            default: return (w1 - wa) / (wb - wa);
        }
    }

    // ---- rays ----

    /** Closest hit of the world-space segment from → to, or null (game thread). */
    public Hit raycast(double fx, double fy, double fz, double tx, double ty, double tz) {
        return raycast(main, fx, fy, fz, tx, ty, tz);
    }

    public Hit raycast(Query q, double fx, double fy, double fz, double tx, double ty, double tz) {
        fx -= ax;
        fy -= ay;
        fz -= az;
        tx -= ax;
        ty -= ay;
        tz -= az;
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double t0 = 0, t1 = 1;
        double[] f = { fx, fy, fz }, dir = { dx, dy, dz };
        for (int a = 0; a < 3; a++) {
            double lo = bounds[a] - 0.01, hi = bounds[a + 3] + 0.01;
            if (Math.abs(dir[a]) < 1e-12) {
                if (f[a] < lo || f[a] > hi) return null;
                continue;
            }
            double ta = (lo - f[a]) / dir[a], tb = (hi - f[a]) / dir[a];
            if (ta > tb) {
                double s = ta;
                ta = tb;
                tb = s;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return null;
        }
        int s = q.next();
        double bestT = Double.POSITIVE_INFINITY;
        int bestTri = -1;
        double px = fx + dx * t0, py = fy + dy * t0, pz = fz + dz * t0;
        int cx = cell(px), cy = cell(py), cz = cell(pz);
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0, stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0, stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double size = 1 << CELL_SHIFT;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? (cx + 1) * size : cx * size) - px) / dx + t0;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? (cy + 1) * size : cy * size) - py) / dy + t0;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? (cz + 1) * size : cz * size) - pz) / dz + t0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(size / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(size / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(size / dz);
        for (int guard = 0; guard < 65536; guard++) {
            int c = findCell(key(cx, cy, cz));
            if (c >= 0) {
                for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                    int t = cellTris[k];
                    if (q.stamp[t] == s) continue;
                    q.stamp[t] = s;
                    double hit = intersect(t, fx, fy, fz, dx, dy, dz);
                    if (hit >= 0 && hit <= t1 && hit < bestT) {
                        bestT = hit;
                        bestTri = t;
                    }
                }
            }
            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (bestTri >= 0 && bestT <= next) break;
            if (next > t1) break;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                cx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                cy += stepY;
                tMaxY += tDeltaY;
            } else {
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        if (bestTri < 0) return null;
        Hit best = new Hit();
        best.t = bestT;
        best.triangle = bestTri;
        best.x = fx + dx * bestT + ax;
        best.y = fy + dy * bestT + ay;
        best.z = fz + dz * bestT + az;
        int o = 9 * bestTri;
        double nx = (v[o + 4] - v[o + 1]) * (v[o + 8] - v[o + 2]) - (v[o + 5] - v[o + 2]) * (v[o + 7] - v[o + 1]);
        double ny = (v[o + 5] - v[o + 2]) * (v[o + 6] - v[o]) - (v[o + 3] - v[o]) * (v[o + 8] - v[o + 2]);
        double nz = (v[o + 3] - v[o]) * (v[o + 7] - v[o + 1]) - (v[o + 4] - v[o + 1]) * (v[o + 6] - v[o]);
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        if (nx * dx + ny * dy + nz * dz > 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        best.nx = nx;
        best.ny = ny;
        best.nz = nz;
        return best;
    }

    /** Möller–Trumbore, both sides; returns the ray parameter or -1. */
    private double intersect(int t, double ox, double oy, double oz, double dx, double dy, double dz) {
        int o = 9 * t;
        double e1x = v[o + 3] - v[o], e1y = v[o + 4] - v[o + 1], e1z = v[o + 5] - v[o + 2];
        double e2x = v[o + 6] - v[o], e2y = v[o + 7] - v[o + 1], e2z = v[o + 8] - v[o + 2];
        double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12) return -1;
        double inv = 1 / det;
        double tx = ox - v[o], ty = oy - v[o + 1], tz = oz - v[o + 2];
        double u = (tx * px + ty * py + tz * pz) * inv;
        if (u < -1e-6 || u > 1 + 1e-6) return -1;
        double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        double w = (dx * qx + dy * qy + dz * qz) * inv;
        if (w < -1e-6 || u + w > 1 + 1e-6) return -1;
        double r = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return r < 0 ? -1 : r;
    }

    /**
     * True when some part of this mesh lies straight above the world point. A vertical ray only needs the triangles
     * whose top-down footprint contains the point, so each candidate is rejected by its x/z extent first and the
     * search stops at the first surface above. Walls (no footprint) never count.
     */
    public boolean occludedAbove(Query q, double x, double y, double z) {
        double lx = x - ax, ly = y - ay, lz = z - az;
        if (lx < bounds[0] || lx > bounds[3] || lz < bounds[2] || lz > bounds[5] || ly >= bounds[4]) return false;
        int cx = cell(lx), cz = cell(lz);
        int cy0 = cell(Math.max(ly, bounds[1])), cy1 = cell(bounds[4]);
        for (int cy = cy0; cy <= cy1; cy++) {
            int ci = findCell(key(cx, cy, cz));
            if (ci < 0) continue;
            for (int k = cellStart[ci]; k < cellStart[ci + 1]; k++) {
                int o = 9 * cellTris[k];
                float x0 = v[o], z0 = v[o + 2], x1 = v[o + 3], z1 = v[o + 5], x2 = v[o + 6], z2 = v[o + 8];
                if (lx < min3(x0, x1, x2) || lx > max3(x0, x1, x2) || lz < min3(z0, z1, z2) || lz > max3(z0, z1, z2)) continue;
                float y0 = v[o + 1], y1 = v[o + 4], y2 = v[o + 7];
                if (ly >= max3(y0, y1, y2)) continue;
                double d = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2);
                if (Math.abs(d) < 1e-9) continue;
                double a = ((z1 - z2) * (lx - x2) + (x2 - x1) * (lz - z2)) / d;
                double b = ((z2 - z0) * (lx - x2) + (x0 - x2) * (lz - z2)) / d;
                double c = 1 - a - b;
                if (a < -1e-6 || b < -1e-6 || c < -1e-6) continue;
                if (a * y0 + b * y1 + c * y2 > ly) return true;
            }
        }
        return false;
    }

    // ---- helpers ----

    private static int cell(double c) {
        return ((int) Math.floor(c)) >> CELL_SHIFT;
    }

    private static long key(int cx, int cy, int cz) {
        return (((long) cx & 0x1FFFFF) << 42) | (((long) cy & 0x1FFFFF) << 21) | ((long) cz & 0x1FFFFF);
    }

    private int findCell(long key) {
        return Arrays.binarySearch(cellKeys, key);
    }

    private static float min3(float a, float b, float c) {
        return Math.min(a, Math.min(b, c));
    }

    private static float max3(float a, float b, float c) {
        return Math.max(a, Math.max(b, c));
    }

    private static double min3(double a, double b, double c) {
        return Math.min(a, Math.min(b, c));
    }

    private static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }
}
