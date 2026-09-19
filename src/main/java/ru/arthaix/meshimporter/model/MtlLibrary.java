package ru.arthaix.meshimporter.model;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal .mtl reader: newmtl, Kd, d / Tr, map_Kd. Everything else is ignored. */
public final class MtlLibrary {

    public static final class Material {
        public final String name;
        public float kdR = 0.8f, kdG = 0.8f, kdB = 0.8f;
        public float alpha = 1f;
        /** map_Kd path as written (may be relative to the .mtl directory), or null. */
        public String mapKd;

        Material(String name) {
            this.name = name;
        }

        /** Kd as opaque-or-translucent ARGB. */
        public int kdArgb() {
            int a = Math.round(clamp(alpha) * 255f);
            int r = Math.round(clamp(kdR) * 255f);
            int g = Math.round(clamp(kdG) * 255f);
            int b = Math.round(clamp(kdB) * 255f);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static float clamp(float f) {
            return f < 0 ? 0 : f > 1 ? 1 : f;
        }
    }

    public final Map<String, Material> materials = new LinkedHashMap<>();

    /** Loads every mtllib named by the mesh; missing files are skipped and reported in the returned list. */
    public static MtlLibrary load(File objFile, Iterable<String> mtlNames, java.util.List<String> problems) {
        MtlLibrary lib = new MtlLibrary();
        File dir = objFile.getAbsoluteFile().getParentFile();
        for (String name : mtlNames) {
            File f = new File(name);
            if (!f.isAbsolute()) f = new File(dir, name);
            if (!f.isFile()) {
                problems.add("mtl not found: " + f);
                continue;
            }
            try {
                lib.read(f);
            } catch (IOException e) {
                problems.add("mtl unreadable: " + f + " (" + e.getMessage() + ")");
            }
        }
        return lib;
    }

    public void read(File file) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            Material cur = null;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] tok = line.split("\\s+");
                String key = tok[0];
                if (key.equals("newmtl")) {
                    String name = line.substring(6).trim();
                    cur = new Material(name);
                    materials.put(name, cur);
                } else if (cur == null) {
                    continue;
                } else if (key.equals("Kd") && tok.length >= 4) {
                    cur.kdR = f(tok[1]);
                    cur.kdG = f(tok[2]);
                    cur.kdB = f(tok[3]);
                } else if (key.equals("d") && tok.length >= 2) {
                    cur.alpha = f(tok[1]);
                } else if (key.equals("Tr") && tok.length >= 2) {
                    cur.alpha = 1f - f(tok[1]);
                } else if (key.equals("map_Kd") && tok.length >= 2) {
                    // options like "-s 1 1 1" may precede the file name; take the trailing part after the last option
                    String path = line.substring(6).trim();
                    int lastOpt = -1;
                    for (int i = 1; i < tok.length; i++)
                        if (tok[i].startsWith("-")) lastOpt = i;
                    if (lastOpt >= 0) {
                        // skip the option and its numeric arguments
                        int j = lastOpt + 1;
                        while (j < tok.length - 1 && isNumber(tok[j])) j++;
                        StringBuilder sb = new StringBuilder();
                        for (int k = j; k < tok.length; k++) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(tok[k]);
                        }
                        path = sb.toString();
                    }
                    File tex = new File(path);
                    if (!tex.isAbsolute()) tex = new File(dir, path);
                    cur.mapKd = tex.getPath();
                }
            }
        }
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static float f(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
