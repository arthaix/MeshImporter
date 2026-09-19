package ru.arthaix.meshimporter.light;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Sky light of a mesh, baked by ray casting. The mesh is rasterised into quarter-block sub-voxels (opaque and glass
 * separately). From every block cell near a surface 64 rays go out (the direction set is randomly rotated per cell,
 * so thin geometry gives fine noise instead of blotches, which a filter over connected neighbour cells then removes);
 * a ray that leaves the mesh sees sky, glass lets part of it through, anything else stops it. For each of the six axis directions the cell stores how much sky a
 * surface facing that way sees (an "ambient cube"), plus a soft bounce term so rooms lit through windows are not
 * pitch black. Surfaces then read the six values with their normal, trilinearly between cells, per pixel or per
 * vertex.
 *
 * Coordinates are anchor-relative blocks, like {@code LoadedInstance.local}. Storage is sparse (16³-block sections).
 * Read-only after construction.
 */
public final class LightBake {

    public static final int SUB = 4;
    private static final int DIRS = 64;
    private static final int NEAR = 2;

    public static double rayLength = 48;
    public static double glassTransmission = 0.8;
    public static double bounceStrength = 0.55;
    public static int bounceIterations = 6;
    /**
     * Interpolation weight (of 255) of a cell whose centre is inside geometry. Its light is a guess, so it only counts
     * where no cell in free space is near; otherwise light under a floor slab would bleed onto the foot of walls.
     */
    public static final int BLOCKED_ALPHA = 4;
    public static int smoothIterations = 2;
    /**
     * Side faces: weight of rays that go below the horizon. Sky light comes from above, so an open view downwards
     * (under the model, off a balcony) counts only a little and light does not creep up from under a floor slab.
     * Open space still gives full light because every face is normalised by its own weights.
     */
    public static double groundWeight = 0.15;
    /** Smoothing weight by neighbour kind: self, face, edge, corner. */
    private static final double[] SMOOTH_WEIGHT = { 1.0, 0.6, 0.35, 0.2 };

    /** Block coordinates of cell (0, 0, 0). */
    public final int originX, originY, originZ;
    /** Size in blocks. */
    public final int nx, ny, nz;
    private final int snx, sny, snz;
    private final AtomicLongArray[] opaque, glass;
    /** Per section: 6 bytes per cell (+X -X +Y -Y +Z -Z), 0..255. */
    private final byte[][] faces;
    /** Per section: bit 0 = baked, bit 7 = centre inside geometry, bits 1..6 = face undefined. */
    private final byte[][] state;

    public final long millis;
    public final int sampleCells;
    public final long raysCast;

    private static final double[] DIR = new double[3 * DIRS];

    static {
        for (int i = 0; i < DIRS; i++) {
            double y = 1 - 2 * (i + 0.5) / DIRS, r = Math.sqrt(1 - y * y), phi = i * Math.PI * (3 - Math.sqrt(5));
            DIR[3 * i] = Math.cos(phi) * r;
            DIR[3 * i + 1] = y;
            DIR[3 * i + 2] = Math.sin(phi) * r;
        }
    }

    /**
     * @param local       x, y, z per vertex (anchor-relative)
     * @param indices     three vertex indices per triangle
     * @param translucent per triangle: lets light through (glass); null = nothing does
     * @param threads     worker threads for rasterising and baking
     * @throws IllegalStateException when the mesh is too large to bake
     */
    public LightBake(float[] local, int[] indices, int triCount, boolean[] translucent, int threads) {
        long t0 = System.currentTimeMillis();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int t = 0; t < triCount; t++)
            for (int c = 0; c < 3; c++) {
                int v = 3 * indices[3 * t + c];
                minX = Math.min(minX, local[v]);
                maxX = Math.max(maxX, local[v]);
                minY = Math.min(minY, local[v + 1]);
                maxY = Math.max(maxY, local[v + 1]);
                minZ = Math.min(minZ, local[v + 2]);
                maxZ = Math.max(maxZ, local[v + 2]);
            }
        if (triCount == 0) minX = minY = minZ = maxX = maxY = maxZ = 0;
        originX = (int) Math.floor(minX) - NEAR - 1;
        originY = (int) Math.floor(minY) - NEAR - 1;
        originZ = (int) Math.floor(minZ) - NEAR - 1;
        nx = (int) Math.ceil(maxX) - originX + NEAR + 2;
        ny = (int) Math.ceil(maxY) - originY + NEAR + 2;
        nz = (int) Math.ceil(maxZ) - originZ + NEAR + 2;
        snx = (nx + 15) >> 4;
        sny = (ny + 15) >> 4;
        snz = (nz + 15) >> 4;
        long sections = (long) snx * sny * snz;
        if (sections > 8_000_000L || (long) nx * SUB >= Integer.MAX_VALUE / 2) throw new IllegalStateException("mesh too large to bake light (" + nx + " x " + ny + " x " + nz + " blocks)");
        opaque = new AtomicLongArray[(int) sections];
        glass = new AtomicLongArray[(int) sections];
        faces = new byte[(int) sections][];
        state = new byte[(int) sections][];

        allocateOccupancy(local, indices, triCount, translucent);
        rasterise(local, indices, triCount, translucent, threads);
        long[] cells = markSampleCells();
        sampleCells = cells.length;
        raysCast = bake(cells, threads);
        smooth(cells, threads);
        bounce(cells);
        fillUndefined(cells);
        millis = System.currentTimeMillis() - t0;
    }

    // ---- occupancy ----

    private int sectionIndex(int bx, int by, int bz) {
        return ((bx >> 4) * sny + (by >> 4)) * snz + (bz >> 4);
    }

    private static int blockIndex(int bx, int by, int bz) {
        return ((bx & 15) << 8) | ((by & 15) << 4) | (bz & 15);
    }

    private void allocateOccupancy(float[] p, int[] idx, int triCount, boolean[] translucent) {
        for (int t = 0; t < triCount; t++) {
            int a = 3 * idx[3 * t], b = 3 * idx[3 * t + 1], c = 3 * idx[3 * t + 2];
            int x0 = (int) Math.floor(min3(p[a], p[b], p[c])) - originX, x1 = (int) Math.floor(max3(p[a], p[b], p[c])) - originX;
            int y0 = (int) Math.floor(min3(p[a + 1], p[b + 1], p[c + 1])) - originY, y1 = (int) Math.floor(max3(p[a + 1], p[b + 1], p[c + 1])) - originY;
            int z0 = (int) Math.floor(min3(p[a + 2], p[b + 2], p[c + 2])) - originZ, z1 = (int) Math.floor(max3(p[a + 2], p[b + 2], p[c + 2])) - originZ;
            AtomicLongArray[] target = translucent != null && translucent[t] ? glass : opaque;
            for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++)
                    for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                        int si = (sx * sny + sy) * snz + sz;
                        if (target[si] == null) target[si] = new AtomicLongArray(4096);
                    }
        }
    }

    private void rasterise(float[] p, int[] idx, int triCount, boolean[] translucent, int threads) {
        AtomicInteger next = new AtomicInteger();
        runParallel(threads, () -> {
            double[] tri = new double[9];
            int from;
            while ((from = next.getAndAdd(4096)) < triCount) {
                int to = Math.min(triCount, from + 4096);
                for (int t = from; t < to; t++) {
                    for (int c = 0; c < 3; c++) {
                        int v = 3 * idx[3 * t + c];
                        tri[3 * c] = (p[v] - originX) * SUB;
                        tri[3 * c + 1] = (p[v + 1] - originY) * SUB;
                        tri[3 * c + 2] = (p[v + 2] - originZ) * SUB;
                    }
                    rasteriseTriangle(tri, translucent != null && translucent[t] ? glass : opaque);
                }
            }
        });
    }

    /** Conservative surface rasterisation on the dominant axis: every sub-voxel column the triangle touches, over the plane's depth range there. */
    private void rasteriseTriangle(double[] tri, AtomicLongArray[] target) {
        double ax = tri[0], ay = tri[1], az = tri[2], bx = tri[3], by = tri[4], bz = tri[5], cx = tri[6], cy = tri[7], cz = tri[8];
        double nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
        double ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
        double nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        double anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
        if (anx + any + anz < 1e-12) return;
        int axis;
        double ua, va, wa, ub, vb, wb, uc, vc, wc, nu, nv, nw;
        if (anx >= any && anx >= anz) {
            axis = 0;
            ua = ay; va = az; wa = ax; ub = by; vb = bz; wb = bx; uc = cy; vc = cz; wc = cx;
            nu = ny; nv = nz; nw = nx;
        } else if (any >= anz) {
            axis = 1;
            ua = az; va = ax; wa = ay; ub = bz; vb = bx; wb = by; uc = cz; vc = cx; wc = cy;
            nu = nz; nv = nx; nw = ny;
        } else {
            axis = 2;
            ua = ax; va = ay; wa = az; ub = bx; vb = by; wb = bz; uc = cx; vc = cy; wc = cz;
            nu = nx; nv = ny; nw = nz;
        }
        double area2 = (ub - ua) * (vc - va) - (vb - va) * (uc - ua);
        if (area2 < 0) {
            double tu = ub, tv = vb, tw = wb;
            ub = uc; vb = vc; wb = wc;
            uc = tu; vc = tv; wc = tw;
        }
        double gu = -nu / nw, gv = -nv / nw;
        double wMin = min3(wa, wb, wc), wMax = max3(wa, wb, wc);
        double e0A = ub - ua, e0B = vb - va, e0C = -e0A * va + e0B * ua;
        double e1A = uc - ub, e1B = vc - vb, e1C = -e1A * vb + e1B * ub;
        double e2A = ua - uc, e2B = va - vc, e2C = -e2A * vc + e2B * uc;
        // grid size of the (u, v, w) axes in sub-voxels (the local normal components shadow the size fields' names)
        int limU = (axis == 0 ? this.ny : axis == 1 ? this.nz : this.nx) * SUB;
        int limV = (axis == 0 ? this.nz : axis == 1 ? this.nx : this.ny) * SUB;
        int limW = (axis == 0 ? this.nx : axis == 1 ? this.ny : this.nz) * SUB;
        int iu0 = Math.max(0, (int) Math.floor(min3(ua, ub, uc))), iu1 = Math.min(limU - 1, (int) Math.floor(max3(ua, ub, uc)));
        int iv0 = Math.max(0, (int) Math.floor(min3(va, vb, vc))), iv1 = Math.min(limV - 1, (int) Math.floor(max3(va, vb, vc)));

        // Narrow triangles (mullions, seals, profile edges: less than a sub-voxel across) are sampled at column centres
        // with a single sub-voxel of depth. Rasterised conservatively, a 5 cm frame would fill a whole 25 cm sub-voxel
        // and a mullioned curtain wall would turn into a solid wall for light.
        double le = Math.max(Math.hypot(ub - ua, vb - va), Math.max(Math.hypot(uc - ub, vc - vb), Math.hypot(ua - uc, va - vc)));
        double height = Math.abs((ub - ua) * (vc - va) - (vb - va) * (uc - ua)) / Math.max(1e-12, le);
        if (height < 1.0) {
            for (int j = iv0; j <= iv1; j++) {
                double pv = j + 0.5;
                for (int i = iu0; i <= iu1; i++) {
                    double pu = i + 0.5;
                    if (e0A * pv - e0B * pu + e0C < -1e-9 || e1A * pv - e1B * pu + e1C < -1e-9 || e2A * pv - e2B * pu + e2C < -1e-9) continue;
                    double w = Math.max(wMin, Math.min(wMax, wa + gu * (pu - ua) + gv * (pv - va)));
                    int k = (int) Math.floor(w);
                    if (k < 0 || k >= limW) continue;
                    setSub(target, axis, i, j, k);
                }
            }
            return;
        }

        for (int j = iv0; j <= iv1; j++) {
            double v0 = j, v1 = j + 1;
            for (int i = iu0; i <= iu1; i++) {
                double u0 = i, u1 = i + 1;
                if (e0A * (e0A > 0 ? v1 : v0) - e0B * (e0B < 0 ? u1 : u0) + e0C < -1e-9) continue;
                if (e1A * (e1A > 0 ? v1 : v0) - e1B * (e1B < 0 ? u1 : u0) + e1C < -1e-9) continue;
                if (e2A * (e2A > 0 ? v1 : v0) - e2B * (e2B < 0 ? u1 : u0) + e2C < -1e-9) continue;
                double w00 = wa + gu * (u0 - ua) + gv * (v0 - va), w10 = wa + gu * (u1 - ua) + gv * (v0 - va);
                double w01 = wa + gu * (u0 - ua) + gv * (v1 - va), w11 = wa + gu * (u1 - ua) + gv * (v1 - va);
                double lo = Math.max(wMin, Math.min(Math.min(w00, w10), Math.min(w01, w11)));
                double hi = Math.min(wMax, Math.max(Math.max(w00, w10), Math.max(w01, w11)));
                int k0 = Math.max(0, (int) Math.floor(lo)), k1 = Math.min(limW - 1, (int) Math.floor(hi));
                for (int k = k0; k <= k1; k++) setSub(target, axis, i, j, k);
            }
        }
    }

    /** Sets one sub-voxel given in the (u, v, w) frame of a dominant axis; safe from several threads. */
    private void setSub(AtomicLongArray[] target, int axis, int i, int j, int k) {
        int x, y, z;
        if (axis == 0) { x = k; y = i; z = j; }
        else if (axis == 1) { x = j; y = k; z = i; }
        else { x = i; y = j; z = k; }
        int bx0 = x >> 2, by0 = y >> 2, bz0 = z >> 2;
        AtomicLongArray sec = target[sectionIndex(bx0, by0, bz0)];
        if (sec == null) return;
        long bit = 1L << ((x & 3) | ((y & 3) << 2) | ((z & 3) << 4));
        int bi = blockIndex(bx0, by0, bz0);
        long cur;
        do {
            cur = sec.get(bi);
            if ((cur & bit) != 0) return;
        } while (!sec.compareAndSet(bi, cur, cur | bit));
    }

    private long opaqueMask(int bx, int by, int bz) {
        AtomicLongArray s = opaque[sectionIndex(bx, by, bz)];
        return s == null ? 0 : s.get(blockIndex(bx, by, bz));
    }

    private long glassMask(int bx, int by, int bz) {
        AtomicLongArray s = glass[sectionIndex(bx, by, bz)];
        return s == null ? 0 : s.get(blockIndex(bx, by, bz));
    }

    private boolean opaqueSub(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= nx * SUB || y >= ny * SUB || z >= nz * SUB) return false;
        return ((opaqueMask(x >> 2, y >> 2, z >> 2) >>> ((x & 3) | ((y & 3) << 2) | ((z & 3) << 4))) & 1) != 0;
    }

    // ---- which cells get baked ----

    private long[] markSampleCells() {
        int count = 0;
        for (int sx = 0; sx < snx; sx++)
            for (int sy = 0; sy < sny; sy++)
                for (int sz = 0; sz < snz; sz++) {
                    int si = (sx * sny + sy) * snz + sz;
                    if (opaque[si] == null && glass[si] == null) continue;
                    for (int i = 0; i < 4096; i++) {
                        long m = (opaque[si] == null ? 0 : opaque[si].get(i)) | (glass[si] == null ? 0 : glass[si].get(i));
                        if (m == 0) continue;
                        int bx = (sx << 4) | (i >> 8), by = (sy << 4) | ((i >> 4) & 15), bz = (sz << 4) | (i & 15);
                        for (int dx = -NEAR; dx <= NEAR; dx++)
                            for (int dy = -NEAR; dy <= NEAR; dy++)
                                for (int dz = -NEAR; dz <= NEAR; dz++) {
                                    int x = bx + dx, y = by + dy, z = bz + dz;
                                    if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) continue;
                                    int ti = sectionIndex(x, y, z);
                                    if (state[ti] == null) {
                                        state[ti] = new byte[4096];
                                        faces[ti] = new byte[6 * 4096];
                                    }
                                    int ci = blockIndex(x, y, z);
                                    if (state[ti][ci] == 0) {
                                        state[ti][ci] = 1;
                                        count++;
                                    }
                                }
                    }
                }
        long[] cells = new long[count];
        int n = 0;
        for (int si = 0; si < state.length; si++) {
            if (state[si] == null) continue;
            int sx = si / (sny * snz), sy = (si / snz) % sny, sz = si % snz;
            for (int i = 0; i < 4096; i++)
                if (state[si][i] != 0) cells[n++] = pack((sx << 4) | (i >> 8), (sy << 4) | ((i >> 4) & 15), (sz << 4) | (i & 15));
        }
        return cells;
    }

    // ---- baking ----

    private long bake(long[] cells, int threads) {
        AtomicInteger next = new AtomicInteger();
        long[] rayCount = new long[1];
        runParallel(threads, () -> {
            float[] trans = new float[DIRS];
            double[] rot = new double[9];
            double[] dir = new double[3 * DIRS];
            double[] weight = new double[6 * DIRS];
            double[] weightSum = new double[6];
            long rays = 0;
            int from;
            while ((from = next.getAndAdd(256)) < cells.length) {
                int to = Math.min(cells.length, from + 256);
                for (int k = from; k < to; k++) {
                    long c = cells[k];
                    int bx = (int) (c >>> 42), by = (int) ((c >>> 21) & 0x1FFFFF), bz = (int) (c & 0x1FFFFF);
                    int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
                    double cx = (bx + 0.5) * SUB, cy = (by + 0.5) * SUB, cz = (bz + 0.5) * SUB;
                    rotation(bx, by, bz, rot);
                    Arrays.fill(weightSum, 0);
                    for (int i = 0; i < DIRS; i++) {
                        double x = DIR[3 * i], y = DIR[3 * i + 1], z = DIR[3 * i + 2];
                        for (int a = 0; a < 3; a++) dir[3 * i + a] = rot[3 * a] * x + rot[3 * a + 1] * y + rot[3 * a + 2] * z;
                        double side = dir[3 * i + 1] < 0 ? groundWeight : 1;
                        for (int a = 0; a < 3; a++) {
                            double r = dir[3 * i + a], g = a == 1 ? 1 : side;
                            weight[6 * i + 2 * a] = g * Math.max(0, r);
                            weight[6 * i + 2 * a + 1] = g * Math.max(0, -r);
                            weightSum[2 * a] += g * Math.max(0, r);
                            weightSum[2 * a + 1] += g * Math.max(0, -r);
                        }
                    }
                    int flags = 1;
                    boolean blocked = false;
                    for (int d = 0; d < 8 && !blocked; d++)
                        if (opaqueSub((int) cx - 1 + (d & 1), (int) cy - 1 + ((d >> 1) & 1), (int) cz - 1 + ((d >> 2) & 1))) blocked = true;
                    if (!blocked) {
                        for (int i = 0; i < DIRS; i++) trans[i] = trace(cx + 1e-4, cy + 2e-4, cz + 3e-4, dir[3 * i], dir[3 * i + 1], dir[3 * i + 2]);
                        rays += DIRS;
                        for (int f = 0; f < 6; f++) {
                            double s = 0;
                            for (int i = 0; i < DIRS; i++) s += weight[6 * i + f] * trans[i];
                            faces[si][6 * ci + f] = (byte) Math.round(255 * s / weightSum[f]);
                        }
                    } else {
                        flags |= 0x80;
                        for (int f = 0; f < 6; f++) {
                            double ox = cx + (f == 0 ? 1.5 : f == 1 ? -1.5 : 0) + 1e-4, oy = cy + (f == 2 ? 1.5 : f == 3 ? -1.5 : 0) + 2e-4, oz = cz + (f == 4 ? 1.5 : f == 5 ? -1.5 : 0) + 3e-4;
                            if (opaqueSub((int) Math.floor(ox), (int) Math.floor(oy), (int) Math.floor(oz))) {
                                flags |= 1 << (f + 1);
                                continue;
                            }
                            double s = 0;
                            for (int i = 0; i < DIRS; i++) {
                                double w = weight[6 * i + f];
                                if (w <= 0) continue;
                                s += w * trace(ox, oy, oz, dir[3 * i], dir[3 * i + 1], dir[3 * i + 2]);
                                rays++;
                            }
                            faces[si][6 * ci + f] = (byte) Math.round(255 * s / weightSum[f]);
                        }
                    }
                    state[si][ci] = (byte) flags;
                }
            }
            synchronized (rayCount) {
                rayCount[0] += rays;
            }
        });
        return rayCount[0];
    }

    /** Fraction of light that gets out along the ray (sub-voxel units): 0 = hit, 1 = open sky, in between = through glass. */
    private float trace(double ox, double oy, double oz, double dx, double dy, double dz) {
        return trace(ox, oy, oz, dx, dy, dz, null);
    }

    /** As above; when {@code hit} is given, hit[0] receives the distance (sub-voxels) of the blocking sub-voxel, or -1. */
    private float trace(double ox, double oy, double oz, double dx, double dy, double dz, double[] hit) {
        if (hit != null) hit[0] = -1;
        final double maxT = rayLength * SUB;
        final int lx = nx * SUB, ly = ny * SUB, lz = nz * SUB;
        float trans = 1f;
        boolean inGlass = false;
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double idx = dx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
        double idy = dy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
        double idz = dz == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
        double t = 0;
        int ix = (int) Math.floor(ox), iy = (int) Math.floor(oy), iz = (int) Math.floor(oz);
        double tMaxX = dx > 0 ? (ix + 1 - ox) * idx : dx < 0 ? (ox - ix) * idx : Double.POSITIVE_INFINITY;
        double tMaxY = dy > 0 ? (iy + 1 - oy) * idy : dy < 0 ? (oy - iy) * idy : Double.POSITIVE_INFINITY;
        double tMaxZ = dz > 0 ? (iz + 1 - oz) * idz : dz < 0 ? (oz - iz) * idz : Double.POSITIVE_INFINITY;
        while (t <= maxT) {
            if (ix < 0 || iy < 0 || iz < 0 || ix >= lx || iy >= ly || iz >= lz) return trans;
            int bx = ix >> 2, by = iy >> 2, bz = iz >> 2;
            long om = opaqueMask(bx, by, bz), gm = glassMask(bx, by, bz);
            if ((om | gm) == 0) {
                // empty block: jump to where the ray leaves it
                double ex = dx > 0 ? ((bx + 1) * SUB - ox) * idx : dx < 0 ? (ox - bx * SUB) * idx : Double.POSITIVE_INFINITY;
                double ey = dy > 0 ? ((by + 1) * SUB - oy) * idy : dy < 0 ? (oy - by * SUB) * idy : Double.POSITIVE_INFINITY;
                double ez = dz > 0 ? ((bz + 1) * SUB - oz) * idz : dz < 0 ? (oz - bz * SUB) * idz : Double.POSITIVE_INFINITY;
                t = Math.min(ex, Math.min(ey, ez)) + 1e-6;
                if (t > maxT) return trans;
                inGlass = false;
                double px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
                ix = (int) Math.floor(px);
                iy = (int) Math.floor(py);
                iz = (int) Math.floor(pz);
                tMaxX = dx > 0 ? t + (ix + 1 - px) * idx : dx < 0 ? t + (px - ix) * idx : Double.POSITIVE_INFINITY;
                tMaxY = dy > 0 ? t + (iy + 1 - py) * idy : dy < 0 ? t + (py - iy) * idy : Double.POSITIVE_INFINITY;
                tMaxZ = dz > 0 ? t + (iz + 1 - pz) * idz : dz < 0 ? t + (pz - iz) * idz : Double.POSITIVE_INFINITY;
                continue;
            }
            int bit = (ix & 3) | ((iy & 3) << 2) | ((iz & 3) << 4);
            if (((om >>> bit) & 1) != 0) {
                if (hit != null) hit[0] = t;
                return 0f;
            }
            if (((gm >>> bit) & 1) != 0) {
                if (!inGlass) {
                    trans *= glassTransmission;
                    inGlass = true;
                    if (trans < 0.01f) return 0f;
                }
            } else {
                inGlass = false;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                ix += stepX;
                t = tMaxX;
                tMaxX += idx;
            } else if (tMaxY < tMaxZ) {
                iy += stepY;
                t = tMaxY;
                tMaxY += idy;
            } else {
                iz += stepZ;
                t = tMaxZ;
                tMaxZ += idz;
            }
        }
        return trans;
    }

    /** Random rotation matrix (row major) that depends only on the cell, so a bake is reproducible. */
    private static void rotation(int x, int y, int z, double[] m) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        double u1 = (h & 0x1FFFFF) / (double) 0x200000, u2 = ((h >>> 21) & 0x1FFFFF) / (double) 0x200000, u3 = ((h >>> 42) & 0x1FFFFF) / (double) 0x200000;
        double a = Math.sqrt(1 - u1), b = Math.sqrt(u1);
        double qx = a * Math.sin(2 * Math.PI * u2), qy = a * Math.cos(2 * Math.PI * u2), qz = b * Math.sin(2 * Math.PI * u3), qw = b * Math.cos(2 * Math.PI * u3);
        m[0] = 1 - 2 * (qy * qy + qz * qz);
        m[1] = 2 * (qx * qy - qz * qw);
        m[2] = 2 * (qx * qz + qy * qw);
        m[3] = 2 * (qx * qy + qz * qw);
        m[4] = 1 - 2 * (qx * qx + qz * qz);
        m[5] = 2 * (qy * qz - qx * qw);
        m[6] = 2 * (qx * qz - qy * qw);
        m[7] = 2 * (qy * qz + qx * qw);
        m[8] = 1 - 2 * (qx * qx + qy * qy);
    }

    // ---- smoothing, bounce and gaps ----

    /**
     * Averages each face value with the same face of neighbour cells (26-neighbourhood) that are in free space and
     * reachable in a straight line, so light never leaks through a wall or floor.
     */
    private void smooth(long[] cells, int threads) {
        if (smoothIterations <= 0) return;
        int[] conn = new int[cells.length];
        AtomicInteger next = new AtomicInteger();
        runParallel(threads, () -> {
            int from;
            while ((from = next.getAndAdd(1024)) < cells.length) {
                for (int k = from, to = Math.min(cells.length, from + 1024); k < to; k++) {
                    long c = cells[k];
                    conn[k] = connections((int) (c >>> 42), (int) ((c >>> 21) & 0x1FFFFF), (int) (c & 0x1FFFFF));
                }
            }
        });
        for (int it = 0; it < smoothIterations; it++) {
            byte[][] src = new byte[faces.length][];
            for (int si = 0; si < faces.length; si++) if (faces[si] != null) src[si] = faces[si].clone();
            next.set(0);
            runParallel(threads, () -> {
                int from;
                while ((from = next.getAndAdd(1024)) < cells.length) {
                    for (int k = from, to = Math.min(cells.length, from + 1024); k < to; k++) {
                        int mask = conn[k];
                        if (mask == 0) continue;
                        long c = cells[k];
                        int bx = (int) (c >>> 42), by = (int) ((c >>> 21) & 0x1FFFFF), bz = (int) (c & 0x1FFFFF);
                        int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
                        for (int f = 0; f < 6; f++) {
                            double sum = SMOOTH_WEIGHT[0] * (src[si][6 * ci + f] & 255), wsum = SMOOTH_WEIGHT[0];
                            for (int n = 0; n < 27; n++) {
                                if (((mask >>> n) & 1) == 0) continue;
                                int dx = n / 9 - 1, dy = (n / 3) % 3 - 1, dz = n % 3 - 1;
                                int x = bx + dx, y = by + dy, z = bz + dz;
                                double w = SMOOTH_WEIGHT[Math.abs(dx) + Math.abs(dy) + Math.abs(dz)];
                                sum += w * (src[sectionIndex(x, y, z)][6 * blockIndex(x, y, z) + f] & 255);
                                wsum += w;
                            }
                            faces[si][6 * ci + f] = (byte) Math.round(sum / wsum);
                        }
                    }
                }
            });
        }
    }

    /** Bit n = (dx+1)*9 + (dy+1)*3 + (dz+1) set when that neighbour takes part in smoothing this cell. */
    private int connections(int bx, int by, int bz) {
        int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
        if ((state[si][ci] & 0x80) != 0) return 0;
        int mask = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) continue;
                    int ti = sectionIndex(x, y, z);
                    if (state[ti] == null) continue;
                    int st = state[ti][blockIndex(x, y, z)];
                    if (st == 0 || (st & 0x80) != 0) continue;
                    if (!segmentClear(bx, by, bz, dx, dy, dz)) continue;
                    mask |= 1 << ((dx + 1) * 9 + (dy + 1) * 3 + (dz + 1));
                }
        return mask;
    }

    /** No opaque sub-voxel on the straight line between the centres of two neighbouring blocks. */
    private boolean segmentClear(int bx, int by, int bz, int dx, int dy, int dz) {
        boolean any = false;
        for (int i = 0; i <= Math.abs(dx) && !any; i++)
            for (int j = 0; j <= Math.abs(dy) && !any; j++)
                for (int k = 0; k <= Math.abs(dz) && !any; k++)
                    if (opaqueMask(bx + i * dx, by + j * dy, bz + k * dz) != 0) any = true;
        if (!any) return true;
        double ax = (bx + 0.5) * SUB, ay = (by + 0.5) * SUB, az = (bz + 0.5) * SUB;
        for (int s = 1; s < 16; s++) {
            double t = s / 16.0 * SUB;
            if (opaqueSub((int) Math.floor(ax + dx * t), (int) Math.floor(ay + dy * t), (int) Math.floor(az + dz * t))) return false;
        }
        return true;
    }

    /** Soft indirect light: the average sky seen nearby, blurred through free space, lifts dark faces. */
    private void bounce(long[] cells) {
        float[][] mean = new float[faces.length][];
        float[][] tmp = new float[faces.length][];
        for (long c : cells) {
            int bx = (int) (c >>> 42), by = (int) ((c >>> 21) & 0x1FFFFF), bz = (int) (c & 0x1FFFFF);
            int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
            if (mean[si] == null) {
                mean[si] = new float[4096];
                tmp[si] = new float[4096];
            }
            int st = state[si][ci];
            float s = 0;
            int n = 0;
            for (int f = 0; f < 6; f++) {
                if ((st & (1 << (f + 1))) != 0) continue;
                s += (faces[si][6 * ci + f] & 255) / 255f;
                n++;
            }
            mean[si][ci] = n == 0 ? 0 : s / n;
        }
        for (int it = 0; it < bounceIterations; it++) {
            for (long c : cells) {
                int bx = (int) (c >>> 42), by = (int) ((c >>> 21) & 0x1FFFFF), bz = (int) (c & 0x1FFFFF);
                int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
                if ((state[si][ci] & 0x80) != 0) {
                    tmp[si][ci] = mean[si][ci];
                    continue;
                }
                float s = mean[si][ci];
                int n = 1;
                for (int d = 0; d < 6; d++) {
                    int x = bx + (d == 0 ? 1 : d == 1 ? -1 : 0), y = by + (d == 2 ? 1 : d == 3 ? -1 : 0), z = bz + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
                        s += 1;
                        n++;
                        continue;
                    }
                    int ti = sectionIndex(x, y, z), tj = blockIndex(x, y, z);
                    if (state[ti] == null || state[ti][tj] == 0) {
                        s += 1; // open air far from the mesh
                        n++;
                    } else if ((state[ti][tj] & 0x80) == 0) {
                        s += mean[ti][tj];
                        n++;
                    }
                }
                tmp[si][ci] = s / n;
            }
            float[][] sw = mean;
            mean = tmp;
            tmp = sw;
        }
        for (long c : cells) {
            int bx = (int) (c >>> 42), by = (int) ((c >>> 21) & 0x1FFFFF), bz = (int) (c & 0x1FFFFF);
            int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
            float b = (float) (bounceStrength * mean[si][ci]);
            for (int f = 0; f < 6; f++) {
                float d = (faces[si][6 * ci + f] & 255) / 255f;
                faces[si][6 * ci + f] = (byte) Math.round(255 * Math.min(1f, d + b * (1 - d)));
            }
        }
    }

    /** Faces that could not be baked (their side is inside geometry) take the value of the neighbour they face. */
    private void fillUndefined(long[] cells) {
        for (int pass = 0; pass < 3; pass++) {
            // neighbours are read as they were before this pass, so a value never runs along a whole slab in one pass
            byte[][] oldState = new byte[state.length][], oldFaces = new byte[faces.length][];
            for (int si = 0; si < state.length; si++) {
                if (state[si] == null) continue;
                oldState[si] = state[si].clone();
                oldFaces[si] = faces[si].clone();
            }
            for (long c : cells) {
                int bx = (int) (c >>> 42), by = (int) ((c >>> 21) & 0x1FFFFF), bz = (int) (c & 0x1FFFFF);
                int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
                int st = state[si][ci];
                if ((st & 0x7E) == 0) continue;
                for (int f = 0; f < 6; f++) {
                    if ((st & (1 << (f + 1))) == 0) continue;
                    int x = bx + (f == 0 ? 1 : f == 1 ? -1 : 0), y = by + (f == 2 ? 1 : f == 3 ? -1 : 0), z = bz + (f == 4 ? 1 : f == 5 ? -1 : 0);
                    int value = -1;
                    if (x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz) {
                        int ti = sectionIndex(x, y, z), tj = blockIndex(x, y, z);
                        if (oldState[ti] != null && oldState[ti][tj] != 0 && (oldState[ti][tj] & (1 << (f + 1))) == 0) value = oldFaces[ti][6 * tj + f] & 255;
                        else if (oldState[ti] == null || oldState[ti][tj] == 0) value = 255;
                    } else {
                        value = 255;
                    }
                    if (value < 0 && pass == 2) value = 0;
                    if (value >= 0) {
                        faces[si][6 * ci + f] = (byte) value;
                        st &= ~(1 << (f + 1));
                    }
                }
                state[si][ci] = (byte) st;
            }
        }
    }

    // ---- queries ----

    /** Sky seen by a surface facing (nx, ny, nz) in this cell, 0..1; cells that were not baked are open sky. */
    private float cellValue(int bx, int by, int bz, double nxv, double nyv, double nzv) {
        if (bx < 0 || by < 0 || bz < 0 || bx >= nx || by >= ny || bz >= nz) return 1f;
        int si = sectionIndex(bx, by, bz);
        if (state[si] == null) return 1f;
        int ci = blockIndex(bx, by, bz);
        if (state[si][ci] == 0) return 1f;
        byte[] fc = faces[si];
        int o = 6 * ci;
        double x2 = nxv * nxv, y2 = nyv * nyv, z2 = nzv * nzv;
        double v = x2 * (fc[o + (nxv >= 0 ? 0 : 1)] & 255) + y2 * (fc[o + (nyv >= 0 ? 2 : 3)] & 255) + z2 * (fc[o + (nzv >= 0 ? 4 : 5)] & 255);
        return (float) (v / (255 * Math.max(1e-6, x2 + y2 + z2)));
    }

    /** Sky visibility 0..1 of a surface point (anchor-relative) with its unit normal: trilinear between cells half a block in front. */
    public float sample(double x, double y, double z, double nxv, double nyv, double nzv) {
        double u = x + nxv * 0.5 - originX - 0.5, v = y + nyv * 0.5 - originY - 0.5, w = z + nzv * 0.5 - originZ - 0.5;
        int i0 = (int) Math.floor(u), j0 = (int) Math.floor(v), k0 = (int) Math.floor(w);
        double fu = u - i0, fv = v - j0, fw = w - k0;
        double s = 0, ws = 0;
        for (int d = 0; d < 8; d++) {
            int di = d & 1, dj = (d >> 1) & 1, dk = (d >> 2) & 1;
            double wt = (di == 0 ? 1 - fu : fu) * (dj == 0 ? 1 - fv : fv) * (dk == 0 ? 1 - fw : fw);
            if (wt == 0) continue;
            wt *= cellWeight(i0 + di, j0 + dj, k0 + dk);
            s += wt * cellValue(i0 + di, j0 + dj, k0 + dk, nxv, nyv, nzv);
            ws += wt;
        }
        return ws <= 0 ? 1f : (float) (s / ws);
    }

    /** Interpolation weight of a cell, as the texture alpha: 1 in free space, {@link #BLOCKED_ALPHA}/255 inside geometry. */
    private double cellWeight(int bx, int by, int bz) {
        if (bx < 0 || by < 0 || bz < 0 || bx >= nx || by >= ny || bz >= nz) return 1;
        int si = sectionIndex(bx, by, bz);
        if (state[si] == null) return 1;
        return (state[si][blockIndex(bx, by, bz)] & 0x80) != 0 ? BLOCKED_ALPHA / 255.0 : 1;
    }

    /**
     * Sky light level (0..15, fractional) for a visibility: every halving of the visible sky costs two levels, like
     * Minecraft's falloff of one level per block. The shader uses the same formula.
     */
    public static float levelOf(float visibility, float floor) {
        double l = 15 + 2 * Math.log(Math.max(1e-4, visibility)) / Math.log(2);
        return (float) Math.max(floor, Math.min(15, l));
    }

    /**
     * Writes the light of a box of cells as two RGBA byte volumes (pos = +X +Y +Z, neg = -X -Y -Z), x fastest then y
     * then z, as glTexImage3D expects. Alpha is the interpolation weight of the cell and RGB is premultiplied by it, so
     * linear filtering divided by alpha ignores cells inside geometry. One texel covers {@code step} blocks; cells that
     * were not baked are open sky.
     *
     * @param x0 anchor-relative block of texel (0, 0, 0)
     */
    public void fillTexture(int x0, int y0, int z0, int w, int h, int d, int step, java.nio.ByteBuffer pos, java.nio.ByteBuffer neg) {
        for (int k = 0; k < d; k++)
            for (int j = 0; j < h; j++)
                for (int i = 0; i < w; i++) {
                    int bx = x0 + i * step + step / 2 - originX, by = y0 + j * step + step / 2 - originY, bz = z0 + k * step + step / 2 - originZ;
                    byte[] fc = null;
                    int o = 0, st = 0;
                    if (bx >= 0 && by >= 0 && bz >= 0 && bx < nx && by < ny && bz < nz) {
                        int si = sectionIndex(bx, by, bz), ci = blockIndex(bx, by, bz);
                        if (state[si] != null && state[si][ci] != 0) {
                            fc = faces[si];
                            o = 6 * ci;
                            st = state[si][ci];
                        }
                    }
                    if (fc == null) {
                        pos.putInt(-1);
                        neg.putInt(-1);
                    } else if ((st & 0x80) == 0) {
                        pos.put(fc[o]).put(fc[o + 2]).put(fc[o + 4]).put((byte) -1);
                        neg.put(fc[o + 1]).put(fc[o + 3]).put(fc[o + 5]).put((byte) -1);
                    } else {
                        pos.put(premultiplied(fc[o])).put(premultiplied(fc[o + 2])).put(premultiplied(fc[o + 4])).put((byte) BLOCKED_ALPHA);
                        neg.put(premultiplied(fc[o + 1])).put(premultiplied(fc[o + 3])).put(premultiplied(fc[o + 5])).put((byte) BLOCKED_ALPHA);
                    }
                }
    }

    private static byte premultiplied(byte value) {
        return (byte) Math.round((value & 255) * BLOCKED_ALPHA / 255.0);
    }

    /**
     * Minecraft sky light level (0..15, fractional) that makes a surface with this visibility about as bright as
     * the visibility says: inverse of Minecraft's brightness curve.
     */
    public static float level(float visibility) {
        double b = Math.max(0, Math.min(1, visibility));
        double f = (1 - b) / (3 * b + 1);
        return (float) (15 * (1 - f));
    }

    public int sectionCount() {
        int n = 0;
        for (byte[] s : state) if (s != null) n++;
        return n;
    }

    // ---- diagnostics ----

    /** Transmission of one ray from an anchor-relative point (1 = sky, 0 = blocked), for tests. */
    public float traceFrom(double x, double y, double z, double dx, double dy, double dz) {
        return trace((x - originX) * SUB, (y - originY) * SUB, (z - originZ) * SUB, dx, dy, dz);
    }

    /** Distance in blocks to the first opaque sub-voxel along the ray, or -1 when the ray is not blocked. */
    public double firstOpaque(double x, double y, double z, double dx, double dy, double dz) {
        double[] hit = new double[1];
        trace((x - originX) * SUB, (y - originY) * SUB, (z - originZ) * SUB, dx, dy, dz, hit);
        return hit[0] < 0 ? -1 : hit[0] / SUB;
    }

    /** Cell state flags (see {@link #state}) of the block containing the point, -1 when not baked. */
    public int cellState(double x, double y, double z) {
        int bx = (int) Math.floor(x) - originX, by = (int) Math.floor(y) - originY, bz = (int) Math.floor(z) - originZ;
        if (bx < 0 || by < 0 || bz < 0 || bx >= nx || by >= ny || bz >= nz) return -1;
        int si = sectionIndex(bx, by, bz);
        return state[si] == null ? -1 : state[si][blockIndex(bx, by, bz)] & 255;
    }

    /** The six face values (0..1) of the block containing the point, null when not baked. */
    public float[] cellFaces(double x, double y, double z) {
        int bx = (int) Math.floor(x) - originX, by = (int) Math.floor(y) - originY, bz = (int) Math.floor(z) - originZ;
        if (bx < 0 || by < 0 || bz < 0 || bx >= nx || by >= ny || bz >= nz) return null;
        int si = sectionIndex(bx, by, bz);
        if (state[si] == null || state[si][blockIndex(bx, by, bz)] == 0) return null;
        float[] f = new float[6];
        for (int i = 0; i < 6; i++) f[i] = (faces[si][6 * blockIndex(bx, by, bz) + i] & 255) / 255f;
        return f;
    }

    /** Occupancy of the block containing the point: opaque sub-voxels, glass sub-voxels. */
    public int[] occupancy(double x, double y, double z) {
        int bx = (int) Math.floor(x) - originX, by = (int) Math.floor(y) - originY, bz = (int) Math.floor(z) - originZ;
        if (bx < 0 || by < 0 || bz < 0 || bx >= nx || by >= ny || bz >= nz) return new int[2];
        return new int[] { Long.bitCount(opaqueMask(bx, by, bz)), Long.bitCount(glassMask(bx, by, bz)) };
    }

    // ---- helpers ----

    private static long pack(int x, int y, int z) {
        return ((long) x << 42) | ((long) y << 21) | z;
    }

    private static double min3(double a, double b, double c) {
        return Math.min(a, Math.min(b, c));
    }

    private static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    private static void runParallel(int threads, Runnable work) {
        Thread[] ts = new Thread[Math.max(0, threads - 1)];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(work, "meshimporter-bake");
            ts[i].setDaemon(true);
            ts[i].setPriority(Thread.MIN_PRIORITY + 1);
            ts[i].start();
        }
        work.run();
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
