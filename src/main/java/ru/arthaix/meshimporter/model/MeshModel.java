package ru.arthaix.meshimporter.model;

import ru.arthaix.meshimporter.common.MaterialSetup;

/**
 * A model ready for the world: triangles grouped by material, one vertex stream (position, flat normal, uv),
 * the look of every material and the textures it needs. This is what a .mim file holds; the same bytes live on the
 * server (collision) and on every client (rendering + collision).
 *
 * Positions are raw model units; the per-instance transform (scale, axes, rotation, anchor) is applied when the
 * model is loaded into a world.
 */
public final class MeshModel {

    public static final class Material {
        public String name = "";
        public MaterialSetup.Mode mode = MaterialSetup.Mode.BLOCK;
        public String blockId = "minecraft:stone";
        public int color = 0xffffffff;
        public boolean glow;
        /** Index into {@link MeshModel#textures}, or -1. */
        public int texture = -1;

        public boolean translucent() {
            return ((color >>> 24) & 255) < 255;
        }
    }

    public Material[] materials = new Material[0];
    /** Image files (PNG bytes) for TEXTURE materials. */
    public byte[][] textures = new byte[0][];

    public int vertexCount;
    /** x, y, z per vertex. */
    public float[] positions = new float[0];
    /** Flat face normal per vertex, signed bytes (-127..127). */
    public byte[] normals = new byte[0];
    /** u, v per vertex; null when no material uses model UVs. */
    public float[] uvs;

    public int triangleCount;
    /** Three vertex indices per triangle, grouped by material. */
    public int[] indices = new int[0];
    /** Triangle range of every material. */
    public int[] matStart = new int[0], matCount = new int[0];

    /** minX, minY, minZ, maxX, maxY, maxZ in model units. */
    public double[] bounds = new double[6];

    /** SHA-1 (hex) of the encoded file, known once the model went through the codec. */
    public String hash;
    public long encodedBytes;

    public boolean hasUvs() {
        return uvs != null;
    }

    public long estimatedBytes() {
        long b = 4L * positions.length + normals.length + 4L * indices.length;
        if (uvs != null) b += 4L * uvs.length;
        for (byte[] t : textures) b += t.length;
        return b;
    }
}
