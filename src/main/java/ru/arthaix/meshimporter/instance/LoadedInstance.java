package ru.arthaix.meshimporter.instance;

import ru.arthaix.meshimporter.collision.TriangleGrid;
import ru.arthaix.meshimporter.common.Transform;
import ru.arthaix.meshimporter.model.MeshModel;

/**
 * A model placed in a world: vertices transformed into anchor-relative world space, plus the collision grid.
 * The client hangs its render data on {@link #render}.
 */
public final class LoadedInstance {

    public final MeshInstance instance;
    public final MeshModel model;
    /** x, y, z per vertex, relative to the anchor block. */
    public final float[] local;
    /** Transformed flat normals, signed bytes. */
    public final byte[] normals;
    public final TriangleGrid grid;
    /** Client render data (RenderData), null until built. */
    public volatile Object render;
    /** Preview instances exist only on the client and are never sent anywhere. */
    public boolean preview;

    private LoadedInstance(MeshInstance instance, MeshModel model, float[] local, byte[] normals, TriangleGrid grid) {
        this.instance = instance;
        this.model = model;
        this.local = local;
        this.normals = normals;
        this.grid = grid;
    }

    /** CPU work of a few hundred ms for a city; call it off the game thread. */
    public static LoadedInstance load(MeshInstance inst, MeshModel model) {
        double[] m = inst.matrix;
        int n = model.vertexCount;
        float[] local = new float[3 * n];
        byte[] normals = new byte[3 * n];
        double[] tmp = new double[3];
        float[] p = model.positions;
        for (int i = 0; i < n; i++) {
            double x = p[3 * i], y = p[3 * i + 1], z = p[3 * i + 2];
            local[3 * i] = (float) (Transform.x(m, x, y, z) - inst.anchorX);
            local[3 * i + 1] = (float) (Transform.y(m, x, y, z) - inst.anchorY);
            local[3 * i + 2] = (float) (Transform.z(m, x, y, z) - inst.anchorZ);
            Transform.normal(m, model.normals[3 * i] / 127.0, model.normals[3 * i + 1] / 127.0, model.normals[3 * i + 2] / 127.0, tmp);
            normals[3 * i] = (byte) Math.round(tmp[0] * 127);
            normals[3 * i + 1] = (byte) Math.round(tmp[1] * 127);
            normals[3 * i + 2] = (byte) Math.round(tmp[2] * 127);
        }
        int t = model.triangleCount;
        float[] tris = new float[9 * t];
        int[] idx = model.indices;
        for (int i = 0; i < t; i++) {
            for (int c = 0; c < 3; c++) {
                int vi = idx[3 * i + c];
                tris[9 * i + 3 * c] = local[3 * vi];
                tris[9 * i + 3 * c + 1] = local[3 * vi + 1];
                tris[9 * i + 3 * c + 2] = local[3 * vi + 2];
            }
        }
        TriangleGrid grid = new TriangleGrid(tris, t, inst.anchorX, inst.anchorY, inst.anchorZ);
        return new LoadedInstance(inst, model, local, normals, grid);
    }
}
