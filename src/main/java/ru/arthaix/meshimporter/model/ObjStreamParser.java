package ru.arthaix.meshimporter.model;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single-pass streaming .obj parser that fills an {@link ObjMesh}.
 *
 * Only v / vt / f / usemtl / mtllib are interpreted; vn, o, g, s and everything else are skipped.
 * Faces with more than three vertices are fan-triangulated on the fly. Negative (relative) indices are
 * supported. Parsing is done with a hand-rolled tokenizer: no String.split, no Scanner, no regex,
 * so a 1M-triangle file is read in a couple of seconds.
 */
public final class ObjStreamParser {

    public interface Progress {
        /** Called every few MB of input. bytesRead / totalBytes (totalBytes may be -1). Return false to cancel. */
        boolean onProgress(long bytesRead, long totalBytes);
    }

    private ObjStreamParser() {}

    /**
     * Cheap pre-pass: returns the material names (usemtl) in order of first use, plus mtllib names.
     * Reads the file once but does no numeric parsing; used by the GUI to build the material list.
     */
    public static MaterialScan scanMaterials(File file) throws IOException {
        MaterialScan scan = new MaterialScan();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            boolean sawFaceBeforeMaterial = false;
            boolean sawMaterial = false;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                char c = line.charAt(0);
                if (c == 'u' && line.startsWith("usemtl")) {
                    scan.materials.add(line.substring(6).trim());
                    sawMaterial = true;
                } else if (c == 'm' && line.startsWith("mtllib")) {
                    scan.mtlFiles.add(line.substring(6).trim());
                } else if (c == 'f' && !sawMaterial) {
                    sawFaceBeforeMaterial = true;
                }
            }
            if (sawFaceBeforeMaterial || scan.materials.isEmpty())
                scan.hasDefaultMaterial = true;
        }
        return scan;
    }

    public static final class MaterialScan {
        public final Set<String> materials = new LinkedHashSet<>();
        public final List<String> mtlFiles = new ArrayList<>();
        /** true when some faces precede the first usemtl (they belong to the implicit "default" material). */
        public boolean hasDefaultMaterial;

        public List<String> materialNames() {
            List<String> names = new ArrayList<>();
            if (hasDefaultMaterial) names.add(ObjMesh_DEFAULT);
            names.addAll(materials);
            return names;
        }
    }

    public static final String ObjMesh_DEFAULT = "default";

    public static ObjMesh parse(File file, Progress progress) throws IOException {
        ObjMesh mesh = new ObjMesh();
        mesh.materialIndex(ObjMesh_DEFAULT);
        mesh.positions = new float[3 * 1024];
        mesh.texCoords = new float[2 * 1024];
        mesh.triVertex = new int[3 * 2048];
        mesh.triUv = new int[3 * 2048];
        mesh.triMaterial = new int[2048];

        long total = file.length();
        long read = 0;
        long nextReport = 4L << 20;
        int currentMaterial = 0;
        int[] faceV = new int[16];
        int[] faceT = new int[16];

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                read += line.length() + 1;
                if (progress != null && read >= nextReport) {
                    nextReport = read + (4L << 20);
                    if (!progress.onProgress(read, total))
                        throw new IOException("cancelled");
                }
                int len = line.length();
                if (len < 2) continue;
                char c0 = line.charAt(0);
                char c1 = line.charAt(1);

                if (c0 == 'v') {
                    if (c1 == ' ' || c1 == '\t') {
                        parseVertex(mesh, line);
                    } else if (c1 == 't' && len > 2 && isSpace(line.charAt(2))) {
                        parseTexCoord(mesh, line);
                    }
                    // vn / vp: ignored
                } else if (c0 == 'f' && isSpace(c1)) {
                    int n = 0;
                    int i = 2;
                    while (i < len) {
                        while (i < len && isSpace(line.charAt(i))) i++;
                        if (i >= len) break;
                        int end = i;
                        while (end < len && !isSpace(line.charAt(end))) end++;
                        if (n == faceV.length) {
                            faceV = grow(faceV);
                            faceT = grow(faceT);
                        }
                        // token is v, v/t, v//n or v/t/n
                        int slash1 = indexOf(line, '/', i, end);
                        int vi = parseInt(line, i, slash1 < 0 ? end : slash1);
                        int ti = 0;
                        if (slash1 >= 0) {
                            int slash2 = indexOf(line, '/', slash1 + 1, end);
                            int tEnd = slash2 < 0 ? end : slash2;
                            if (tEnd > slash1 + 1)
                                ti = parseInt(line, slash1 + 1, tEnd);
                        }
                        faceV[n] = resolve(vi, mesh.vertexCount);
                        faceT[n] = ti == 0 ? -1 : resolve(ti, mesh.uvCount);
                        n++;
                        i = end;
                    }
                    if (n < 3) {
                        mesh.invalidFaces++;
                        continue;
                    }
                    boolean valid = true;
                    for (int k = 0; k < n; k++) {
                        if (faceV[k] < 0 || faceV[k] >= mesh.vertexCount || faceT[k] >= mesh.uvCount) {
                            valid = false;
                            break;
                        }
                    }
                    if (!valid) {
                        mesh.invalidFaces++;
                        continue;
                    }
                    if (n > 3) mesh.ngonFaces++;
                    for (int k = 1; k + 1 < n; k++)
                        addTriangle(mesh, faceV[0], faceV[k], faceV[k + 1], faceT[0], faceT[k], faceT[k + 1], currentMaterial);
                } else if (c0 == 'u' && line.startsWith("usemtl")) {
                    currentMaterial = mesh.materialIndex(line.substring(6).trim());
                } else if (c0 == 'm' && line.startsWith("mtllib")) {
                    mesh.mtlFiles.add(line.substring(6).trim());
                }
            }
        }
        mesh.trim();
        if (progress != null) progress.onProgress(total, total);
        return mesh;
    }

    private static void parseVertex(ObjMesh mesh, String line) {
        int len = line.length();
        int i = 1;
        float[] xyz = new float[3];
        for (int k = 0; k < 3; k++) {
            while (i < len && isSpace(line.charAt(i))) i++;
            int end = i;
            while (end < len && !isSpace(line.charAt(end))) end++;
            if (end == i) return; // malformed
            xyz[k] = parseFloat(line, i, end);
            i = end;
        }
        if (3 * mesh.vertexCount + 3 > mesh.positions.length)
            mesh.positions = grow(mesh.positions);
        int o = 3 * mesh.vertexCount;
        mesh.positions[o] = xyz[0];
        mesh.positions[o + 1] = xyz[1];
        mesh.positions[o + 2] = xyz[2];
        mesh.vertexCount++;
        if (xyz[0] < mesh.minX) mesh.minX = xyz[0];
        if (xyz[0] > mesh.maxX) mesh.maxX = xyz[0];
        if (xyz[1] < mesh.minY) mesh.minY = xyz[1];
        if (xyz[1] > mesh.maxY) mesh.maxY = xyz[1];
        if (xyz[2] < mesh.minZ) mesh.minZ = xyz[2];
        if (xyz[2] > mesh.maxZ) mesh.maxZ = xyz[2];
    }

    private static void parseTexCoord(ObjMesh mesh, String line) {
        int len = line.length();
        int i = 2;
        float u = 0, v = 0;
        for (int k = 0; k < 2; k++) {
            while (i < len && isSpace(line.charAt(i))) i++;
            int end = i;
            while (end < len && !isSpace(line.charAt(end))) end++;
            if (end == i) break; // "vt u" alone is legal
            float f = parseFloat(line, i, end);
            if (k == 0) u = f; else v = f;
            i = end;
        }
        if (2 * mesh.uvCount + 2 > mesh.texCoords.length)
            mesh.texCoords = grow(mesh.texCoords);
        int o = 2 * mesh.uvCount;
        mesh.texCoords[o] = u;
        mesh.texCoords[o + 1] = v;
        mesh.uvCount++;
    }

    private static void addTriangle(ObjMesh mesh, int a, int b, int c, int ta, int tb, int tc, int material) {
        if (3 * mesh.triangleCount + 3 > mesh.triVertex.length) {
            mesh.triVertex = grow(mesh.triVertex);
            mesh.triUv = grow(mesh.triUv);
            mesh.triMaterial = grow(mesh.triMaterial);
        }
        int o = 3 * mesh.triangleCount;
        mesh.triVertex[o] = a;
        mesh.triVertex[o + 1] = b;
        mesh.triVertex[o + 2] = c;
        mesh.triUv[o] = ta;
        mesh.triUv[o + 1] = tb;
        mesh.triUv[o + 2] = tc;
        mesh.triMaterial[mesh.triangleCount] = material;
        mesh.triangleCount++;
    }

    /** obj indices are 1-based; negative values are relative to the current count. */
    private static int resolve(int idx, int count) {
        return idx > 0 ? idx - 1 : count + idx;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }

    private static int indexOf(String s, char c, int from, int to) {
        for (int i = from; i < to; i++)
            if (s.charAt(i) == c) return i;
        return -1;
    }

    private static int parseInt(String s, int from, int to) {
        int i = from;
        boolean neg = false;
        if (i < to && s.charAt(i) == '-') {
            neg = true;
            i++;
        } else if (i < to && s.charAt(i) == '+') {
            i++;
        }
        int v = 0;
        for (; i < to; i++) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9) break;
            v = v * 10 + d;
        }
        return neg ? -v : v;
    }

    /** Fast decimal parser for the common "-12.345" / "1e-05" forms; falls back to Float.parseFloat. */
    private static float parseFloat(String s, int from, int to) {
        int i = from;
        boolean neg = false;
        if (i < to && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        double v = 0;
        boolean any = false;
        while (i < to) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9) break;
            v = v * 10 + d;
            any = true;
            i++;
        }
        if (i < to && s.charAt(i) == '.') {
            i++;
            double scale = 0.1;
            while (i < to) {
                int d = s.charAt(i) - '0';
                if (d < 0 || d > 9) break;
                v += d * scale;
                scale *= 0.1;
                any = true;
                i++;
            }
        }
        if (i < to && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int exp = parseInt(s, i + 1, to);
            v *= Math.pow(10, exp);
            i = to;
        }
        if (!any || i != to) {
            try {
                return Float.parseFloat(s.substring(from, to));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return (float) (neg ? -v : v);
    }

    private static float[] grow(float[] a) {
        float[] r = new float[a.length + (a.length >> 1) + 16];
        System.arraycopy(a, 0, r, 0, a.length);
        return r;
    }

    private static int[] grow(int[] a) {
        int[] r = new int[a.length + (a.length >> 1) + 16];
        System.arraycopy(a, 0, r, 0, a.length);
        return r;
    }
}
