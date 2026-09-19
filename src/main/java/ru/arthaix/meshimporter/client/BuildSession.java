package ru.arthaix.meshimporter.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.common.ImportSettings;
import ru.arthaix.meshimporter.common.MaterialSetup;
import ru.arthaix.meshimporter.common.Transform;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.instance.MeshInstance;
import ru.arthaix.meshimporter.model.MeshBuilder;
import ru.arthaix.meshimporter.model.MeshModel;
import ru.arthaix.meshimporter.model.MeshModelCodec;
import ru.arthaix.meshimporter.model.MtlLibrary;
import ru.arthaix.meshimporter.model.ObjMesh;
import ru.arthaix.meshimporter.model.ObjStreamParser;
import ru.arthaix.meshimporter.network.MsgPlace;
import ru.arthaix.meshimporter.network.MsgRemove;
import ru.arthaix.meshimporter.network.MsgUploadAck;
import ru.arthaix.meshimporter.network.MsgUploadBegin;
import ru.arthaix.meshimporter.network.MsgUploadPart;
import ru.arthaix.meshimporter.network.Net;

/**
 * The import pipeline behind the anchor GUI: read the .obj, build the mesh with the chosen materials, show a preview
 * where it will stand, upload the model file and ask the server to place it. The parsed .obj and the built model are
 * kept, so changing only the position or rotation never re-reads or rebuilds anything.
 */
public final class BuildSession {

    public static final BuildSession INSTANCE = new BuildSession();

    public volatile boolean busy;
    /** 0..1 while working, -1 when idle. */
    public volatile float progress = -1;
    public volatile String status = "";
    public final List<String> problems = new CopyOnWriteArrayList<>();
    public volatile MtlLibrary mtl;

    private volatile ObjMesh mesh;
    private volatile String meshPath;
    private volatile long meshStamp;
    private volatile MeshModel model;
    private volatile byte[] bytes;
    private volatile String signature;

    private String uploadHash;
    private byte[] uploadBytes;
    private int uploadParts, nextPart;
    private MsgPlace pendingPlace;

    private BuildSession() {}

    private File scanFile;
    private long scanStamp;
    private ObjStreamParser.MaterialScan scanResult;
    private MtlLibrary scanMtl;
    private List<String> scanProblems;

    /**
     * Material names of a model file. Reading a city-sized .obj takes a second or two, so the result is kept until the
     * .obj or one of its .mtl files changes. Thread-safe; meant to be called off the game thread.
     */
    public ObjStreamParser.MaterialScan scanMaterials(String path) throws IOException {
        File f = file(path);
        synchronized (this) {
            if (scanResult != null && f.getAbsoluteFile().equals(scanFile) && stamp(f, scanResult) == scanStamp) {
                mtl = scanMtl;
                problems.clear();
                problems.addAll(scanProblems);
                return scanResult;
            }
        }
        ObjStreamParser.MaterialScan scan = ObjStreamParser.scanMaterials(f);
        List<String> pr = new ArrayList<>();
        MtlLibrary lib = MtlLibrary.load(f, scan.mtlFiles, pr);
        synchronized (this) {
            scanFile = f.getAbsoluteFile();
            scanStamp = stamp(f, scan);
            scanResult = scan;
            scanMtl = lib;
            scanProblems = pr;
            mtl = lib;
            problems.clear();
            problems.addAll(pr);
        }
        return scan;
    }

    /** Changes when the .obj or any of its .mtl files is rewritten. */
    private static long stamp(File obj, ObjStreamParser.MaterialScan scan) {
        long s = obj.length() * 1_000_003L + obj.lastModified();
        File dir = obj.getAbsoluteFile().getParentFile();
        for (String name : scan.mtlFiles) {
            File m = new File(name);
            if (!m.isAbsolute()) m = new File(dir, name);
            s = s * 31 + m.length() * 7 + m.lastModified();
        }
        return s;
    }

    private static File file(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) throw new IOException("Choose a .obj file first");
        File f = new File(path.trim());
        if (!f.isFile()) throw new IOException("File not found: " + f);
        return f;
    }

    /**
     * Builds the model (or reuses the last build when file and materials are unchanged) and then either shows it as a
     * preview or uploads and places it.
     */
    public void build(ImportSettings settings, BlockPos anchor, int slot, boolean thenPlace) {
        Minecraft mc = Minecraft.getMinecraft();
        if (busy || mc.world == null) return;
        final int dim = mc.world.provider.getDimension();
        final ImportSettings s = settings.copy();
        busy = true;
        progress = 0;
        status = "Reading model...";
        ClientMeshes.WORKERS.execute(() -> {
            try {
                File f = file(s.modelPath);
                long stamp = f.length() * 1_000_003L + f.lastModified();
                String path = f.getAbsolutePath();
                List<String> pr = new ArrayList<>();
                if (mesh == null || !path.equals(meshPath) || stamp != meshStamp) {
                    ObjMesh parsed = ObjStreamParser.parse(f, (read, total) -> {
                        progress = 0.35f * read / Math.max(1L, total);
                        return true;
                    });
                    mtl = MtlLibrary.load(f, parsed.mtlFiles, pr);
                    mesh = parsed;
                    meshPath = path;
                    meshStamp = stamp;
                    model = null;
                }
                ObjMesh m = mesh;
                if (m.triangleCount == 0) throw new IOException("The model has no faces");
                List<MaterialSetup> setups = new ArrayList<>();
                for (String name : m.materials) setups.add(s.materialOrCreate(name));
                String sig = signature(path, stamp, setups);
                if (model == null || !sig.equals(signature)) {
                    status = "Building mesh...";
                    MeshModel built = MeshBuilder.build(m, mtl, setups, f, pr, fr -> {
                        progress = 0.35f + 0.35f * fr;
                        return true;
                    });
                    if (built == null || built.triangleCount == 0) throw new IOException("Nothing to import (every material skipped?)");
                    status = "Packing...";
                    progress = 0.72f;
                    byte[] encoded = MeshModelCodec.encode(built);
                    model = built;
                    bytes = encoded;
                    signature = sig;
                }
                problems.clear();
                problems.addAll(pr);
                final String name = f.getName().replaceFirst("\\.[^.]*$", "");
                final MeshModel mm = model;
                final byte[] data = bytes;
                final double[] matrix = Transform.matrix(s, Transform.scaledBounds(mm.positions, mm.vertexCount, null, s), anchor.getX(), anchor.getY(), anchor.getZ());
                LoadedInstance previewInstance = null;
                if (!thenPlace) {
                    status = "Preparing preview...";
                    progress = 0.8f;
                    MeshInstance in = new MeshInstance();
                    in.id = -1;
                    in.dim = dim;
                    in.hash = mm.hash;
                    in.anchorX = anchor.getX();
                    in.anchorY = anchor.getY();
                    in.anchorZ = anchor.getZ();
                    in.matrix = matrix;
                    in.bounds = Transform.bounds(matrix, mm.bounds);
                    in.name = name;
                    in.triangles = mm.triangleCount;
                    previewInstance = LoadedInstance.load(in, mm);
                    previewInstance.preview = true;
                }
                final LoadedInstance pv = previewInstance;
                mc.addScheduledTask(() -> {
                    ModelCache.INSTANCE.store(mm.hash, data, mm);
                    busy = false;
                    progress = -1;
                    String info = String.format("%,d triangles, %.1f MB", mm.triangleCount, data.length / 1048576.0)
                        + (problems.isEmpty() ? "" : "  (" + problems.size() + " warning" + (problems.size() > 1 ? "s" : "") + ": " + problems.get(0) + ")");
                    if (pv != null) {
                        ClientMeshes.INSTANCE.setPreview(pv);
                        status = "Preview: " + info;
                    } else {
                        startUpload(mm.hash, data, new MsgPlace(anchor, mm.hash, matrix, name, s.write(new NBTTagCompound()), slot), info);
                    }
                });
            } catch (Throwable e) {
                busy = false;
                progress = -1;
                status = TextFormatting.RED + (e instanceof OutOfMemoryError ? "Out of memory while building the model" : e.getMessage() != null ? e.getMessage() : e.toString());
                MeshImporter.logger.warn("Model build failed", e);
            }
        });
    }

    private static String signature(String path, long stamp, List<MaterialSetup> setups) {
        StringBuilder sb = new StringBuilder(path).append('|').append(stamp);
        for (MaterialSetup m : setups)
            sb.append('|').append(m.name).append(',').append(m.blockId).append(',').append(m.mode.ordinal()).append(',').append(m.color).append(',').append(m.texturePath)
                .append(',').append(m.skip).append(',').append(m.glow);
        return sb.toString();
    }

    // ---- upload ----

    private void startUpload(String hash, byte[] data, MsgPlace place, String info) {
        uploadHash = hash;
        uploadBytes = data;
        uploadParts = Math.max(1, (data.length + Net.UPLOAD_PART - 1) / Net.UPLOAD_PART);
        nextPart = 0;
        pendingPlace = place;
        status = "Uploading " + info;
        progress = 0;
        Net.CH.sendToServer(new MsgUploadBegin(hash, data.length, uploadParts, place.name));
    }

    public void onUploadAck(MsgUploadAck ack) {
        if (uploadHash == null || !uploadHash.equals(ack.hash)) return;
        switch (ack.status) {
            case MsgUploadAck.CONTINUE:
                while (nextPart < uploadParts && nextPart < ack.received + 16) {
                    int from = nextPart * Net.UPLOAD_PART;
                    int len = Math.min(Net.UPLOAD_PART, uploadBytes.length - from);
                    Net.CH.sendToServer(new MsgUploadPart(uploadHash, nextPart, Arrays.copyOfRange(uploadBytes, from, from + len)));
                    nextPart++;
                }
                progress = (float) ack.received / uploadParts;
                status = "Uploading " + (100 * ack.received / uploadParts) + "%";
                break;
            case MsgUploadAck.DONE:
            case MsgUploadAck.EXISTS:
                Net.CH.sendToServer(pendingPlace);
                status = "Placing...";
                progress = -1;
                clearUpload();
                break;
            default:
                status = TextFormatting.RED + "Upload failed: " + ack.message;
                progress = -1;
                clearUpload();
        }
    }

    private void clearUpload() {
        uploadHash = null;
        uploadBytes = null;
        pendingPlace = null;
    }

    public void remove(BlockPos anchor, int slot, boolean dropSlot) {
        Net.CH.sendToServer(new MsgRemove(anchor, slot, dropSlot));
        ClientMeshes.INSTANCE.clearPreview();
        status = "Removing...";
    }

    /** Disconnected: forget uploads (the parsed .obj and the built model stay for the next server). */
    public void reset() {
        clearUpload();
        if (!busy) {
            progress = -1;
            status = "";
        }
    }
}
