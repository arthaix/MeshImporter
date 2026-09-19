package ru.arthaix.meshimporter.web;

import java.nio.ByteBuffer;

import ru.arthaix.meshimporter.light.LightBake;

/**
 * Geometry of a model's web map copy (built by {@link WebProxy}): anchor-relative vertices, triangle indices grouped by
 * material and face direction. Also packs the baked sky light into three RGB slice atlases (+XYZ faces, -XYZ faces,
 * interpolation weight) that the browser samples per pixel, like the in-game shader.
 */
public final class WebMesh {

    /** Anchor-relative positions, three per vertex. */
    public float[] positions;
    public int vertexCount;
    public int[] indices;
    /** Per group: material index, face (EnumFacing index, -1 = any), first index and index count. */
    public int[] groupMaterial, groupFace, groupStart, groupCount;
    /** Surface grid size in blocks. */
    public double cellSize;

    public int triangleCount() {
        return indices.length / 3;
    }

    public static final class LightAtlas {
        /** Anchor-relative block of texel (0, 0, 0), size in texels, blocks per texel. */
        public int x0, y0, z0, w, h, d, step;
        /** Slices along z are tiled cols x rows into images of width x height pixels. */
        public int cols, rows, width, height;
        /** RGB, 3 bytes per pixel: +X +Y +Z faces, -X -Y -Z faces (both premultiplied), weight in red. */
        public byte[] pos, neg, weight;
    }

    public static LightAtlas light(LightBake bake, float[] local, int vertexCount) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int v = 0; v < vertexCount; v++) {
            minX = Math.min(minX, local[3 * v]);
            maxX = Math.max(maxX, local[3 * v]);
            minY = Math.min(minY, local[3 * v + 1]);
            maxY = Math.max(maxY, local[3 * v + 1]);
            minZ = Math.min(minZ, local[3 * v + 2]);
            maxZ = Math.max(maxZ, local[3 * v + 2]);
        }
        LightAtlas a = new LightAtlas();
        a.x0 = (int) Math.floor(minX) - 2;
        a.y0 = (int) Math.floor(minY) - 2;
        a.z0 = (int) Math.floor(minZ) - 2;
        int x1 = (int) Math.ceil(maxX) + 2, y1 = (int) Math.ceil(maxY) + 2, z1 = (int) Math.ceil(maxZ) + 2;
        a.step = 2;
        while (true) {
            a.w = Math.max(1, (x1 - a.x0 + a.step - 1) / a.step);
            a.h = Math.max(1, (y1 - a.y0 + a.step - 1) / a.step);
            a.d = Math.max(1, (z1 - a.z0 + a.step - 1) / a.step);
            a.cols = Math.max(1, (int) Math.ceil(Math.sqrt(a.d * (double) a.h / a.w)));
            a.rows = (a.d + a.cols - 1) / a.cols;
            a.width = a.cols * a.w;
            a.height = a.rows * a.h;
            if (a.width <= 4096 && a.height <= 4096) break;
            a.step *= 2;
        }
        int texels = a.w * a.h * a.d;
        ByteBuffer pos = ByteBuffer.allocate(4 * texels), neg = ByteBuffer.allocate(4 * texels);
        bake.fillTexture(a.x0, a.y0, a.z0, a.w, a.h, a.d, a.step, pos, neg);
        byte[] p = pos.array(), n = neg.array();
        a.pos = new byte[3 * a.width * a.height];
        a.neg = new byte[3 * a.width * a.height];
        a.weight = new byte[3 * a.width * a.height];
        for (int k = 0; k < a.d; k++) {
            int col = k % a.cols, row = k / a.cols;
            for (int j = 0; j < a.h; j++)
                for (int i = 0; i < a.w; i++) {
                    int src = 4 * ((k * a.h + j) * a.w + i);
                    int dst = 3 * ((row * a.h + j) * a.width + col * a.w + i);
                    System.arraycopy(p, src, a.pos, dst, 3);
                    System.arraycopy(n, src, a.neg, dst, 3);
                    a.weight[dst] = p[src + 3];
                }
        }
        return a;
    }
}
