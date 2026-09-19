package ru.arthaix.meshimporter.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.MeshImporterConfig;
import ru.arthaix.meshimporter.common.MaterialSetup;
import ru.arthaix.meshimporter.common.Transform;
import ru.arthaix.meshimporter.instance.MeshInstance;
import ru.arthaix.meshimporter.light.LightBake;
import ru.arthaix.meshimporter.model.MeshModel;
import ru.arthaix.meshimporter.model.MeshModelCodec;

/**
 * Writes placed models into a folder a web map serves:
 * <ul>
 * <li>{@code index.json}: every model with its anchor, geometry description and materials;</li>
 * <li>{@code m/<key>.bin}: simplified geometry, {@code m/<key>-lp/-ln/-lw.png}: baked light atlases, plus
 * {@code .geo.json} and {@code .mat} so the next export skips the heavy work (the key covers model, placement and
 * export settings);</li>
 * <li>{@code t/<name>.png}: block face textures that clients uploaded to the server.</li>
 * </ul>
 * Files of models that are gone are deleted; nothing else in the folder is touched.
 */
public final class WebExporter {

    public static final int FORMAT = 7;
    private static final int MAGIC = 0x3157494D; // "MIW1"
    private static final Pattern MODEL_FILE = Pattern.compile("[0-9a-f]{40}(\\.bin|\\.geo\\.json|\\.mat|-lp\\.png|-ln\\.png|-lw\\.png)");
    private static final Pattern TEXTURE_FILE = Pattern.compile("[a-z0-9_]+-[0-9a-f]{8}\\.png");
    private static final String[] MODEL_SUFFIXES = { ".bin", ".geo.json", ".mat", "-lp.png", "-ln.png", "-lw.png" };

    public interface ModelSource {
        MeshModel load(String hash) throws IOException;
    }

    private final File out, textureStore;
    private final int budget, threads;

    public WebExporter(File out, File textureStore, int budget, int threads) {
        this.out = out;
        this.textureStore = textureStore;
        this.budget = budget;
        this.threads = threads;
    }

    /** File name (without .png) of an uploaded block face texture. */
    public static String textureName(String blockId, int face) {
        String clean = blockId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (clean.length() > 60) clean = clean.substring(0, 60);
        return clean + "_" + face + "-" + MeshModelCodec.sha1((blockId + "#" + face).getBytes(StandardCharsets.UTF_8)).substring(0, 8);
    }

    public static boolean translucent(MeshModel.Material m) {
        String b = m.blockId.toLowerCase(Locale.ROOT);
        return ((m.color >>> 24) & 255) < 255 || (m.mode == MaterialSetup.Mode.BLOCK && (b.contains("glass") || b.contains("ice") || b.contains("slime")));
    }

    public void export(List<MeshInstance> instances, ModelSource models) throws IOException {
        File mDir = new File(out, "m"), tDir = new File(out, "t");
        mDir.mkdirs();
        tDir.mkdirs();
        Set<String> keepModels = new HashSet<>(), keepTextures = new HashSet<>();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"format\":").append(FORMAT).append(",\"generated\":").append(System.currentTimeMillis()).append(",\"instances\":[");
        boolean first = true;
        for (MeshInstance in : instances) {
            String key = key(in);
            File geo = new File(mDir, key + ".geo.json"), mat = new File(mDir, key + ".mat");
            if (!geo.isFile() || !mat.isFile() || !new File(mDir, key + ".bin").isFile()) {
                try {
                    build(in, models.load(in.hash), key, mDir);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    MeshImporter.logger.warn("Web export of model #" + in.id + " failed: " + e);
                    continue;
                }
            }
            for (String s : MODEL_SUFFIXES) keepModels.add(key + s);
            List<String[]> materials = readMaterials(mat);
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(in.id).append(",\"name\":").append(string(in.name)).append(",\"dim\":").append(in.dim);
            sb.append(",\"anchor\":[").append(in.anchorX).append(',').append(in.anchorY).append(',').append(in.anchorZ).append(']');
            sb.append(",\"bounds\":[");
            for (int i = 0; i < 6; i++) sb.append(i > 0 ? "," : "").append(number(in.bounds[i]));
            sb.append("],\"geometry\":").append(new String(Files.readAllBytes(geo.toPath()), StandardCharsets.UTF_8).trim());
            sb.append(",\"materials\":[");
            for (int i = 0; i < materials.size(); i++) {
                if (i > 0) sb.append(',');
                appendMaterial(sb, materials.get(i), tDir, keepTextures);
            }
            sb.append("]}");
        }
        sb.append("]}");
        writeAtomically(new File(out, "index.json"), sb.toString().getBytes(StandardCharsets.UTF_8));
        cleanup(mDir, MODEL_FILE, keepModels);
        cleanup(tDir, TEXTURE_FILE, keepTextures);
    }

    private String key(MeshInstance in) {
        StringBuilder k = new StringBuilder();
        k.append(FORMAT).append('|').append(budget).append('|').append(in.hash).append('|').append(in.anchorX).append(',').append(in.anchorY).append(',').append(in.anchorZ);
        for (double d : in.matrix) k.append('|').append(Double.doubleToLongBits(d));
        k.append('|').append(MeshImporterConfig.webCell);
        k.append('|').append(LightBake.rayLength).append(',').append(LightBake.glassTransmission).append(',').append(LightBake.bounceStrength);
        return MeshModelCodec.sha1(k.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- building one model ----

    private void build(MeshInstance in, MeshModel model, String key, File mDir) throws IOException {
        long t0 = System.currentTimeMillis();
        int n = model.vertexCount;
        float[] local = new float[3 * n];
        byte[] normals = new byte[3 * n];
        double[] m = in.matrix, tmp = new double[3];
        float[] p = model.positions;
        for (int i = 0; i < n; i++) {
            double x = p[3 * i], y = p[3 * i + 1], z = p[3 * i + 2];
            local[3 * i] = (float) (Transform.x(m, x, y, z) - in.anchorX);
            local[3 * i + 1] = (float) (Transform.y(m, x, y, z) - in.anchorY);
            local[3 * i + 2] = (float) (Transform.z(m, x, y, z) - in.anchorZ);
            Transform.normal(m, model.normals[3 * i] / 127.0, model.normals[3 * i + 1] / 127.0, model.normals[3 * i + 2] / 127.0, tmp);
            normals[3 * i] = (byte) Math.round(tmp[0] * 127);
            normals[3 * i + 1] = (byte) Math.round(tmp[1] * 127);
            normals[3 * i + 2] = (byte) Math.round(tmp[2] * 127);
        }
        int nm = model.materials.length;
        boolean[] split = new boolean[nm], translucentMat = new boolean[nm];
        for (int i = 0; i < nm; i++) {
            split[i] = model.materials[i].mode == MaterialSetup.Mode.BLOCK;
            translucentMat[i] = translucent(model.materials[i]);
        }
        WebMesh mesh = WebProxy.build(local, model, split, budget, MeshImporterConfig.webCell);
        if (mesh == null) throw new IOException("model could not be simplified");
        long t1 = System.currentTimeMillis();

        boolean[] triTranslucent = new boolean[model.triangleCount];
        for (int i = 0; i < nm; i++)
            for (int t = model.matStart[i], end = t + model.matCount[i]; t < end; t++) triTranslucent[t] = translucentMat[i];
        LightBake bake = new LightBake(local, model.indices, model.triangleCount, triTranslucent, threads);
        WebMesh.LightAtlas atlas = WebMesh.light(bake, local, n);
        long t2 = System.currentTimeMillis();

        writeBin(new File(mDir, key + ".bin"), mesh);
        writeRgbPng(new File(mDir, key + "-lp.png"), atlas.pos, atlas.width, atlas.height);
        writeRgbPng(new File(mDir, key + "-ln.png"), atlas.neg, atlas.width, atlas.height);
        writeRgbPng(new File(mDir, key + "-lw.png"), atlas.weight, atlas.width, atlas.height);

        StringBuilder mt = new StringBuilder();
        for (int i = 0; i < nm; i++) {
            MeshModel.Material mat = model.materials[i];
            String avg = "-";
            if (mat.mode == MaterialSetup.Mode.TEXTURE && mat.texture >= 0 && mat.texture < model.textures.length) avg = averageColour(model.textures[mat.texture]);
            mt.append(mat.mode.name()).append('\t').append(mat.blockId.replace('\t', ' ')).append('\t').append(String.format("%08x", mat.color)).append('\t')
                .append(mat.glow ? 1 : 0).append('\t').append(avg).append('\t').append(translucentMat[i] ? 1 : 0).append('\n');
        }
        Files.write(new File(mDir, key + ".mat").toPath(), mt.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder g = new StringBuilder();
        g.append("{\"mesh\":\"m/").append(key).append(".bin\",\"vertices\":").append(mesh.vertexCount).append(",\"triangles\":").append(mesh.triangleCount())
            .append(",\"sourceTriangles\":").append(model.triangleCount).append(",\"cell\":").append(number(mesh.cellSize)).append(",\"groups\":[");
        for (int i = 0; i < mesh.groupMaterial.length; i++) {
            if (i > 0) g.append(',');
            g.append('[').append(mesh.groupMaterial[i]).append(',').append(mesh.groupFace[i]).append(',').append(mesh.groupStart[i]).append(',').append(mesh.groupCount[i]).append(']');
        }
        g.append("],\"light\":{\"pos\":\"m/").append(key).append("-lp.png\",\"neg\":\"m/").append(key).append("-ln.png\",\"weight\":\"m/").append(key)
            .append("-lw.png\",\"origin\":[").append(atlas.x0).append(',').append(atlas.y0).append(',').append(atlas.z0).append("],\"dims\":[").append(atlas.w).append(',')
            .append(atlas.h).append(',').append(atlas.d).append("],\"step\":").append(atlas.step).append(",\"grid\":[").append(atlas.cols).append(',').append(atlas.rows)
            .append("],\"size\":[").append(atlas.width).append(',').append(atlas.height).append("]}}");
        // written last: its presence marks a finished export of this key
        writeAtomically(new File(mDir, key + ".geo.json"), g.toString().getBytes(StandardCharsets.UTF_8));
        MeshImporter.logger.info("Web export of model #" + in.id + ": " + String.format("%,d", model.triangleCount) + " -> " + String.format("%,d", mesh.triangleCount())
            + " triangles (surface grid " + String.format(Locale.ROOT, "%.2f", mesh.cellSize) + " blocks) in " + (t1 - t0) + " ms, light " + (t2 - t1) + " ms");
    }

    private static void writeBin(File f, WebMesh mesh) throws IOException {
        boolean small = mesh.vertexCount <= 65535;
        int ib = small ? 2 : 4;
        ByteBuffer bb = ByteBuffer.allocate(20 + 12 * mesh.vertexCount + ib * mesh.indices.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(MAGIC).putInt(FORMAT).putInt(mesh.vertexCount).putInt(mesh.indices.length).put((byte) ib).put((byte) 0).put((byte) 0).put((byte) 0);
        for (int i = 0; i < 3 * mesh.vertexCount; i++) bb.putFloat(mesh.positions[i]);
        for (int i : mesh.indices) {
            if (small) bb.putShort((short) i);
            else bb.putInt(i);
        }
        writeAtomically(f, bb.array());
    }

    private static void writeRgbPng(File f, byte[] rgb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = ((rgb[3 * i] & 255) << 16) | ((rgb[3 * i + 1] & 255) << 8) | (rgb[3 * i + 2] & 255);
        img.setRGB(0, 0, w, h, px, 0, w);
        File tmp = new File(f.getPath() + ".tmp");
        if (!ImageIO.write(img, "png", tmp)) throw new IOException("no PNG writer");
        move(tmp, f);
    }

    private static String averageColour(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return "-";
            long r = 0, g = 0, b = 0, a = 0;
            int w = img.getWidth(), h = img.getHeight(), stepX = Math.max(1, w / 256), stepY = Math.max(1, h / 256);
            for (int y = 0; y < h; y += stepY)
                for (int x = 0; x < w; x += stepX) {
                    int c = img.getRGB(x, y), al = (c >>> 24) & 255;
                    r += ((c >> 16) & 255) * al;
                    g += ((c >> 8) & 255) * al;
                    b += (c & 255) * al;
                    a += al;
                }
            if (a == 0) return "-";
            return String.format("ff%02x%02x%02x", r / a, g / a, b / a);
        } catch (IOException | RuntimeException e) {
            return "-";
        }
    }

    // ---- materials ----

    private static List<String[]> readMaterials(File f) throws IOException {
        List<String[]> list = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length >= 6) list.add(parts);
        }
        return list;
    }

    private void appendMaterial(StringBuilder sb, String[] m, File tDir, Set<String> keepTextures) throws IOException {
        String mode = m[0], block = m[1];
        int colour = (int) Long.parseLong(m[2], 16);
        if ("TEXTURE".equals(mode) && !"-".equals(m[4])) colour = multiply(colour, (int) Long.parseLong(m[4], 16));
        sb.append("{\"mode\":\"").append(mode).append("\",\"color\":").append(rgba(colour)).append(",\"glow\":").append("1".equals(m[3]))
            .append(",\"translucent\":").append("1".equals(m[5]));
        if ("BLOCK".equals(mode)) {
            sb.append(",\"faces\":[");
            for (int face = 0; face < 6; face++) {
                if (face > 0) sb.append(',');
                String name = textureName(block, face);
                File src = new File(textureStore, name + ".png");
                if (!src.isFile()) {
                    sb.append("null");
                    continue;
                }
                File dst = new File(tDir, name + ".png");
                if (!dst.isFile() || dst.length() != src.length() || dst.lastModified() < src.lastModified())
                    Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                keepTextures.add(name + ".png");
                int tint = -1;
                File tf = new File(textureStore, name + ".tint");
                if (tf.isFile()) {
                    try {
                        tint = (int) Long.parseLong(new String(Files.readAllBytes(tf.toPath()), StandardCharsets.UTF_8).trim(), 16);
                    } catch (NumberFormatException ignored) {
                        tint = -1;
                    }
                }
                sb.append("{\"tex\":\"t/").append(name).append(".png\",\"tint\":").append(rgba(tint)).append('}');
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private static int multiply(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    private static String rgba(int argb) {
        return String.format(Locale.ROOT, "[%.3f,%.3f,%.3f,%.3f]", ((argb >> 16) & 255) / 255.0, ((argb >> 8) & 255) / 255.0, (argb & 255) / 255.0, ((argb >>> 24) & 255) / 255.0);
    }

    private static String number(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }

    private static String string(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    // ---- files ----

    private static void writeAtomically(File f, byte[] bytes) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), bytes);
        move(tmp, f);
    }

    private static void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanup(File dir, Pattern ours, Set<String> keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files)
            if (f.isFile() && ours.matcher(f.getName()).matches() && !keep.contains(f.getName()) && !f.delete())
                MeshImporter.logger.warn("Web export: cannot delete old file " + f);
    }
}
