package ru.arthaix.meshimporter.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact in-memory representation of a triangulated .obj model.
 * Everything is stored in flat primitive arrays: no per-vertex or per-face objects,
 * so a model with millions of triangles costs a few dozen bytes per triangle instead of hundreds.
 *
 * Coordinates are raw model units (1 unit = 1 block after the user's scale factor is applied).
 * Y is up, as in the .obj file; axis conversion is done by the voxelizer.
 */
public class ObjMesh {

    /** x,y,z interleaved. Length = 3 * vertexCount. */
    public float[] positions;
    public int vertexCount;

    /** u,v interleaved. Length = 2 * uvCount. */
    public float[] texCoords;
    public int uvCount;

    /** Three vertex indices per triangle (0-based). Length = 3 * triangleCount. */
    public int[] triVertex;
    /** Three tex-coord indices per triangle, or -1 when the face has no uv. Length = 3 * triangleCount. */
    public int[] triUv;
    /** Material index per triangle (index into {@link #materials}). */
    public int[] triMaterial;
    public int triangleCount;

    /** Material names in first-use order. Index 0 is always the implicit "default" material. */
    public final List<String> materials = new ArrayList<>();
    /** mtllib file names as written in the .obj (relative to the .obj directory). */
    public final List<String> mtlFiles = new ArrayList<>();

    /** Number of faces that were not triangles and got fan-triangulated. */
    public int ngonFaces;
    /** Number of faces skipped because they referenced invalid indices. */
    public int invalidFaces;

    public float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
    public float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

    public boolean isEmpty() {
        return triangleCount == 0;
    }

    public int materialIndex(String name) {
        int idx = materials.indexOf(name);
        if (idx < 0) {
            materials.add(name);
            idx = materials.size() - 1;
        }
        return idx;
    }

    /** Estimated heap footprint of the arrays, for log output. */
    public long estimatedBytes() {
        long b = 0;
        if (positions != null) b += 4L * positions.length;
        if (texCoords != null) b += 4L * texCoords.length;
        if (triVertex != null) b += 4L * triVertex.length;
        if (triUv != null) b += 4L * triUv.length;
        if (triMaterial != null) b += 4L * triMaterial.length;
        return b;
    }

    /** Trims the growable arrays to their used length. */
    public void trim() {
        positions = trim(positions, 3 * vertexCount);
        texCoords = trim(texCoords, 2 * uvCount);
        triVertex = trim(triVertex, 3 * triangleCount);
        triUv = trim(triUv, 3 * triangleCount);
        triMaterial = trim(triMaterial, triangleCount);
    }

    private static float[] trim(float[] a, int len) {
        if (a == null) return new float[0];
        if (a.length == len) return a;
        float[] r = new float[len];
        System.arraycopy(a, 0, r, 0, len);
        return r;
    }

    private static int[] trim(int[] a, int len) {
        if (a == null) return new int[0];
        if (a.length == len) return a;
        int[] r = new int[len];
        System.arraycopy(a, 0, r, 0, len);
        return r;
    }
}
