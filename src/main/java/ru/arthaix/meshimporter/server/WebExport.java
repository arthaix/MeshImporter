package ru.arthaix.meshimporter.server;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.MeshImporterConfig;
import ru.arthaix.meshimporter.instance.MeshInstance;
import ru.arthaix.meshimporter.model.MeshModelCodec;
import ru.arthaix.meshimporter.web.WebExporter;

/**
 * Server side of the web map export: runs {@link WebExporter} on a background thread a few seconds after models or
 * textures change (newer requests replace pending ones), and stores the block face textures clients upload in
 * {@code <save>/data/meshimporter/web-textures}.
 */
public final class WebExport {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:/\\-]{1,160}#[0-5]");
    private static final int MAX_TEXTURES = 20_000;

    private static ScheduledExecutorService exec;
    private static ScheduledFuture<?> pending;

    private WebExport() {}

    public static boolean enabled() {
        return !MeshImporterConfig.webExportDir.trim().isEmpty();
    }

    public static File textureDir(File saveDir) {
        return new File(saveDir, "data/meshimporter/web-textures");
    }

    /** Exports these instances (copies) a few seconds from now. */
    public static synchronized void request(List<MeshInstance> instances, File saveDir) {
        if (!enabled() || saveDir == null) return;
        if (exec == null) exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "meshimporter-web");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        if (pending != null) pending.cancel(false);
        File out = new File(MeshImporterConfig.webExportDir.trim()).getAbsoluteFile();
        File models = new File(saveDir, "data/meshimporter");
        File textures = textureDir(saveDir);
        int budget = MeshImporterConfig.webTriangles;
        pending = exec.schedule(() -> {
            long t0 = System.currentTimeMillis();
            try {
                new WebExporter(out, textures, budget, 2).export(instances, hash -> MeshModelCodec.decode(Files.readAllBytes(new File(models, hash + ".mim").toPath())));
                MeshImporter.logger.info("Web map export: " + instances.size() + " models in " + out + " (" + (System.currentTimeMillis() - t0) + " ms)");
            } catch (Throwable e) {
                MeshImporter.logger.warn("Web map export failed: " + e);
            }
        }, 3, TimeUnit.SECONDS);
    }

    /** Validates and stores an uploaded block face texture. True when something changed. */
    public static boolean storeTexture(File saveDir, String key, int tint, byte[] png) {
        if (saveDir == null || key == null || !KEY.matcher(key).matches() || png == null || png.length == 0 || png.length > 32_000) return false;
        int hash = key.lastIndexOf('#');
        String blockId = key.substring(0, hash);
        int face = key.charAt(hash + 1) - '0';
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || img.getWidth() > 64 || img.getHeight() > 64) return false;
            BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(img, 0, 0, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(argb, "png", bos);
            byte[] clean = bos.toByteArray();
            File dir = textureDir(saveDir);
            String name = WebExporter.textureName(blockId, face);
            File f = new File(dir, name + ".png"), tf = new File(dir, name + ".tint");
            String tintHex = String.format("%08x", tint);
            if (f.isFile() && tf.isFile() && Arrays.equals(Files.readAllBytes(f.toPath()), clean)
                && tintHex.equals(new String(Files.readAllBytes(tf.toPath()), StandardCharsets.UTF_8).trim())) return false;
            dir.mkdirs();
            if (!f.isFile()) {
                String[] existing = dir.list();
                if (existing != null && existing.length > 2 * MAX_TEXTURES) return false;
            }
            Files.write(f.toPath(), clean);
            Files.write(tf.toPath(), tintHex.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static synchronized void shutdown() {
        if (exec != null) exec.shutdownNow();
        exec = null;
        pending = null;
    }
}
