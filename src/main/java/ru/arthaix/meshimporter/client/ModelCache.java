package ru.arthaix.meshimporter.client;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.model.MeshModel;
import ru.arthaix.meshimporter.model.MeshModelCodec;
import ru.arthaix.meshimporter.network.MsgModelPart;
import ru.arthaix.meshimporter.network.MsgModelRequest;
import ru.arthaix.meshimporter.network.Net;

/**
 * Models on the client: decoded ones in memory, files in .minecraft/meshimporter/cache (so a model is downloaded once,
 * not on every login), and downloads in progress. All methods run on the game thread.
 */
public final class ModelCache {

    public static final ModelCache INSTANCE = new ModelCache();

    private final Map<String, MeshModel> memory = new HashMap<>();
    private final Map<String, List<Consumer<MeshModel>>> waiting = new HashMap<>();
    private final Map<String, byte[][]> parts = new HashMap<>();
    private final Map<String, int[]> progress = new HashMap<>(); // received, total

    private ModelCache() {}

    private static File file(String hash) {
        return new File(Minecraft.getMinecraft().gameDir, "meshimporter/cache/" + hash + ".mim");
    }

    /** Calls back (on the game thread) with the model: from memory, from the disk cache, or after downloading it. */
    public void get(String hash, Consumer<MeshModel> callback) {
        MeshModel m = memory.get(hash);
        if (m != null) {
            callback.accept(m);
            return;
        }
        List<Consumer<MeshModel>> list = waiting.get(hash);
        if (list != null) {
            list.add(callback);
            return;
        }
        list = new ArrayList<>();
        list.add(callback);
        waiting.put(hash, list);
        File f = file(hash);
        ClientMeshes.WORKERS.execute(() -> {
            MeshModel loaded = null;
            if (f.isFile()) {
                try {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    if (MeshModelCodec.sha1(bytes).equals(hash)) loaded = MeshModelCodec.decode(bytes);
                } catch (Exception e) {
                    MeshImporter.logger.warn("Cached model " + f + " is unreadable, downloading it again: " + e);
                }
                if (loaded == null) f.delete();
            }
            final MeshModel result = loaded;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (!waiting.containsKey(hash)) return;
                if (result != null) deliver(hash, result);
                else {
                    progress.put(hash, new int[] { 0, 1 });
                    Net.CH.sendToServer(new MsgModelRequest(hash));
                }
            });
        });
    }

    public void onPart(MsgModelPart msg) {
        if (!waiting.containsKey(msg.hash) || msg.total <= 0 || msg.part < 0 || msg.part >= msg.total) return;
        byte[][] arr = parts.get(msg.hash);
        if (arr == null || arr.length != msg.total) {
            arr = new byte[msg.total][];
            parts.put(msg.hash, arr);
            progress.put(msg.hash, new int[] { 0, msg.total });
        }
        int[] pr = progress.get(msg.hash);
        if (arr[msg.part] == null) pr[0]++;
        arr[msg.part] = msg.data;
        if (pr[0] < msg.total) return;
        parts.remove(msg.hash);
        progress.remove(msg.hash);
        final byte[][] all = arr;
        final String hash = msg.hash;
        ClientMeshes.WORKERS.execute(() -> {
            int size = 0;
            for (byte[] d : all) size += d.length;
            byte[] bytes = new byte[size];
            int o = 0;
            for (byte[] d : all) {
                System.arraycopy(d, 0, bytes, o, d.length);
                o += d.length;
            }
            MeshModel model = null;
            try {
                if (!MeshModelCodec.sha1(bytes).equals(hash)) throw new IllegalStateException("checksum mismatch");
                model = MeshModelCodec.decode(bytes);
                writeFile(hash, bytes);
            } catch (Exception e) {
                MeshImporter.logger.error("Downloaded model " + hash + " is broken: " + e);
            }
            final MeshModel result = model;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (result != null) deliver(hash, result);
                else waiting.remove(hash);
            });
        });
    }

    /** A model built on this computer: keep it and write the cache file, so placing it never downloads it again. */
    public void store(String hash, byte[] bytes, MeshModel model) {
        memory.put(hash, model);
        if (!file(hash).isFile()) ClientMeshes.WORKERS.execute(() -> writeFile(hash, bytes));
    }

    /** Downloads in progress: 0..1 per hash, for the GUI. */
    public float downloadProgress() {
        int got = 0, total = 0;
        for (int[] p : progress.values()) {
            got += p[0];
            total += p[1];
        }
        return total == 0 ? -1 : (float) got / total;
    }

    public void forget(String hash) {
        memory.remove(hash);
    }

    public void clear() {
        memory.clear();
        waiting.clear();
        parts.clear();
        progress.clear();
    }

    private void deliver(String hash, MeshModel model) {
        memory.put(hash, model);
        List<Consumer<MeshModel>> list = waiting.remove(hash);
        if (list == null) return;
        for (Consumer<MeshModel> c : list) {
            try {
                c.accept(model);
            } catch (RuntimeException e) {
                MeshImporter.logger.error("Model " + hash + " callback failed", e);
            }
        }
    }

    private static void writeFile(String hash, byte[] bytes) {
        File f = file(hash);
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Exception e) {
            MeshImporter.logger.warn("Cannot cache model " + f + ": " + e);
        }
    }
}
