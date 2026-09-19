package ru.arthaix.meshimporter.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import ru.arthaix.meshimporter.common.MaterialSetup;

/**
 * Turns a parsed .obj plus the material choices into a {@link MeshModel}: drops skipped materials and degenerate
 * triangles, gives every triangle a flat normal, shares identical vertices, groups triangles by material and packs
 * the textures the model needs.
 */
public final class MeshBuilder {

    public interface Progress {
        /** 0..1; return false to cancel. */
        boolean onProgress(float fraction);
    }

    private MeshBuilder() {}

    /**
     * @param setups   one per mesh material index (missing = default), may contain skipped materials
     * @param objFile  for resolving relative texture paths
     * @param problems receives human-readable warnings
     */
    public static MeshModel build(ObjMesh mesh, MtlLibrary mtl, List<MaterialSetup> setups, File objFile, List<String> problems, Progress progress) {
        int matCount = mesh.materials.size();
        MeshModel out = new MeshModel();

        // ---- materials and textures ----
        List<MeshModel.Material> mats = new ArrayList<>();
        List<byte[]> textures = new ArrayList<>();
        int[] outIndex = new int[matCount]; // mesh material → output material, -1 = skipped
        boolean anyUv = false;
        int[] rawCount = new int[matCount];
        for (int t = 0; t < mesh.triangleCount; t++) rawCount[mesh.triMaterial[t]]++;
        for (int i = 0; i < matCount; i++) {
            MaterialSetup s = i < setups.size() ? setups.get(i) : null;
            if (s == null) {
                s = new MaterialSetup(mesh.materials.get(i));
            }
            if (s.skip || rawCount[i] == 0) {
                outIndex[i] = -1;
                continue;
            }
            MeshModel.Material m = new MeshModel.Material();
            m.name = s.name;
            m.mode = s.mode;
            m.blockId = s.blockId;
            m.color = s.color;
            m.glow = s.glow;
            if (s.mode == MaterialSetup.Mode.TEXTURE) {
                MtlLibrary.Material mm = mtl == null ? null : mtl.materials.get(s.name);
                String path = s.texturePath;
                if ((path == null || path.isEmpty()) && mm != null) path = mm.mapKd;
                byte[] png = path == null || path.isEmpty() ? null : loadPng(path, objFile, s.name, problems);
                if (png == null) {
                    if (path == null || path.isEmpty()) problems.add("material '" + s.name + "': no texture (map_Kd), using the flat colour");
                    m.mode = MaterialSetup.Mode.KD;
                } else {
                    m.texture = textures.size();
                    textures.add(png);
                    anyUv = true;
                }
            }
            outIndex[i] = mats.size();
            mats.add(m);
        }
        out.materials = mats.toArray(new MeshModel.Material[0]);
        out.textures = textures.toArray(new byte[0][]);

        // ---- count triangles per output material ----
        int[] counts = new int[mats.size()];
        for (int t = 0; t < mesh.triangleCount; t++) {
            int oi = outIndex[mesh.triMaterial[t]];
            if (oi >= 0) counts[oi]++;
        }
        int total = 0;
        out.matStart = new int[mats.size()];
        out.matCount = new int[mats.size()];
        for (int i = 0; i < mats.size(); i++) {
            out.matStart[i] = total;
            out.matCount[i] = counts[i];
            total += counts[i];
        }
        int[] fill = Arrays.copyOf(out.matStart, mats.size());
        int[] order = new int[total]; // mesh triangle index in output order
        for (int t = 0; t < mesh.triangleCount; t++) {
            int oi = outIndex[mesh.triMaterial[t]];
            if (oi >= 0) order[fill[oi]++] = t;
        }

        // ---- vertices ----
        VertexTable table = new VertexTable(Math.max(16, total * 2));
        float[] pos = new float[Math.max(48, total * 3 * 3 / 2)];
        byte[] nrm = new byte[pos.length];
        float[] uv = anyUv ? new float[pos.length / 3 * 2] : null;
        int[] idx = new int[total * 3];
        int vcount = 0, tcount = 0;
        float[] p = mesh.positions;
        float[] tc = mesh.texCoords;
        double[] bounds = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        int[] newStart = new int[mats.size()], newCount = new int[mats.size()];
        int curMat = -1;
        int reportEvery = Math.max(1, total / 50);
        for (int k = 0; k < total; k++) {
            if (progress != null && k % reportEvery == 0 && !progress.onProgress((float) k / total)) return null;
            int t = order[k];
            int oi = outIndex[mesh.triMaterial[t]];
            if (oi != curMat) {
                if (curMat >= 0) newCount[curMat] = tcount - newStart[curMat];
                curMat = oi;
                newStart[oi] = tcount;
            }
            int a = mesh.triVertex[3 * t], b = mesh.triVertex[3 * t + 1], c = mesh.triVertex[3 * t + 2];
            double ax = p[3 * a], ay = p[3 * a + 1], az = p[3 * a + 2];
            double bx = p[3 * b], by = p[3 * b + 1], bz = p[3 * b + 2];
            double cx = p[3 * c], cy = p[3 * c + 1], cz = p[3 * c + 2];
            double ux = bx - ax, uy = by - ay, uz = bz - az;
            double vx = cx - ax, vy = cy - ay, vz = cz - az;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12) continue; // degenerate
            nx /= len;
            ny /= len;
            nz /= len;
            byte qx = (byte) Math.round(nx * 127), qy = (byte) Math.round(ny * 127), qz = (byte) Math.round(nz * 127);
            int packedN = ((qx & 255) << 16) | ((qy & 255) << 8) | (qz & 255);
            boolean withUv = uv != null && mats.get(oi).texture >= 0;
            int[] verts = { a, b, c };
            for (int corner = 0; corner < 3; corner++) {
                int vi = verts[corner];
                int ti = withUv ? mesh.triUv[3 * t + corner] : -1;
                int found = table.find(vi, ti, packedN, oi);
                if (found < 0) {
                    if (3 * vcount + 3 > pos.length) {
                        pos = Arrays.copyOf(pos, pos.length * 3 / 2 + 48);
                        nrm = Arrays.copyOf(nrm, pos.length);
                        if (uv != null) uv = Arrays.copyOf(uv, pos.length / 3 * 2);
                    }
                    found = vcount++;
                    pos[3 * found] = p[3 * vi];
                    pos[3 * found + 1] = p[3 * vi + 1];
                    pos[3 * found + 2] = p[3 * vi + 2];
                    nrm[3 * found] = qx;
                    nrm[3 * found + 1] = qy;
                    nrm[3 * found + 2] = qz;
                    if (uv != null) {
                        uv[2 * found] = ti >= 0 ? tc[2 * ti] : 0;
                        uv[2 * found + 1] = ti >= 0 ? 1f - tc[2 * ti + 1] : 0; // obj v runs bottom-up, GL textures top-down
                    }
                    table.insert(vi, ti, packedN, oi, found);
                    double x = p[3 * vi], y = p[3 * vi + 1], z = p[3 * vi + 2];
                    if (x < bounds[0]) bounds[0] = x;
                    if (y < bounds[1]) bounds[1] = y;
                    if (z < bounds[2]) bounds[2] = z;
                    if (x > bounds[3]) bounds[3] = x;
                    if (y > bounds[4]) bounds[4] = y;
                    if (z > bounds[5]) bounds[5] = z;
                }
                idx[3 * tcount + corner] = found;
            }
            tcount++;
        }
        if (curMat >= 0) newCount[curMat] = tcount - newStart[curMat];
        for (int i = 0; i < mats.size(); i++) {
            out.matStart[i] = newStart[i];
            out.matCount[i] = newCount[i];
        }
        out.vertexCount = vcount;
        out.positions = Arrays.copyOf(pos, 3 * vcount);
        out.normals = Arrays.copyOf(nrm, 3 * vcount);
        out.uvs = uv == null ? null : Arrays.copyOf(uv, 2 * vcount);
        out.triangleCount = tcount;
        out.indices = Arrays.copyOf(idx, 3 * tcount);
        out.bounds = vcount == 0 ? new double[6] : bounds;
        if (progress != null) progress.onProgress(1f);
        return out;
    }

    private static byte[] loadPng(String path, File objFile, String material, List<String> problems) {
        File f = new File(path);
        if (!f.isAbsolute() && objFile != null) f = new File(objFile.getAbsoluteFile().getParentFile(), path);
        if (!f.isFile()) {
            problems.add("material '" + material + "': texture not found: " + f);
            return null;
        }
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            if (raw.length > 8 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G') return raw;
            BufferedImage img = ImageIO.read(f);
            if (img == null) throw new IOException("unsupported image format");
            ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            problems.add("material '" + material + "': cannot read texture " + f + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /** Open-addressing table: (position index, uv index, packed normal, material) → output vertex. */
    private static final class VertexTable {
        private int[] keyPos, keyUv, keyNormal, keyMat, value;
        private int mask, size;

        VertexTable(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected) - 1) << 1;
            alloc(cap);
        }

        private void alloc(int cap) {
            keyPos = new int[cap];
            keyUv = new int[cap];
            keyNormal = new int[cap];
            keyMat = new int[cap];
            value = new int[cap];
            Arrays.fill(value, -1);
            mask = cap - 1;
            size = 0;
        }

        private static int hash(int pos, int uv, int normal, int mat) {
            int h = pos * 0x9E3779B1 ^ (uv + 1) * 0x85EBCA77 ^ normal * 0xC2B2AE3D ^ mat * 0x27D4EB2F;
            return h ^ (h >>> 15);
        }

        int find(int pos, int uv, int normal, int mat) {
            int i = hash(pos, uv, normal, mat) & mask;
            while (value[i] >= 0) {
                if (keyPos[i] == pos && keyUv[i] == uv && keyNormal[i] == normal && keyMat[i] == mat) return value[i];
                i = (i + 1) & mask;
            }
            return -1;
        }

        void insert(int pos, int uv, int normal, int mat, int v) {
            if (size * 2 >= value.length) grow();
            int i = hash(pos, uv, normal, mat) & mask;
            while (value[i] >= 0) i = (i + 1) & mask;
            keyPos[i] = pos;
            keyUv[i] = uv;
            keyNormal[i] = normal;
            keyMat[i] = mat;
            value[i] = v;
            size++;
        }

        private void grow() {
            int[] oPos = keyPos, oUv = keyUv, oN = keyNormal, oM = keyMat, oV = value;
            alloc(oV.length * 2);
            for (int i = 0; i < oV.length; i++)
                if (oV[i] >= 0) insert(oPos[i], oUv[i], oN[i], oM[i], oV[i]);
        }
    }
}
