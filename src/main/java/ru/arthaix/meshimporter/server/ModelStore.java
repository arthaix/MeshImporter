package ru.arthaix.meshimporter.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraftforge.common.DimensionManager;
import ru.arthaix.meshimporter.model.MeshModel;
import ru.arthaix.meshimporter.model.MeshModelCodec;

/** Model files of the current save: <save>/data/meshimporter/<hash>.mim, plus the decoded models in memory. */
public final class ModelStore {

    private static final Map<String, MeshModel> loaded = new ConcurrentHashMap<>();

    private ModelStore() {}

    public static File dir() {
        return new File(DimensionManager.getCurrentSaveRootDirectory(), "data/meshimporter");
    }

    public static File file(String hash) {
        return new File(dir(), hash + ".mim");
    }

    public static boolean exists(String hash) {
        return MeshModelCodec.validHash(hash) && file(hash).isFile();
    }

    public static byte[] readBytes(String hash) throws IOException {
        return Files.readAllBytes(file(hash).toPath());
    }

    public static void write(String hash, byte[] bytes) throws IOException {
        File f = file(hash);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (f.exists() && !f.delete()) throw new IOException("cannot replace " + f);
        if (!tmp.renameTo(f)) throw new IOException("cannot rename " + tmp);
    }

    public static void delete(String hash) {
        loaded.remove(hash);
        File f = file(hash);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    /** Decoded model, from memory or from disk (slow: call off the server thread when possible). */
    public static MeshModel load(String hash) throws IOException {
        MeshModel m = loaded.get(hash);
        if (m != null) return m;
        try (FileInputStream in = new FileInputStream(file(hash))) {
            m = MeshModelCodec.decode(in);
        }
        m.hash = hash;
        m.encodedBytes = file(hash).length();
        loaded.put(hash, m);
        return m;
    }

    public static MeshModel cached(String hash) {
        return loaded.get(hash);
    }

    public static void cache(String hash, MeshModel model) {
        loaded.put(hash, model);
    }

    public static void unload(String hash) {
        loaded.remove(hash);
    }

    public static void clearCache() {
        loaded.clear();
    }
}
