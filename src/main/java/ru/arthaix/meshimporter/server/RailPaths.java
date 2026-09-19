package ru.arthaix.meshimporter.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.arthaix.meshimporter.common.Transform;
import ru.arthaix.meshimporter.instance.MeshInstance;

/**
 * Lines drawn in Blender, kept next to the config as plain JSON: {@code {"Path 1": [[[x,y,z], ...]], ...}} in Blender
 * coordinates (Z up). A placed model says where they land in the world - the same transform its own vertices went
 * through - so a curve drawn along a railway lands exactly on the railway of the model.
 */
public final class RailPaths {

    private RailPaths() {}

    public static File folder() {
        return new File("config/meshimporter/paths");
    }

    /** Every line of a file, by name, in the order the file lists them. */
    public static Map<String, List<double[][]>> read(File file) throws IOException {
        Map<String, List<double[][]>> out = new LinkedHashMap<>();
        try (Reader r = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                List<double[][]> lines = new ArrayList<>();
                for (JsonElement line : e.getValue().getAsJsonArray()) {
                    JsonArray points = line.getAsJsonArray();
                    double[][] pts = new double[points.size()][3];
                    for (int i = 0; i < points.size(); i++) {
                        JsonArray p = points.get(i).getAsJsonArray();
                        for (int c = 0; c < 3; c++) pts[i][c] = p.get(c).getAsDouble();
                    }
                    if (pts.length >= 2) lines.add(pts);
                }
                out.put(e.getKey(), lines);
            }
        }
        return out;
    }

    /**
     * Blender coordinates to world coordinates through a placed model. Blender has Z up, the model file has Y up
     * (x, z, -y), and the instance matrix then puts it where the model stands.
     */
    public static double[][] toWorld(double[][] blender, MeshInstance in) {
        double[] m = in.matrix;
        double[][] out = new double[blender.length][3];
        for (int i = 0; i < blender.length; i++) {
            double x = blender[i][0], y = blender[i][2], z = -blender[i][1];
            out[i][0] = Transform.x(m, x, y, z);
            out[i][1] = Transform.y(m, x, y, z);
            out[i][2] = Transform.z(m, x, y, z);
        }
        return out;
    }

    /** Points along the line every {@code step} blocks, keeping the ends; the shape between them is not cut corners. */
    public static List<double[]> resample(double[][] line, double step) {
        List<double[]> out = new ArrayList<>();
        if (line.length == 0) return out;
        out.add(line[0].clone());
        double carried = 0;
        for (int i = 1; i < line.length; i++) {
            double[] a = line[i - 1], b = line[i];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-9) continue;
            double travelled = -carried;
            while (travelled + step <= len) {
                travelled += step;
                double t = travelled / len;
                out.add(new double[] { a[0] + dx * t, a[1] + dy * t, a[2] + dz * t });
            }
            carried = len - travelled;
        }
        double[] last = line[line.length - 1], tail = out.get(out.size() - 1);
        if (distance(last, tail) > step * 0.25) out.add(last.clone());
        else out.set(out.size() - 1, last.clone());
        return out;
    }

    /** A jump longer than this means the line is drawn in separate runs: they are laid one by one, not joined. */
    public static List<double[][]> split(double[][] line, double maxJump) {
        List<double[][]> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= line.length; i++) {
            boolean end = i == line.length || distance(line[i - 1], line[i]) > maxJump;
            if (!end) continue;
            if (i - start >= 2) {
                double[][] run = new double[i - start][];
                System.arraycopy(line, start, run, 0, i - start);
                runs.add(run);
            }
            start = i;
        }
        return runs;
    }

    public static double distance(double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Minecraft's yaw for a direction: 0 looks towards +Z, 90 towards -X. */
    public static float yaw(double[] from, double[] to) {
        return (float) Math.toDegrees(Math.atan2(-(to[0] - from[0]), to[2] - from[2]));
    }
}
