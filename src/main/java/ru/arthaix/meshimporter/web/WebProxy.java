package ru.arthaix.meshimporter.web;

import java.util.Arrays;

import ru.arthaix.meshimporter.collision.TriangleGrid;
import ru.arthaix.meshimporter.model.MeshModel;

/**
 * Clean stand-in surface of a model for the web map. The model is rasterised into a voxel grid, gaps up to about a
 * block are closed, the air reachable from outside is flooded, and the boundary of what remains is extracted with
 * surface nets on a grid twice as coarse (sized to the triangle budget) and lightly smoothed. Every face takes the
 * material seen from outside there (rays from in front of the face into the model; the first surface hit votes), so
 * facades read as glass or panels without the spikes and slivers that direct simplification of thin frames produces.
 * Interiors are not represented.
 */
public final class WebProxy {

    private static final long MAX_VOXELS = 120_000_000L;
    /** Ray offsets across a face, in units of its two edges. */
    private static final double[] RAY_U = { 0, 0.25, -0.25, 0.25, -0.25 }, RAY_W = { 0, 0.25, 0.25, -0.25, -0.25 };

    private interface Sink {
        void sample(int material, int voxel);
    }

    private WebProxy() {}

    public static WebMesh build(float[] local, MeshModel model, boolean[] splitFaces, int budget) {
        double[] b = bounds(local, model.vertexCount);
        double ex = b[3] - b[0], ey = b[4] - b[1], ez = b[5] - b[2];
        double area = 2.4 * (ex * ey + ey * ez + ex * ez) + 1;
        double s = Math.max(0.25, Math.sqrt(2 * area / Math.max(1000, budget)));
        int[] idx = model.indices;
        float[] tris = new float[9 * model.triangleCount];
        int[] triMat = new int[model.triangleCount];
        for (int m = 0; m < model.materials.length; m++)
            for (int t = model.matStart[m], end = t + model.matCount[m]; t < end; t++) {
                triMat[t] = m;
                for (int c = 0; c < 3; c++) System.arraycopy(local, 3 * idx[3 * t + c], tris, 9 * t + 3 * c, 3);
            }
        TriangleGrid grid = new TriangleGrid(tris, model.triangleCount, 0, 0, 0);
        WebMesh best = null, last = null;
        int refinements = 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            WebMesh m = attempt(local, model, splitFaces, b, s, grid, triMat);
            if (m != null) last = m;
            if (m != null && m.triangleCount() <= budget) {
                if (best == null || m.triangleCount() > best.triangleCount()) best = m;
                if (m.triangleCount() >= 0.6 * budget || refinements++ >= 2 || s <= 0.25) break;
                s = Math.max(0.25, s / Math.min(2.0, Math.sqrt((double) budget / Math.max(1, m.triangleCount())) * 0.95));
                continue;
            }
            if (best != null) break;
            double ratio = m == null ? 4 : (double) m.triangleCount() / budget;
            s *= Math.max(1.1, Math.sqrt(ratio) * 1.05);
        }
        return best != null ? best : last;
    }

    private static WebMesh attempt(float[] p, MeshModel model, boolean[] split, double[] b, double s, TriangleGrid grid, int[] triMat) {
        final double v = s / 2;
        int r = Math.max(1, (int) Math.round(1.0 / v));
        int pad = 2 * ((r + 4) / 2);
        double ox = Math.floor(b[0] / s) * s - pad * v, oy = Math.floor(b[1] / s) * s - pad * v, oz = Math.floor(b[2] / s) * s - pad * v;
        int nx = even((int) Math.ceil((b[3] - ox) / v) + pad), ny = even((int) Math.ceil((b[4] - oy) / v) + pad), nz = even((int) Math.ceil((b[5] - oz) / v) + pad);
        if ((long) nx * ny * nz > MAX_VOXELS) return null;
        int nm = model.materials.length;

        // surface voxels, closed gaps, outside air
        byte[] surface = new byte[nx * ny * nz];
        samples(p, model, v, ox, oy, oz, nx, ny, nz, (m, f) -> surface[f] = 1);
        byte[] closed = dilate(surface, nx, ny, nz, r);
        byte[] outside = dilate(flood(closed, nx, ny, nz), nx, ny, nz, r);

        // which materials face the outside, per coarse cell
        int cx = nx / 2, cy = ny / 2, cz = nz / 2, sxy = nx * ny;
        LongIntMap hist = new LongIntMap(1 << 16);
        long[] totals = new long[nm];
        samples(p, model, v, ox, oy, oz, nx, ny, nz, (m, f) -> {
            if (outside[f] == 0 && outside[f - 1] == 0 && outside[f + 1] == 0 && outside[f - nx] == 0 && outside[f + nx] == 0 && outside[f - sxy] == 0 && outside[f + sxy] == 0)
                return;
            int ix = f % nx, iy = (f / nx) % ny, iz = f / sxy;
            long cell = (ix >> 1) + (long) cx * ((iy >> 1) + (long) cy * (iz >> 1));
            hist.add(cell * nm + m, 1);
            totals[m]++;
        });
        int fallback = 0;
        for (int m = 1; m < nm; m++) if (totals[m] > totals[fallback]) fallback = m;

        // solid share of every coarse cell, lattice densities at cell corners
        float[] frac = new float[cx * cy * cz];
        for (int iz = 0; iz < 2 * cz; iz++)
            for (int iy = 0; iy < 2 * cy; iy++)
                for (int ix = 0; ix < 2 * cx; ix++)
                    if (outside[ix + nx * (iy + ny * iz)] == 0) frac[(ix >> 1) + cx * ((iy >> 1) + cy * (iz >> 1))] += 0.125f;
        int lx = cx + 1, ly = cy + 1, lz = cz + 1;
        float[] dens = new float[lx * ly * lz];
        for (int k = 0; k < lz; k++)
            for (int j = 0; j < ly; j++)
                for (int i = 0; i < lx; i++) {
                    float sum = 0;
                    for (int dk = -1; dk <= 0; dk++)
                        for (int dj = -1; dj <= 0; dj++)
                            for (int di = -1; di <= 0; di++) {
                                int a = i + di, bb = j + dj, c = k + dk;
                                if (a >= 0 && bb >= 0 && c >= 0 && a < cx && bb < cy && c < cz) sum += frac[a + cx * (bb + cy * c)];
                            }
                    dens[i + lx * (j + ly * k)] = sum / 8;
                }

        // surface nets: a vertex in every cell the surface crosses
        int[] vid = new int[cx * cy * cz];
        Arrays.fill(vid, -1);
        float[] pos = new float[3 * 4096];
        int[] vcell = new int[3 * 4096];
        int nv = 0;
        double[] d = new double[8];
        for (int c = 0; c < cz; c++)
            for (int bb = 0; bb < cy; bb++)
                for (int a = 0; a < cx; a++) {
                    int mask = 0;
                    for (int corner = 0; corner < 8; corner++) {
                        d[corner] = dens[(a + (corner & 1)) + lx * ((bb + ((corner >> 1) & 1)) + ly * (c + ((corner >> 2) & 1)))];
                        if (d[corner] > 0.5) mask |= 1 << corner;
                    }
                    if (mask == 0 || mask == 255) continue;
                    double sx = 0, sy = 0, sz = 0;
                    int crossings = 0;
                    for (int e = 0; e < 12; e++) {
                        int c0 = EDGES[2 * e], c1 = EDGES[2 * e + 1];
                        if (((mask >> c0) & 1) == ((mask >> c1) & 1)) continue;
                        double t = (0.5 - d[c0]) / (d[c1] - d[c0]);
                        sx += (c0 & 1) + t * ((c1 & 1) - (c0 & 1));
                        sy += ((c0 >> 1) & 1) + t * (((c1 >> 1) & 1) - ((c0 >> 1) & 1));
                        sz += ((c0 >> 2) & 1) + t * (((c1 >> 2) & 1) - ((c0 >> 2) & 1));
                        crossings++;
                    }
                    if (3 * nv + 3 > pos.length) {
                        pos = Arrays.copyOf(pos, 2 * pos.length);
                        vcell = Arrays.copyOf(vcell, 2 * vcell.length);
                    }
                    pos[3 * nv] = (float) (ox + (a + sx / crossings) * s);
                    pos[3 * nv + 1] = (float) (oy + (bb + sy / crossings) * s);
                    pos[3 * nv + 2] = (float) (oz + (c + sz / crossings) * s);
                    vcell[3 * nv] = a;
                    vcell[3 * nv + 1] = bb;
                    vcell[3 * nv + 2] = c;
                    vid[a + cx * (bb + cy * c)] = nv++;
                }

        // quads across lattice edges with a sign change, oriented outwards, with the outside material around them
        int[] quads = new int[4 * 4096];
        int[] quadMat = new int[4096];
        int nq = 0;
        int[] acc = new int[nm];
        int[] cells = new int[4];
        for (int axis = 0; axis < 3; axis++) {
            for (int k = (axis == 2 ? 0 : 1); k < (axis == 2 ? cz : cz); k++)
                for (int j = (axis == 1 ? 0 : 1); j < cy; j++)
                    for (int i = (axis == 0 ? 0 : 1); i < cx; i++) {
                        boolean s0 = dens[i + lx * (j + ly * k)] > 0.5;
                        int i1 = i + (axis == 0 ? 1 : 0), j1 = j + (axis == 1 ? 1 : 0), k1 = k + (axis == 2 ? 1 : 0);
                        boolean s1 = dens[i1 + lx * (j1 + ly * k1)] > 0.5;
                        if (s0 == s1) continue;
                        if (axis == 0) {
                            cells[0] = cell(cx, cy, i, j - 1, k - 1);
                            cells[1] = cell(cx, cy, i, j, k - 1);
                            cells[2] = cell(cx, cy, i, j, k);
                            cells[3] = cell(cx, cy, i, j - 1, k);
                        } else if (axis == 1) {
                            cells[0] = cell(cx, cy, i - 1, j, k - 1);
                            cells[1] = cell(cx, cy, i - 1, j, k);
                            cells[2] = cell(cx, cy, i, j, k);
                            cells[3] = cell(cx, cy, i, j, k - 1);
                        } else {
                            cells[0] = cell(cx, cy, i - 1, j - 1, k);
                            cells[1] = cell(cx, cy, i, j - 1, k);
                            cells[2] = cell(cx, cy, i, j, k);
                            cells[3] = cell(cx, cy, i - 1, j, k);
                        }
                        if (4 * nq + 4 > quads.length) {
                            quads = Arrays.copyOf(quads, 2 * quads.length);
                            quadMat = Arrays.copyOf(quadMat, 2 * quadMat.length);
                        }
                        for (int q = 0; q < 4; q++) quads[4 * nq + (s0 ? q : 3 - q)] = vid[cells[q]];
                        quadMat[nq] = material(hist, cells, nm, cx, cy, cz, acc, fallback);
                        nq++;
                    }
        }

        smooth(pos, vcell, nv, quads, nq, ox, oy, oz, s);

        // the material seen from outside: rays from in front of each face into the model, first surface hit votes
        TriangleGrid.Query query = grid.newQuery();
        double reach = Math.max(2.5, 2 * s);
        for (int q = 0; q < nq; q++) {
            int q0 = 3 * quads[4 * q], q1 = 3 * quads[4 * q + 1], q2 = 3 * quads[4 * q + 2], q3 = 3 * quads[4 * q + 3];
            double ux = pos[q1] - pos[q0], uy = pos[q1 + 1] - pos[q0 + 1], uz = pos[q1 + 2] - pos[q0 + 2];
            double wx = pos[q3] - pos[q0], wy = pos[q3 + 1] - pos[q0 + 1], wz = pos[q3 + 2] - pos[q0 + 2];
            double fx = uy * wz - uz * wy, fy = uz * wx - ux * wz, fz = ux * wy - uy * wx;
            double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (len < 1e-9) continue;
            fx /= len;
            fy /= len;
            fz /= len;
            double mx = (pos[q0] + pos[q1] + pos[q2] + pos[q3]) / 4, my = (pos[q0 + 1] + pos[q1 + 1] + pos[q2 + 1] + pos[q3 + 1]) / 4, mz = (pos[q0 + 2] + pos[q1 + 2] + pos[q2 + 2] + pos[q3 + 2]) / 4;
            Arrays.fill(acc, 0);
            int hits = 0;
            for (int ray = 0; ray < RAY_U.length; ray++) {
                double px = mx + RAY_U[ray] * ux + RAY_W[ray] * wx, py = my + RAY_U[ray] * uy + RAY_W[ray] * wy, pz = mz + RAY_U[ray] * uz + RAY_W[ray] * wz;
                TriangleGrid.Hit h = grid.raycast(query, px + fx * reach, py + fy * reach, pz + fz * reach, px - fx * reach, py - fy * reach, pz - fz * reach);
                if (h == null) continue;
                acc[triMat[h.triangle]]++;
                hits++;
            }
            if (hits == 0) continue;
            int bestMat = 0;
            for (int m = 1; m < nm; m++) if (acc[m] > acc[bestMat]) bestMat = m;
            quadMat[q] = bestMat;
        }
        smoothMaterials(quads, quadMat, nq, nm);

        // triangles per (material, face) group
        int groups = nm * 6;
        int[] groupOf = new int[2 * nq];
        int[] tris = new int[6 * nq];
        int[] gCount = new int[groups];
        int nt = 0;
        for (int q = 0; q < nq; q++) {
            int q0 = quads[4 * q], q1 = quads[4 * q + 1], q2 = quads[4 * q + 2], q3 = quads[4 * q + 3];
            boolean diag02 = dist2(pos, q0, q2) <= dist2(pos, q1, q3);
            int[][] pair = diag02 ? new int[][] { { q0, q1, q2 }, { q0, q2, q3 } } : new int[][] { { q0, q1, q3 }, { q1, q2, q3 } };
            int m = quadMat[q];
            for (int[] t : pair) {
                int face = split[m] ? face(pos, t[0], t[1], t[2]) : 0;
                if (face < 0) continue;
                int g = m * 6 + face;
                tris[3 * nt] = t[0];
                tris[3 * nt + 1] = t[1];
                tris[3 * nt + 2] = t[2];
                groupOf[nt++] = g;
                gCount[g]++;
            }
        }
        int[] gStart = new int[groups];
        for (int g = 1; g < groups; g++) gStart[g] = gStart[g - 1] + gCount[g - 1];
        int[] fill = Arrays.copyOf(gStart, groups);
        int[] indices = new int[3 * nt];
        for (int t = 0; t < nt; t++) {
            int o = 3 * fill[groupOf[t]]++;
            indices[o] = tris[3 * t];
            indices[o + 1] = tris[3 * t + 1];
            indices[o + 2] = tris[3 * t + 2];
        }

        WebMesh w = new WebMesh();
        w.positions = Arrays.copyOf(pos, 3 * nv);
        w.vertexCount = nv;
        w.indices = indices;
        w.cellSize = s;
        int ng = 0;
        for (int g = 0; g < groups; g++) if (gCount[g] > 0) ng++;
        w.groupMaterial = new int[ng];
        w.groupFace = new int[ng];
        w.groupStart = new int[ng];
        w.groupCount = new int[ng];
        int gi = 0;
        for (int g = 0; g < groups; g++) {
            if (gCount[g] == 0) continue;
            w.groupMaterial[gi] = g / 6;
            w.groupFace[gi] = split[g / 6] ? g % 6 : -1;
            w.groupStart[gi] = 3 * gStart[g];
            w.groupCount[gi] = 3 * gCount[g];
            gi++;
        }
        return w;
    }

    /** Corner pairs of the 12 cube edges (corner bits: x, y, z). */
    private static final int[] EDGES = { 0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7 };

    private static int cell(int cx, int cy, int a, int b, int c) {
        return a + cx * (b + cy * c);
    }

    private static int even(int n) {
        return (n + 1) & ~1;
    }

    private static int material(LongIntMap hist, int[] cells, int nm, int cx, int cy, int cz, int[] acc, int fallback) {
        Arrays.fill(acc, 0);
        boolean any = false;
        for (int cellIndex : cells)
            for (int m = 0; m < nm; m++) {
                int n = hist.get((long) cellIndex * nm + m);
                if (n > 0) {
                    acc[m] += n;
                    any = true;
                }
            }
        if (!any) {
            // no outside sample in these cells: look one cell further
            for (int cellIndex : cells) {
                int a = cellIndex % cx, b = (cellIndex / cx) % cy, c = cellIndex / (cx * cy);
                for (int dc = -1; dc <= 1; dc++)
                    for (int db = -1; db <= 1; db++)
                        for (int da = -1; da <= 1; da++) {
                            int x = a + da, y = b + db, z = c + dc;
                            if (x < 0 || y < 0 || z < 0 || x >= cx || y >= cy || z >= cz) continue;
                            long base = (long) (x + cx * (y + cy * z)) * nm;
                            for (int m = 0; m < nm; m++) {
                                int n = hist.get(base + m);
                                if (n > 0) {
                                    acc[m] += n;
                                    any = true;
                                }
                            }
                        }
            }
        }
        if (!any) return fallback;
        int best = 0;
        for (int m = 1; m < nm; m++) if (acc[m] > acc[best]) best = m;
        return best;
    }

    /**
     * Mode filter over faces sharing an edge (two passes): a face keeps its material only when it is not outvoted by
     * its neighbours, so single stray faces vanish while bands at least a face wide stay.
     */
    private static void smoothMaterials(int[] quads, int[] quadMat, int nq, int nm) {
        long[] edges = new long[4 * nq];
        for (int q = 0; q < nq; q++)
            for (int e = 0; e < 4; e++) {
                long a = quads[4 * q + e], b = quads[4 * q + (e + 1) % 4];
                edges[4 * q + e] = (Math.min(a, b) << 42) | (Math.max(a, b) << 21) | q;
            }
        Arrays.sort(edges);
        int[] pairs = new int[8 * nq];
        int np = 0;
        // neighbours: consecutive entries with the same edge (upper 42 bits)
        int[][] neighbours = new int[nq][];
        int[] count = new int[nq];
        for (int i = 0; i + 1 < edges.length; i++) {
            if ((edges[i] >>> 21) != (edges[i + 1] >>> 21)) continue;
            int a = (int) (edges[i] & 0x1FFFFF), b = (int) (edges[i + 1] & 0x1FFFFF);
            if (np + 2 > pairs.length) pairs = Arrays.copyOf(pairs, 2 * pairs.length);
            pairs[np++] = a;
            pairs[np++] = b;
            count[a]++;
            count[b]++;
        }
        for (int q = 0; q < nq; q++) neighbours[q] = new int[count[q]];
        Arrays.fill(count, 0);
        for (int i = 0; i < np; i += 2) {
            int a = pairs[i], b = pairs[i + 1];
            neighbours[a][count[a]++] = b;
            neighbours[b][count[b]++] = a;
        }
        int[] votes = new int[nm];
        int[] next = new int[nq];
        for (int pass = 0; pass < 2; pass++) {
            for (int q = 0; q < nq; q++) {
                for (int n : neighbours[q]) votes[quadMat[n]] += 2;
                votes[quadMat[q]] += 3;
                int best = quadMat[q];
                for (int n : neighbours[q]) if (votes[quadMat[n]] > votes[best]) best = quadMat[n];
                next[q] = best;
                for (int n : neighbours[q]) votes[quadMat[n]] = 0;
                votes[quadMat[q]] = 0;
            }
            System.arraycopy(next, 0, quadMat, 0, nq);
        }
    }

    /** Two passes of Laplacian smoothing along quad edges, each vertex kept inside its cell. */
    private static void smooth(float[] pos, int[] vcell, int nv, int[] quads, int nq, double ox, double oy, double oz, double s) {
        double[] sum = new double[3 * nv];
        int[] cnt = new int[nv];
        for (int pass = 0; pass < 2; pass++) {
            Arrays.fill(sum, 0);
            Arrays.fill(cnt, 0);
            for (int q = 0; q < nq; q++)
                for (int e = 0; e < 4; e++) {
                    int a = quads[4 * q + e], b = quads[4 * q + (e + 1) % 4];
                    for (int c = 0; c < 3; c++) {
                        sum[3 * a + c] += pos[3 * b + c];
                        sum[3 * b + c] += pos[3 * a + c];
                    }
                    cnt[a]++;
                    cnt[b]++;
                }
            double[] origin = { ox, oy, oz };
            for (int vtx = 0; vtx < nv; vtx++) {
                if (cnt[vtx] == 0) continue;
                for (int c = 0; c < 3; c++) {
                    double avg = sum[3 * vtx + c] / cnt[vtx];
                    double nvalue = pos[3 * vtx + c] + 0.5 * (avg - pos[3 * vtx + c]);
                    double lo = origin[c] + vcell[3 * vtx + c] * s, hi = lo + s;
                    pos[3 * vtx + c] = (float) Math.max(lo, Math.min(hi, nvalue));
                }
            }
        }
    }

    private static double dist2(float[] p, int a, int b) {
        double dx = p[3 * a] - p[3 * b], dy = p[3 * a + 1] - p[3 * b + 1], dz = p[3 * a + 2] - p[3 * b + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** EnumFacing index of the triangle's outward normal, -1 for a degenerate triangle. */
    private static int face(float[] p, int a, int b, int c) {
        double ux = p[3 * b] - p[3 * a], uy = p[3 * b + 1] - p[3 * a + 1], uz = p[3 * b + 2] - p[3 * a + 2];
        double vx = p[3 * c] - p[3 * a], vy = p[3 * c + 1] - p[3 * a + 1], vz = p[3 * c + 2] - p[3 * a + 2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax + ay + az < 1e-9) return -1;
        if (ay >= ax && ay >= az) return ny >= 0 ? 1 : 0;
        if (az >= ax) return nz >= 0 ? 3 : 2;
        return nx >= 0 ? 5 : 4;
    }

    private static double[] bounds(float[] p, int n) {
        double[] b = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        for (int i = 0; i < n; i++)
            for (int c = 0; c < 3; c++) {
                b[c] = Math.min(b[c], p[3 * i + c]);
                b[3 + c] = Math.max(b[3 + c], p[3 * i + c]);
            }
        if (n == 0) Arrays.fill(b, 0);
        return b;
    }

    /** Points on every triangle at most half a voxel apart, as (material, voxel index). */
    private static void samples(float[] p, MeshModel model, double v, double ox, double oy, double oz, int nx, int ny, int nz, Sink sink) {
        int[] idx = model.indices;
        double inv = 1 / v;
        for (int m = 0; m < model.materials.length; m++)
            for (int t = model.matStart[m], end = t + model.matCount[m]; t < end; t++) {
                int a = 3 * idx[3 * t], b = 3 * idx[3 * t + 1], c = 3 * idx[3 * t + 2];
                double ax = (p[a] - ox) * inv, ay = (p[a + 1] - oy) * inv, az = (p[a + 2] - oz) * inv;
                double e1x = (p[b] - p[a]) * inv, e1y = (p[b + 1] - p[a + 1]) * inv, e1z = (p[b + 2] - p[a + 2]) * inv;
                double e2x = (p[c] - p[a]) * inv, e2y = (p[c + 1] - p[a + 1]) * inv, e2z = (p[c + 2] - p[a + 2]) * inv;
                double l = Math.max(e1x * e1x + e1y * e1y + e1z * e1z,
                    Math.max(e2x * e2x + e2y * e2y + e2z * e2z, (e2x - e1x) * (e2x - e1x) + (e2y - e1y) * (e2y - e1y) + (e2z - e1z) * (e2z - e1z)));
                int n = Math.max(1, (int) Math.ceil(2 * Math.sqrt(l)));
                for (int i = 0; i <= n; i++)
                    for (int j = 0; i + j <= n; j++) {
                        int ix = (int) (ax + (e1x * i + e2x * j) / n), iy = (int) (ay + (e1y * i + e2y * j) / n), iz = (int) (az + (e1z * i + e2z * j) / n);
                        if (ix < 1 || iy < 1 || iz < 1 || ix >= nx - 1 || iy >= ny - 1 || iz >= nz - 1) continue;
                        sink.sample(m, ix + nx * (iy + ny * iz));
                    }
            }
    }

    /** Cube dilation by r voxels (separable). */
    private static byte[] dilate(byte[] in, int nx, int ny, int nz, int r) {
        byte[] a = new byte[in.length], b = new byte[in.length];
        for (int z = 0; z < nz; z++)
            for (int y = 0; y < ny; y++) pass(in, a, nx * (y + ny * z), 1, nx, r);
        for (int z = 0; z < nz; z++)
            for (int x = 0; x < nx; x++) pass(a, b, x + nx * ny * z, nx, ny, r);
        for (int y = 0; y < ny; y++)
            for (int x = 0; x < nx; x++) pass(b, a, x + nx * y, nx * ny, nz, r);
        return a;
    }

    private static void pass(byte[] src, byte[] dst, int start, int stride, int len, int r) {
        int count = 0;
        for (int k = 0; k <= Math.min(r, len - 1); k++) count += src[start + k * stride];
        for (int i = 0; i < len; i++) {
            dst[start + i * stride] = (byte) (count > 0 ? 1 : 0);
            int add = i + r + 1, rem = i - r;
            if (add < len) count += src[start + add * stride];
            if (rem >= 0) count -= src[start + rem * stride];
        }
    }

    /** Voxels reachable from the grid border without crossing blocked ones (6-connected). */
    private static byte[] flood(byte[] blocked, int nx, int ny, int nz) {
        byte[] out = new byte[blocked.length];
        int sxy = nx * ny;
        int[] queue = new int[1 << 20];
        int head = 0, size = 0;
        for (int z = 0; z < nz; z++)
            for (int y = 0; y < ny; y++)
                for (int x = 0; x < nx; x++) {
                    if (x != 0 && y != 0 && z != 0 && x != nx - 1 && y != ny - 1 && z != nz - 1) continue;
                    int f = x + nx * (y + ny * z);
                    if (blocked[f] != 0 || out[f] != 0) continue;
                    out[f] = 1;
                    if (size == queue.length) {
                        queue = grow(queue, head, size);
                        head = 0;
                    }
                    queue[(head + size++) % queue.length] = f;
                }
        while (size > 0) {
            int f = queue[head];
            head = (head + 1) % queue.length;
            size--;
            int x = f % nx, y = (f / nx) % ny, z = f / sxy;
            for (int dir = 0; dir < 6; dir++) {
                int g;
                switch (dir) {
                    case 0: if (x == 0) continue; g = f - 1; break;
                    case 1: if (x == nx - 1) continue; g = f + 1; break;
                    case 2: if (y == 0) continue; g = f - nx; break;
                    case 3: if (y == ny - 1) continue; g = f + nx; break;
                    case 4: if (z == 0) continue; g = f - sxy; break;
                    default: if (z == nz - 1) continue; g = f + sxy;
                }
                if (blocked[g] != 0 || out[g] != 0) continue;
                out[g] = 1;
                if (size == queue.length) {
                    queue = grow(queue, head, size);
                    head = 0;
                }
                queue[(head + size++) % queue.length] = g;
            }
        }
        return out;
    }

    private static int[] grow(int[] queue, int head, int size) {
        int[] q = new int[queue.length * 2];
        for (int i = 0; i < size; i++) q[i] = queue[(head + i) % queue.length];
        return q;
    }

    /** Open-addressing long -> int counter map. */
    private static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private boolean[] used;
        private int size, mask;

        LongIntMap(int capacity) {
            int cap = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
            keys = new long[cap];
            values = new int[cap];
            used = new boolean[cap];
            mask = cap - 1;
        }

        int get(long key) {
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) return values[i];
                i = (i + 1) & mask;
            }
            return 0;
        }

        void add(long key, int delta) {
            if (2 * (size + 1) > keys.length) grow();
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    values[i] += delta;
                    return;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = key;
            values[i] = delta;
            size++;
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = values;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            values = new int[ok.length * 2];
            used = new boolean[ok.length * 2];
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ou[i]) add(ok[i], ov[i]);
        }

        private static int hash(long k) {
            k ^= k >>> 33;
            k *= 0xFF51AFD7ED558CCDL;
            k ^= k >>> 33;
            return (int) k;
        }
    }
}
