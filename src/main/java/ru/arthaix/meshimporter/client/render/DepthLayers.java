package ru.arthaix.meshimporter.client.render;

import java.util.Arrays;

/**
 * Which surfaces of a model lie just over other surfaces, so that they can be drawn a little nearer the camera than
 * what they cover.
 *
 * <p>Minecraft's depth buffer has 24 bits and a near plane 0.05 blocks from the eye, so at a distance {@code d} it can
 * tell two surfaces apart only if they are more than about {@code d * d / 838861} blocks apart: a centimetre at 90
 * blocks, five at 200, thirty at 500. A path laid a few centimetres over the ground, a marking over asphalt or two faces
 * that ended up in the same plane look right close by and flicker further off, whichever way the model was built. No
 * gap in the model fixes that for every distance - but an order does. Each triangle gets a layer: 0 for anything with
 * nothing of its own facing directly underneath, and one more than the highest layer under it otherwise. Drawn with
 * a depth offset that grows with the layer, the upper surface wins at every distance, because the offset is measured in
 * steps of the depth buffer itself and those steps grow with the distance exactly as the problem does.
 *
 * <p>"Under" is along the surface's own facing: two faces count as stacked when they face the same way (normals within
 * about 18 degrees), one lies over the other at the same spot, and the gap is less than {@code gap} blocks. Faces in
 * the very same plane have no order of their own; the smaller one is taken to be on top, the way a marking is smaller
 * than the road it is painted on.
 *
 * <p>The search samples every face on a grid in the plane of its main axis, one point per cell centre it covers (and its
 * centre if it covers none, so thin strips are not missed), sorts the samples by cell and compares the few that share a
 * cell. A city of four million triangles takes about a second.
 */
final class DepthLayers {

    /**
     * Highest layer. Every layer is one more step of the depth buffer, and a step is a real distance that grows with
     * the square of the distance: 1.2 cm at 100 blocks, 30 cm at 500. Four layers keep the top one within a block of
     * where it is at the edge of view, so nothing behind a wall shows through it; a real surface is seldom stacked deeper.
     */
    static final int MAX = 4;
    /** Faces closer than this are in the same plane. */
    private static final float TIE = 0.001f;
    /** How far inside a face, in blocks, a point must be to count as lying on it. */
    private static final float MARGIN = 0.01f;
    /** Cosine of the largest angle between two faces that still count as lying on one another. */
    private static final float PARALLEL = 0.95f;
    /** Grid cells per facing at most; bigger models get a coarser grid. */
    private static final double MAX_CELLS = 8_000_000;

    private DepthLayers() {}

    /**
     * @param loc     vertex positions, 3 per vertex
     * @param nrm     vertex normals, which say which side of a face is its front
     * @param idx     3 vertex indices per triangle
     * @param gap     largest distance between two stacked faces, in blocks
     * @return the layer of every triangle, 0..{@link #MAX}
     */
    static byte[] compute(float[] loc, byte[] nrm, int[] idx, int tris, float gap) {
        byte[] layer = new byte[tris];
        if (tris == 0) return layer;
        float[] n = new float[3 * tris];
        float[] area = new float[tris];
        byte[] facing = new byte[tris];
        int[] perFacing = new int[6];
        for (int t = 0; t < tris; t++) {
            int a = idx[3 * t], b = idx[3 * t + 1], c = idx[3 * t + 2];
            float ex = loc[3 * b] - loc[3 * a], ey = loc[3 * b + 1] - loc[3 * a + 1], ez = loc[3 * b + 2] - loc[3 * a + 2];
            float fx = loc[3 * c] - loc[3 * a], fy = loc[3 * c + 1] - loc[3 * a + 1], fz = loc[3 * c + 2] - loc[3 * a + 2];
            float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-7f) {
                facing[t] = -1;
                continue;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            // the winding may be either way round (a mirrored model turns it over); the stored normals say which side is the front
            int sx = nrm[3 * a] + nrm[3 * b] + nrm[3 * c], sy = nrm[3 * a + 1] + nrm[3 * b + 1] + nrm[3 * c + 1], sz = nrm[3 * a + 2] + nrm[3 * b + 2] + nrm[3 * c + 2];
            if (nx * sx + ny * sy + nz * sz < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            n[3 * t] = nx;
            n[3 * t + 1] = ny;
            n[3 * t + 2] = nz;
            area[t] = len / 2;
            float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
            int axis = ay >= ax && ay >= az ? 1 : ax >= az ? 0 : 2;
            float along = axis == 0 ? nx : axis == 1 ? ny : nz;
            facing[t] = (byte) (2 * axis + (along > 0 ? 1 : 0));
            perFacing[facing[t]]++;
        }

        Edges[] perFacingEdges = new Edges[6];
        java.util.stream.IntStream.range(0, 6).parallel().forEach(f -> {
            Edges edges = new Edges();
            if (perFacing[f] > 1) stackOneFacing(f, loc, idx, tris, n, area, facing, perFacing[f], gap, edges);
            perFacingEdges[f] = edges;
        });
        long[] e = Edges.merge(perFacingEdges);
        // longest chain under each face, capped; a pair of faces that each lie over the other somewhere just ends up high
        for (int pass = 0; pass < MAX; pass++) {
            boolean changed = false;
            for (long pair : e) {
                int lower = (int) (pair >>> 32), upper = (int) pair;
                int want = Math.min(MAX, layer[lower] + 1);
                if (layer[upper] < want) {
                    layer[upper] = (byte) want;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return layer;
    }

    /** Samples every face of one facing and records which lies over which. */
    private static void stackOneFacing(int f, float[] loc, int[] idx, int tris, float[] n, float[] area, byte[] facing,
                                       int count, float gap, Edges edges) {
        int axis = f / 2;
        float sign = (f & 1) == 1 ? 1 : -1;
        int ua = axis == 0 ? 1 : 0, va = axis == 2 ? 1 : 2;

        float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        int[] list = new int[count];
        int k = 0;
        for (int t = 0; t < tris; t++) {
            if (facing[t] != f) continue;
            list[k++] = t;
            for (int c = 0; c < 3; c++) {
                int v = idx[3 * t + c];
                float u = loc[3 * v + ua], w = loc[3 * v + va];
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (w < minV) minV = w;
                if (w > maxV) maxV = w;
            }
        }
        double span = Math.max(1e-3, (maxU - minU)) * Math.max(1e-3, (maxV - minV));
        float cell = (float) Math.max(0.5, Math.sqrt(span / MAX_CELLS));
        int cols = (int) ((maxU - minU) / cell) + 2;

        // samples: key = cell << 24 | sample, the sample says which face and whether it stands on the cell centre
        Samples s = new Samples();
        float[] pu = new float[3], pv = new float[3];
        for (int t : list) {
            for (int c = 0; c < 3; c++) {
                int v = idx[3 * t + c];
                pu[c] = loc[3 * v + ua];
                pv[c] = loc[3 * v + va];
            }
            int c0 = (int) Math.floor((Math.min(pu[0], Math.min(pu[1], pu[2])) - minU) / cell);
            int c1 = (int) Math.floor((Math.max(pu[0], Math.max(pu[1], pu[2])) - minU) / cell);
            int r0 = (int) Math.floor((Math.min(pv[0], Math.min(pv[1], pv[2])) - minV) / cell);
            int r1 = (int) Math.floor((Math.max(pv[0], Math.max(pv[1], pv[2])) - minV) / cell);
            boolean any = false;
            for (int r = r0; r <= r1; r++) {
                float cv = minV + (r + 0.5f) * cell;
                for (int col = c0; col <= c1; col++) {
                    float cu = minU + (col + 0.5f) * cell;
                    if (inside(pu, pv, cu, cv, 1e-4f)) {
                        s.add((long) r * cols + col, t, true);
                        any = true;
                    }
                }
            }
            if (!any) {
                float cu = (pu[0] + pu[1] + pu[2]) / 3f, cv = (pv[0] + pv[1] + pv[2]) / 3f;
                long col = (long) Math.floor((cu - minU) / cell), row = (long) Math.floor((cv - minV) / cell);
                s.add(row * cols + col, t, false);
            }
        }

        long[] keys = s.keys;
        int size = s.size;
        Arrays.sort(keys, 0, size);
        int[] group = new int[64];
        float[] qu = new float[64], qv = new float[64];
        Scratch scratch = new Scratch();
        for (int i = 0; i < size; ) {
            long cellKey = keys[i] >>> 24;
            int j = i;
            while (j < size && (keys[j] >>> 24) == cellKey) j++;
            int m = j - i;
            if (m > 1) {
                if (group.length < m) {
                    group = new int[m];
                    qu = new float[m];
                    qv = new float[m];
                }
                long row = cellKey / cols, col = cellKey % cols;
                for (int q = 0; q < m; q++) {
                    int sample = (int) (keys[i + q] & 0xFFFFFF);
                    int t = s.tri[sample];
                    group[q] = t;
                    if (s.onCentre(sample)) {
                        qu[q] = minU + (col + 0.5f) * cell;
                        qv[q] = minV + (row + 0.5f) * cell;
                    } else {
                        float cu = 0, cv = 0;
                        for (int c = 0; c < 3; c++) {
                            int v = idx[3 * t + c];
                            cu += loc[3 * v + ua];
                            cv += loc[3 * v + va];
                        }
                        qu[q] = cu / 3f;
                        qv[q] = cv / 3f;
                    }
                }
                compare(group, qu, qv, m, loc, idx, n, area, axis, ua, va, sign, gap, cell, edges, scratch);
            }
            i = j;
        }
    }

    /**
     * The samples of one cell: which face lies over which. Only samples near one another in height are compared, so a
     * cell full of leaves costs what its neighbouring leaves cost, not the square of all of them.
     */
    private static void compare(int[] group, float[] qu, float[] qv, int m, float[] loc, int[] idx, float[] n, float[] area,
                                int axis, int ua, int va, float sign, float gap, float cell, Edges edges, Scratch k) {
        k.fit(m);
        for (int q = 0; q < m; q++) {
            k.own[q] = k.height(group[q], qu[q], qv[q], true, loc, idx, axis, ua, va);
            int bits = Float.floatToIntBits(k.own[q]);
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            k.order[q] = ((long) bits << 32) | q;
        }
        Arrays.sort(k.order, 0, m);
        // a face's own height at its sample and its height under a neighbour's sample differ by its slope across the cell
        float window = gap + 1.5f * cell;
        for (int a = 0; a < m; a++) {
            int x = (int) k.order[a];
            for (int b = a + 1; b < m; b++) {
                int y = (int) k.order[b];
                if (k.own[y] - k.own[x] > window) break;
                int tx = group[x], ty = group[y];
                if (tx == ty) continue;
                if (n[3 * tx] * n[3 * ty] + n[3 * tx + 1] * n[3 * ty + 1] + n[3 * tx + 2] * n[3 * ty + 2] < PARALLEL) continue;
                // how far x lies over face y at x's point, or else y over face x at y's point
                float over;
                float hy = k.height(ty, qu[x], qv[x], false, loc, idx, axis, ua, va);
                if (!Float.isNaN(hy)) {
                    over = sign * (k.own[x] - hy);
                } else {
                    float hx = k.height(tx, qu[y], qv[y], false, loc, idx, axis, ua, va);
                    if (Float.isNaN(hx)) continue;
                    over = -sign * (k.own[y] - hx);
                }
                if (over > TIE && over < gap) {
                    edges.add(ty, tx);
                } else if (-over > TIE && -over < gap) {
                    edges.add(tx, ty);
                } else if (Math.abs(over) <= TIE) {
                    // the same plane: the smaller face goes on top
                    boolean xOnTop = area[tx] < area[ty] || (area[tx] == area[ty] && tx > ty);
                    if (xOnTop) edges.add(ty, tx);
                    else edges.add(tx, ty);
                }
            }
        }
    }

    /** Working arrays for {@link #compare}, reused from cell to cell. */
    private static final class Scratch {
        float[] own = new float[64];
        long[] order = new long[64];
        final float[] pu = new float[3], pv = new float[3], h = new float[3];

        void fit(int m) {
            if (own.length < m) {
                own = new float[m];
                order = new long[m];
            }
        }

        /** Height of face t over (u, v); NaN if the point is outside the face, unless {@code anywhere}. */
        float height(int t, float u, float v, boolean anywhere, float[] loc, int[] idx, int axis, int ua, int va) {
            for (int c = 0; c < 3; c++) {
                int vi = idx[3 * t + c];
                pu[c] = loc[3 * vi + ua];
                pv[c] = loc[3 * vi + va];
                h[c] = loc[3 * vi + axis];
            }
            return heightAt(pu, pv, h, u, v, anywhere);
        }
    }

    /**
     * Height of the face's plane over (u, v); NaN unless the point lies inside the face by at least {@link #MARGIN}
     * (or {@code anywhere}). The margin keeps two neighbouring faces of one surface apart: a point on the edge they
     * share belongs to both, and would make each look as if it lay over the other.
     */
    private static float heightAt(float[] pu, float[] pv, float[] h, float u, float v, boolean anywhere) {
        float d = (pv[1] - pv[2]) * (pu[0] - pu[2]) + (pu[2] - pu[1]) * (pv[0] - pv[2]);
        if (Math.abs(d) < 1e-12f) return anywhere ? (h[0] + h[1] + h[2]) / 3f : Float.NaN;
        float w0 = ((pv[1] - pv[2]) * (u - pu[2]) + (pu[2] - pu[1]) * (v - pv[2])) / d;
        float w1 = ((pv[2] - pv[0]) * (u - pu[2]) + (pu[0] - pu[2]) * (v - pv[2])) / d;
        float w2 = 1 - w0 - w1;
        if (!anywhere) {
            // a barycentric weight is the distance to the opposite edge over the height of the triangle on that edge
            float a2 = Math.abs(d);
            if (w0 * a2 < MARGIN * len(pu[1] - pu[2], pv[1] - pv[2])) return Float.NaN;
            if (w1 * a2 < MARGIN * len(pu[2] - pu[0], pv[2] - pv[0])) return Float.NaN;
            if (w2 * a2 < MARGIN * len(pu[0] - pu[1], pv[0] - pv[1])) return Float.NaN;
        }
        return w0 * h[0] + w1 * h[1] + w2 * h[2];
    }

    private static float len(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private static boolean inside(float[] pu, float[] pv, float u, float v, float eps) {
        float d = (pv[1] - pv[2]) * (pu[0] - pu[2]) + (pu[2] - pu[1]) * (pv[0] - pv[2]);
        if (Math.abs(d) < 1e-12f) return false;
        float w0 = ((pv[1] - pv[2]) * (u - pu[2]) + (pu[2] - pu[1]) * (v - pv[2])) / d;
        float w1 = ((pv[2] - pv[0]) * (u - pu[2]) + (pu[0] - pu[2]) * (v - pv[2])) / d;
        return w0 >= -eps && w1 >= -eps && 1 - w0 - w1 >= -eps;
    }

    /** Samples of one facing, grown as needed. At most 16 M of them, since the sample number sits in 24 bits. */
    private static final class Samples {
        long[] keys = new long[1 << 16];
        int[] tri = new int[1 << 16];
        long[] centre = new long[(1 << 16) / 64];
        int size;

        void add(long cellKey, int t, boolean onCentre) {
            if (size >= (1 << 24)) return;
            if (size == keys.length) {
                int grow = Math.min(1 << 24, keys.length * 2);
                keys = Arrays.copyOf(keys, grow);
                tri = Arrays.copyOf(tri, grow);
                centre = Arrays.copyOf(centre, (grow + 63) / 64);
            }
            keys[size] = (cellKey << 24) | size;
            tri[size] = t;
            if (onCentre) centre[size >>> 6] |= 1L << (size & 63);
            size++;
        }

        boolean onCentre(int sample) {
            return (centre[sample >>> 6] & (1L << (sample & 63))) != 0;
        }
    }

    /** Pairs lower -> upper, packed in longs, sorted and without repeats. */
    private static final class Edges {
        long[] a = new long[1 << 12];
        int size;

        void add(int lower, int upper) {
            if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size++] = ((long) lower << 32) | (upper & 0xFFFFFFFFL);
        }

        /**
         * All pairs, sorted, each once - and without the pairs that point both ways. Two faces that each lie over
         * the other somewhere cross or wind through one another; neither is on top, and chaining them would only push
         * both, and everything over them, up to the highest layer.
         */
        static long[] merge(Edges[] parts) {
            int total = 0;
            for (Edges p : parts) total += p.size;
            long[] e = new long[total];
            int at = 0;
            for (Edges p : parts) {
                System.arraycopy(p.a, 0, e, at, p.size);
                at += p.size;
            }
            Arrays.sort(e);
            int w = 0;
            for (int i = 0; i < e.length; i++)
                if (i == 0 || e[i] != e[i - 1]) e[w++] = e[i];
            long[] all = Arrays.copyOf(e, w);
            long[] out = new long[w];
            int keep = 0;
            for (long pair : all) {
                long back = (pair << 32) | (pair >>> 32);
                if (Arrays.binarySearch(all, back) < 0) out[keep++] = pair;
            }
            return Arrays.copyOf(out, keep);
        }
    }
}
