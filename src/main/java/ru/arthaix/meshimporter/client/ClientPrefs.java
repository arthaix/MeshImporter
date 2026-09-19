package ru.arthaix.meshimporter.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.common.MaterialSetup;

/** Recently used model files and material presets (material name → look), in config/meshimporter-client.json. */
public final class ClientPrefs {

    public static final int MAX_RECENT = 12;
    public static final int MAX_RECENT_BLOCKS = 18;

    public static final class Preset {
        public String block;
        public int mode;
        public int color;
        public boolean glow;
    }

    private static final class Data {
        List<String> recent = new ArrayList<>();
        List<String> recentBlocks = new ArrayList<>();
        Map<String, Preset> materials = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data;

    private ClientPrefs() {}

    private static File file() {
        return new File(Minecraft.getMinecraft().gameDir, "config/meshimporter-client.json");
    }

    private static synchronized Data data() {
        if (data == null) {
            data = new Data();
            File f = file();
            if (f.isFile()) {
                try {
                    Data d = GSON.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Data.class);
                    if (d != null) {
                        if (d.recent != null) data.recent = d.recent;
                        if (d.materials != null) data.materials = d.materials;
                        if (d.recentBlocks != null) data.recentBlocks = d.recentBlocks;
                    }
                } catch (Exception e) {
                    MeshImporter.logger.warn("Cannot read " + f + ": " + e);
                }
            }
        }
        return data;
    }

    private static synchronized void save() {
        File f = file();
        try {
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), GSON.toJson(data()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            MeshImporter.logger.warn("Cannot write " + f + ": " + e);
        }
    }

    /** Blocks picked most recently in the block picker, newest first. */
    public static List<String> recentBlocks() {
        return new ArrayList<>(data().recentBlocks);
    }

    public static void addRecentBlock(String id) {
        if (id == null || id.trim().isEmpty()) return;
        List<String> r = data().recentBlocks;
        r.remove(id);
        r.add(0, id);
        while (r.size() > MAX_RECENT_BLOCKS) r.remove(r.size() - 1);
        save();
    }

    public static List<String> recent() {
        return new ArrayList<>(data().recent);
    }

    public static void addRecent(String path) {
        if (path == null || path.trim().isEmpty()) return;
        List<String> r = data().recent;
        r.remove(path);
        r.add(0, path);
        while (r.size() > MAX_RECENT) r.remove(r.size() - 1);
        save();
    }

    public static boolean applyPreset(MaterialSetup setup) {
        Preset p = data().materials.get(setup.name);
        if (p == null) return false;
        if (p.block != null && !p.block.isEmpty()) setup.blockId = p.block;
        setup.mode = MaterialSetup.Mode.fromOrdinal(p.mode);
        setup.color = p.color;
        setup.glow = p.glow;
        return true;
    }

    public static void rememberPresets(Iterable<MaterialSetup> setups) {
        boolean changed = false;
        for (MaterialSetup s : setups) {
            Preset p = data().materials.get(s.name);
            if (p == null) {
                p = new Preset();
                data().materials.put(s.name, p);
                changed = true;
            }
            if (!s.blockId.equals(p.block) || p.mode != s.mode.ordinal() || p.color != s.color || p.glow != s.glow) {
                p.block = s.blockId;
                p.mode = s.mode.ordinal();
                p.color = s.color;
                p.glow = s.glow;
                changed = true;
            }
        }
        if (changed) save();
    }
}
