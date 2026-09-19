package ru.arthaix.meshimporter.common;

/**
 * Model space → world space as a 4x4 affine matrix (row-major, {@code world = M * model}).
 *
 * Order of operations, identical to the block importer: scale → file up-axis → mirror inside the model's bounding
 * box (the box does not move) → snap (the box corner or the lowest point onto the anchor) → rotation around the
 * anchor → anchor position + offset. Mirrors and offsets are given in Blender axes (Z up) and converted here.
 */
public final class Transform {

    private Transform() {}

    /** Bounding box of the model after scale and axis conversion, over the given vertices (or all when used == null). */
    public static double[] scaledBounds(float[] positions, int vertexCount, boolean[] used, ImportSettings s) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < vertexCount; i++) {
            if (used != null && !used[i]) continue;
            double x = positions[3 * i] * s.scale, y = positions[3 * i + 1] * s.scale, z = positions[3 * i + 2] * s.scale;
            if (s.zUp) {
                double ny = z, nz = -y;
                y = ny;
                z = nz;
            }
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        if (minX > maxX) return new double[] { 0, 0, 0, 0, 0, 0 };
        return new double[] { minX, minY, minZ, maxX, maxY, maxZ };
    }

    /**
     * @param bounds result of {@link #scaledBounds}
     * @param ox     anchor block position
     */
    public static double[] matrix(ImportSettings s, double[] bounds, int ox, int oy, int oz) {
        double[] m = identity();
        // 1. scale
        m = mul(scale(s.scale, s.scale, s.scale), m);
        // 2. file axis: Z up → Y up: (x, y, z) → (x, z, -y)
        if (s.zUp) m = mul(new double[] { 1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1 }, m);
        // 3. mirror inside the bounding box; GUI axes are Blender's: X = X, Y (forward) = -Z, Z (up) = Y
        boolean mx = s.mirrorX, my = s.mirrorZ, mz = s.mirrorY;
        if (mx || my || mz) {
            double[] flip = identity();
            if (mx) {
                flip[0] = -1;
                flip[3] = bounds[0] + bounds[3];
            }
            if (my) {
                flip[5] = -1;
                flip[7] = bounds[1] + bounds[4];
            }
            if (mz) {
                flip[10] = -1;
                flip[11] = bounds[2] + bounds[5];
            }
            m = mul(flip, m);
        }
        // 4. snap
        boolean alignXZ = s.snapXZ && !s.modelOrigin, alignY = s.snapY && !s.modelOrigin;
        double ax = alignXZ ? bounds[0] : 0, ay = alignY ? bounds[1] : 0, az = alignXZ ? bounds[2] : 0;
        m = mul(translate(-ax, -ay, -az), m);
        // 5. rotation around the vertical axis (quarter turns: (x, z) → (-z, x))
        for (int r = 0; r < (s.rotation & 3); r++) m = mul(new double[] { 0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1 }, m);
        // 6. anchor + offset (offset in Blender axes)
        m = mul(translate(ox + s.offsetX, oy + s.offsetZ, oz - s.offsetY), m);
        return m;
    }

    public static double[] identity() {
        return new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
    }

    public static double[] translate(double x, double y, double z) {
        return new double[] { 1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1 };
    }

    public static double[] scale(double x, double y, double z) {
        return new double[] { x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1 };
    }

    /** a * b */
    public static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double v = 0;
                for (int k = 0; k < 4; k++) v += a[4 * i + k] * b[4 * k + j];
                r[4 * i + j] = v;
            }
        return r;
    }

    public static double x(double[] m, double x, double y, double z) {
        return m[0] * x + m[1] * y + m[2] * z + m[3];
    }

    public static double y(double[] m, double x, double y, double z) {
        return m[4] * x + m[5] * y + m[6] * z + m[7];
    }

    public static double z(double[] m, double x, double y, double z) {
        return m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    /** Transforms a direction with the linear part (for normals; the matrix has no shear, so the inverse transpose is not needed up to scale). */
    public static void normal(double[] m, double nx, double ny, double nz, double[] out) {
        double x = m[0] * nx + m[1] * ny + m[2] * nz;
        double y = m[4] * nx + m[5] * ny + m[6] * nz;
        double z = m[8] * nx + m[9] * ny + m[10] * nz;
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-12) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        out[0] = x / len;
        out[1] = y / len;
        out[2] = z / len;
    }

    /** World bounding box of a model box (all 8 corners transformed). */
    public static double[] bounds(double[] m, double[] box) {
        double[] r = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        for (int c = 0; c < 8; c++) {
            double x = (c & 1) == 0 ? box[0] : box[3];
            double y = (c & 2) == 0 ? box[1] : box[4];
            double z = (c & 4) == 0 ? box[2] : box[5];
            double wx = x(m, x, y, z), wy = y(m, x, y, z), wz = z(m, x, y, z);
            r[0] = Math.min(r[0], wx);
            r[1] = Math.min(r[1], wy);
            r[2] = Math.min(r[2], wz);
            r[3] = Math.max(r[3], wx);
            r[4] = Math.max(r[4], wy);
            r[5] = Math.max(r[5], wz);
        }
        return r;
    }
}
