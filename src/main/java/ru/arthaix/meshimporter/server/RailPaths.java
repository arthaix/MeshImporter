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

    /**
     * Cuts a line into the pieces track will be laid in. A piece is a smooth curve between two points that meets the
     * line's own direction at both ends, so it is stretched as far as it can go while staying within {@code tolerance}
     * blocks of the drawn line - long runs on the straights, shorter ones through the curves, the way rail is laid.
     * Each piece comes back as {start, end, {heading at the start, heading at the end}}.
     */
    public static List<double[][]> pieces(double[][] run, double tolerance, double maxLength) {
        List<double[][]> out = new ArrayList<>();
        int i = 0;
        while (i < run.length - 1) {
            // the farthest point still within one piece
            int far = i;
            double length = 0;
            while (far < run.length - 1 && length + distance(run[far], run[far + 1]) <= maxLength) {
                length += distance(run[far], run[far + 1]);
                far++;
            }
            if (far <= i) break;
            float yawA = tangent(run, i);
            int best = i + 1;
            int lo = i + 1, hi = far;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (error(run, i, mid, yawA, tangent(run, mid)) <= tolerance) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            // a sharp kink in the drawing can otherwise end a piece where it started
            while (best < run.length - 1 && distance(run[i], run[best]) < 0.5) best++;
            out.add(new double[][] { run[i], run[best], { yawA, tangent(run, best) } });
            i = best;
        }
        return out;
    }

    /** Where the line points at this vertex, measured over a few blocks so a short segment cannot wobble it. */
    public static float tangent(double[][] run, int i) {
        int back = i;
        double travelled = 0;
        while (back > 0 && travelled < 4) {
            travelled += distance(run[back - 1], run[back]);
            back--;
        }
        int fwd = i;
        travelled = 0;
        while (fwd < run.length - 1 && travelled < 4) {
            travelled += distance(run[fwd], run[fwd + 1]);
            fwd++;
        }
        return yaw(run[back], run[fwd]);
    }

    /** How far the smooth curve of a piece strays from the line it stands for, at its worst. */
    public static double error(double[][] run, int i, int j, float yawA, float yawB) {
        double[] a = run[i], b = run[j];
        double chord = distance(a, b), arm = chord / 3;
        double[] ca = control(a, yawA, arm, (b[1] - a[1]) / 3);
        double[] cb = control(b, yawB + 180, arm, -(b[1] - a[1]) / 3);
        double worst = 0;
        for (int k = 1; k < 13; k++) {
            double t = k / 13.0, u = 1 - t;
            double[] q = new double[3];
            for (int c = 0; c < 3; c++)
                q[c] = u * u * u * a[c] + 3 * u * u * t * ca[c] + 3 * u * t * t * cb[c] + t * t * t * b[c];
            double best = Double.MAX_VALUE;
            for (int n = i; n < j; n++) best = Math.min(best, toSegment(q, run[n], run[n + 1]));
            worst = Math.max(worst, best);
        }
        return worst;
    }

    /** A Bezier control point: along the heading from one end, a third of the way to the other. */
    public static double[] control(double[] from, float yaw, double arm, double rise) {
        return new double[] { from[0] - Math.sin(Math.toRadians(yaw)) * arm, from[1] + rise, from[2] + Math.cos(Math.toRadians(yaw)) * arm };
    }

    private static double toSegment(double[] q, double[] a, double[] b) {
        double vx = b[0] - a[0], vy = b[1] - a[1], vz = b[2] - a[2];
        double len2 = vx * vx + vy * vy + vz * vz;
        if (len2 < 1e-12) return distance(q, a);
        double t = ((q[0] - a[0]) * vx + (q[1] - a[1]) * vy + (q[2] - a[2]) * vz) / len2;
        t = Math.max(0, Math.min(1, t));
        return distance(q, new double[] { a[0] + vx * t, a[1] + vy * t, a[2] + vz * t });
    }

    /** Minecraft's yaw for a direction: 0 looks towards +Z, 90 towards -X. */
    public static float yaw(double[] from, double[] to) {
        return (float) Math.toDegrees(Math.atan2(-(to[0] - from[0]), to[2] - from[2]));
    }
}
