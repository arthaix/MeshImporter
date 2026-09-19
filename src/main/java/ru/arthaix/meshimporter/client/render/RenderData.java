package ru.arthaix.meshimporter.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.MeshImporterConfig;
import ru.arthaix.meshimporter.client.WebTextures;
import ru.arthaix.meshimporter.common.BlockRef;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.light.LightBake;
import ru.arthaix.meshimporter.model.MeshModel;

/**
 * GPU side of one placed model. Triangles are split into spatial cells (culling, light refresh, light volume) and
 * within a cell into groups (one texture each: a material, or a material's block face). Vertices use Minecraft's
 * BLOCK format, so the world lightmap does day/night and block light exactly like for terrain.
 *
 * Sky light: the mesh's own light is baked by {@link LightBake} when the model loads. With the shader it is read per
 * pixel from a 3D texture per cell, so the size and shape of triangles never shows in the lighting; without shaders
 * it is sampled at the vertices. World light (terrain roofs, torches) is refreshed over time: with the shader it goes
 * into a third 3D texture (only the texels surfaces read are sampled), otherwise it is sampled next to every vertex.
 * The darker of world and mesh sky light wins.
 */
public final class RenderData {

    static final int VERTEX_BYTES = 32;
    /**
     * Minecraft's block format plus a normal: a shader pack lights our triangles by gl_Normal, and without it faces
     * come out inside out or black.
     */
    static final net.minecraft.client.renderer.vertex.VertexFormat FORMAT = new net.minecraft.client.renderer.vertex.VertexFormat()
        .addElement(DefaultVertexFormats.POSITION_3F)
        .addElement(DefaultVertexFormats.COLOR_4UB)
        .addElement(DefaultVertexFormats.TEX_2F)
        .addElement(DefaultVertexFormats.TEX_2S)
        .addElement(DefaultVertexFormats.NORMAL_3B)
        .addElement(DefaultVertexFormats.PADDING_1B);
    /** Largest light texture per cell; bigger cells get coarser texels. */
    private static final int MAX_TEXELS = 400_000;

    public static final class Group {
        int material;
        /** EnumFacing index for block-texture groups, -1 otherwise. */
        int face = -1;
        int texture = -1;
        String textureKey;
        int textureIndex = -1;
        boolean translucent, glow, worldUv, modelUv;
        int color = -1;
    }

    static final class Cell {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        /** Model vertex per corner. */
        int[] corners;
        /** World light per corner: sky (high byte) and block light (low byte), both 0..240. */
        char[] light;
        final int[] groupStart, groupCount;
        final VertexBuffer[] vbo;
        final ByteBuffer[] pending;
        /** Light volume: texel (0, 0, 0) at this anchor-relative block, size in texels, blocks per texel. */
        int volX, volY, volZ, volW, volH, volD, volStep = 1;
        ByteBuffer volPos, volNeg;
        int texPos = -1, texNeg = -1;
        /** Texels of the world light texture that lookups from this cell's surfaces can touch. */
        BitSet worldMask;
        /** Per set bit of worldMask, in order: bit 8 = inside an opaque block, bits 4..7 sky, 0..3 block light. */
        char[] worldValues;
        /** Sweep position (next bit, its ordinal), changed texels as (bit, ordinal) pairs. */
        int worldBit, worldOrdinal, pendingCount;
        int[] worldPending;
        /** A full sweep has been uploaded; afterwards only changed texels are sent. */
        boolean worldSampled;
        int texWorld = -1;

        Cell(int groups) {
            groupStart = new int[groups];
            groupCount = new int[groups];
            vbo = new VertexBuffer[groups];
            pending = new ByteBuffer[groups];
        }

        void include(float x, float y, float z) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
    }

    public final LoadedInstance li;
    final Group[] groups;
    private final int[] groupBase;
    private final int ux, uy, uz;

    private Cell[] cells = new Cell[0];
    /** Mesh sky light per model vertex, 0..240, for drawing without the shader; null = none baked. */
    private byte[] vertexSky;
    private boolean shaded;

    private final Map<String, Textures.Image> decoded = new HashMap<>();
    public volatile boolean built;
    public volatile boolean disposed;
    private int uploadCell;
    private int[] visible = new int[0];

    private int[] lightOrder;
    private int lightPos;
    private long nextSweep;

    private static ByteBuffer scratch;

    /** Game thread: resolves textures and block colours of every material. */
    public RenderData(LoadedInstance li, World world) {
        this.li = li;
        this.shaded = Shaders.active();
        MeshModel m = li.model;
        ux = li.instance.anchorX & 255;
        uy = li.instance.anchorY & 255;
        uz = li.instance.anchorZ & 255;
        groupBase = new int[m.materials.length];
        List<Group> list = new ArrayList<>();
        BlockPos anchor = new BlockPos(li.instance.anchorX, li.instance.anchorY, li.instance.anchorZ);
        Minecraft mc = Minecraft.getMinecraft();
        for (int i = 0; i < m.materials.length; i++) {
            MeshModel.Material mat = m.materials[i];
            groupBase[i] = list.size();
            boolean alphaBlend = ((mat.color >>> 24) & 255) < 255;
            switch (mat.mode) {
                case BLOCK: {
                    IBlockState state = BlockRef.parse(mat.blockId);
                    boolean blockTranslucent = state.getBlock().getRenderLayer() == BlockRenderLayer.TRANSLUCENT;
                    for (EnumFacing f : EnumFacing.VALUES) {
                        Group g = new Group();
                        g.material = i;
                        g.face = f.getIndex();
                        int[] tex = Textures.blockFace(state, f);
                        g.texture = tex[0];
                        int mult = -1;
                        if (tex[1] >= 0) {
                            try {
                                mult = mc.getBlockColors().colorMultiplier(state, world, anchor, tex[1]);
                            } catch (RuntimeException ignored) {
                                mult = -1;
                            }
                        }
                        g.color = multiply(mat.color, mult);
                        if (!li.preview) WebTextures.offer(mat.blockId, state, f, mult);
                        g.worldUv = true;
                        g.translucent = alphaBlend || blockTranslucent;
                        g.glow = mat.glow;
                        list.add(g);
                    }
                    break;
                }
                case TEXTURE: {
                    Group g = new Group();
                    g.material = i;
                    g.textureIndex = mat.texture;
                    g.textureKey = mat.texture >= 0 ? m.hash + "#" + mat.texture : null;
                    g.texture = g.textureKey == null ? Textures.white() : Textures.modelTexture(g.textureKey);
                    g.color = mat.color;
                    g.modelUv = mat.texture >= 0;
                    g.translucent = alphaBlend;
                    g.glow = mat.glow;
                    list.add(g);
                    break;
                }
                default: {
                    Group g = new Group();
                    g.material = i;
                    g.texture = Textures.white();
                    g.color = mat.color;
                    g.translucent = alphaBlend;
                    g.glow = mat.glow;
                    list.add(g);
                }
            }
        }
        groups = list.toArray(new Group[0]);
    }

    private static int multiply(int argb, int rgb) {
        if (rgb == -1) return argb;
        int r = ((argb >> 16) & 255) * ((rgb >> 16) & 255) / 255;
        int g = ((argb >> 8) & 255) * ((rgb >> 8) & 255) / 255;
        int b = (argb & 255) * (rgb & 255) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int face(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) return ny >= 0 ? 1 : 0;
        if (az >= ax) return nz >= 0 ? 3 : 2;
        return nx >= 0 ? 5 : 4;
    }

    // ---- CPU build (worker thread) ----

    public void build() {
        MeshModel m = li.model;
        int tris = m.triangleCount;
        int[] idx = m.indices;
        float[] loc = li.local;
        byte[] nrm = li.normals;
        for (Group g : groups) {
            if (g.textureKey == null || Textures.hasModelTexture(g.textureKey) || decoded.containsKey(g.textureKey)) continue;
            Textures.Image img = null;
            try {
                img = Textures.decode(m.textures[g.textureIndex]);
            } catch (Exception ignored) {
                // unreadable texture: drawn white with the material colour
            }
            decoded.put(g.textureKey, img);
        }

        int[] triGroup = new int[tris];
        for (int mat = 0; mat < m.materials.length; mat++) {
            int base = groupBase[mat];
            boolean perFace = groups[base].face >= 0;
            for (int t = m.matStart[mat], end = t + m.matCount[mat]; t < end; t++) {
                int g = base;
                if (perFace) {
                    int v = idx[3 * t];
                    g += face(nrm[3 * v], nrm[3 * v + 1], nrm[3 * v + 2]);
                }
                triGroup[t] = g;
            }
        }

        // ---- the mesh's own sky light ----
        LightBake bake = null;
        if (MeshImporterConfig.selfShadow && tris > 0) {
            boolean[] translucent = new boolean[tris];
            for (int t = 0; t < tris; t++) translucent[t] = groups[triGroup[t]].translucent;
            int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 3));
            try {
                bake = new LightBake(loc, idx, tris, translucent, threads);
                MeshImporter.logger.info("Model #" + li.instance.id + ": light baked in " + bake.millis + " ms (" + bake.sampleCells + " cells, " + bake.raysCast + " rays, " + threads + " threads)");
                if (!shaded) vertexSky = sampleVertices(bake);
            } catch (RuntimeException | OutOfMemoryError e) {
                bake = null;
                MeshImporter.logger.warn("No baked light for model #" + li.instance.id + ": " + e);
            }
        }

        // ---- cells ----
        float cs = MeshImporterConfig.cellSize;
        Map<Long, Integer> ids = new HashMap<>();
        int[] triCell = new int[tris];
        int[] counts = new int[64];
        for (int t = 0; t < tris; t++) {
            int a = idx[3 * t], b = idx[3 * t + 1], c = idx[3 * t + 2];
            float cx = (loc[3 * a] + loc[3 * b] + loc[3 * c]) / 3f;
            float cy = (loc[3 * a + 1] + loc[3 * b + 1] + loc[3 * c + 1]) / 3f;
            float cz = (loc[3 * a + 2] + loc[3 * b + 2] + loc[3 * c + 2]) / 3f;
            long key = ((((long) Math.floor(cx / cs)) & 0x1FFFFF) << 42) | ((((long) Math.floor(cy / cs)) & 0x1FFFFF) << 21) | (((long) Math.floor(cz / cs)) & 0x1FFFFF);
            Integer id = ids.get(key);
            if (id == null) {
                id = ids.size();
                ids.put(key, id);
                if (id == counts.length) counts = Arrays.copyOf(counts, id * 2);
            }
            triCell[t] = id;
            counts[id]++;
        }
        int nc = ids.size();
        int[] start = new int[nc + 1];
        for (int i = 0; i < nc; i++) start[i + 1] = start[i] + counts[i];
        int[] order = new int[tris];
        int[] cursor = Arrays.copyOf(start, nc);
        for (int t = 0; t < tris; t++) order[cursor[triCell[t]]++] = t;

        int groupCount = groups.length;
        Cell[] out = new Cell[nc];
        int[] gcount = new int[groupCount];
        long texelBytes = 0;
        for (int ci = 0; ci < nc && !disposed; ci++) {
            Arrays.fill(gcount, 0);
            for (int k = start[ci]; k < start[ci + 1]; k++) gcount[triGroup[order[k]]]++;
            Cell cell = new Cell(groupCount);
            int total = 0;
            for (int g = 0; g < groupCount; g++) {
                cell.groupStart[g] = 3 * total;
                cell.groupCount[g] = 3 * gcount[g];
                total += gcount[g];
            }
            cell.corners = new int[3 * total];
            int[] fill = cell.groupStart.clone();
            for (int k = start[ci]; k < start[ci + 1]; k++) {
                int t = order[k];
                int g = triGroup[t];
                int o = fill[g];
                fill[g] += 3;
                for (int c = 0; c < 3; c++) {
                    int v = idx[3 * t + c];
                    cell.corners[o + c] = v;
                    cell.include(loc[3 * v], loc[3 * v + 1], loc[3 * v + 2]);
                }
            }
            cell.light = new char[3 * total];
            Arrays.fill(cell.light, (char) (240 << 8));
            if (shaded) texelBytes += volume(cell, bake);
            for (int g = 0; g < groupCount; g++) {
                if (cell.groupCount[g] == 0) continue;
                ByteBuffer buf = ByteBuffer.allocateDirect(cell.groupCount[g] * VERTEX_BYTES).order(ByteOrder.nativeOrder());
                fill(cell, g, buf);
                buf.flip();
                cell.pending[g] = buf;
            }
            out[ci] = cell;
        }
        if (texelBytes > 0) MeshImporter.logger.info("Model #" + li.instance.id + ": " + nc + " cells, light textures " + (texelBytes / 1048576) + " MB");
        cells = out;
        built = true;
    }

    /** Prepares the light textures of a cell: its bounds plus a block of margin. Returns the bytes. */
    private long volume(Cell c, LightBake bake) {
        int x0 = (int) Math.floor(c.minX) - 1, y0 = (int) Math.floor(c.minY) - 1, z0 = (int) Math.floor(c.minZ) - 1;
        int x1 = (int) Math.ceil(c.maxX) + 1, y1 = (int) Math.ceil(c.maxY) + 1, z1 = (int) Math.ceil(c.maxZ) + 1;
        int step = 1;
        while ((long) ceilDiv(x1 - x0, step) * ceilDiv(y1 - y0, step) * ceilDiv(z1 - z0, step) > MAX_TEXELS) step *= 2;
        int w = Math.max(1, ceilDiv(x1 - x0, step)), h = Math.max(1, ceilDiv(y1 - y0, step)), d = Math.max(1, ceilDiv(z1 - z0, step));
        c.volX = x0;
        c.volY = y0;
        c.volZ = z0;
        c.volW = w;
        c.volH = h;
        c.volD = d;
        c.volStep = step;
        long bytes = 4L * w * h * d;
        if (bake != null) {
            c.volPos = ByteBuffer.allocateDirect((int) bytes);
            c.volNeg = ByteBuffer.allocateDirect((int) bytes);
            bake.fillTexture(x0, y0, z0, w, h, d, step, c.volPos, c.volNeg);
            c.volPos.flip();
            c.volNeg.flip();
        }
        c.worldMask = worldTexels(c);
        c.worldValues = new char[c.worldMask.cardinality()];
        Arrays.fill(c.worldValues, NOT_SAMPLED);
        return (bake != null ? 3 : 1) * bytes;
    }

    /** Texels a trilinear lookup half a block in front of (or behind) any surface of the cell can touch. */
    private BitSet worldTexels(Cell c) {
        BitSet mask = new BitSet(c.volW * c.volH * c.volD);
        float[] loc = li.local;
        byte[] nrm = li.normals;
        for (int g = 0; g < groups.length; g++) {
            if (groups[g].glow) continue;
            for (int k = c.groupStart[g], end = k + c.groupCount[g]; k < end; k += 3) {
                int a = 3 * c.corners[k], b = 3 * c.corners[k + 1], d = 3 * c.corners[k + 2];
                double ox = nrm[a] / 254.0, oy = nrm[a + 1] / 254.0, oz = nrm[a + 2] / 254.0;
                double e1x = loc[b] - loc[a], e1y = loc[b + 1] - loc[a + 1], e1z = loc[b + 2] - loc[a + 2];
                double e2x = loc[d] - loc[a], e2y = loc[d + 1] - loc[a + 1], e2z = loc[d + 2] - loc[a + 2];
                double l1 = e1x * e1x + e1y * e1y + e1z * e1z, l2 = e2x * e2x + e2y * e2y + e2z * e2z;
                double l3 = (e2x - e1x) * (e2x - e1x) + (e2y - e1y) * (e2y - e1y) + (e2z - e1z) * (e2z - e1z);
                int n = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(l1, Math.max(l2, l3))) / (0.5 * c.volStep)));
                for (int i = 0; i <= n; i++)
                    for (int j = 0; i + j <= n; j++) {
                        double px = loc[a] + (e1x * i + e2x * j) / n, py = loc[a + 1] + (e1y * i + e2y * j) / n, pz = loc[a + 2] + (e1z * i + e2z * j) / n;
                        markAround(mask, c, px + ox, py + oy, pz + oz);
                        markAround(mask, c, px - ox, py - oy, pz - oz);
                    }
            }
        }
        return mask;
    }

    private static void markAround(BitSet mask, Cell c, double x, double y, double z) {
        int s = c.volStep;
        int i0 = (int) Math.floor((x - c.volX) / s - 0.5), j0 = (int) Math.floor((y - c.volY) / s - 0.5), k0 = (int) Math.floor((z - c.volZ) / s - 0.5);
        for (int dk = 0; dk <= 1; dk++) {
            int k = Math.max(0, Math.min(c.volD - 1, k0 + dk));
            for (int dj = 0; dj <= 1; dj++) {
                int j = Math.max(0, Math.min(c.volH - 1, j0 + dj));
                for (int di = 0; di <= 1; di++) {
                    int i = Math.max(0, Math.min(c.volW - 1, i0 + di));
                    mask.set((k * c.volH + j) * c.volW + i);
                }
            }
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private byte[] sampleVertices(LightBake bake) {
        int n = li.model.vertexCount;
        byte[] result = new byte[n];
        float[] loc = li.local;
        byte[] nrm = li.normals;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        AtomicInteger next = new AtomicInteger();
        Runnable work = () -> {
            int from;
            while (!disposed && (from = next.getAndAdd(8192)) < n) {
                int to = Math.min(n, from + 8192);
                for (int v = from; v < to; v++) {
                    float vis = bake.sample(loc[3 * v], loc[3 * v + 1], loc[3 * v + 2], nrm[3 * v] / 127.0, nrm[3 * v + 1] / 127.0, nrm[3 * v + 2] / 127.0);
                    result[v] = (byte) Math.round(16 * LightBake.levelOf(vis, 0));
                }
            }
        };
        Thread[] ts = new Thread[threads - 1];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(work, "meshimporter-light");
            ts[i].setDaemon(true);
            ts[i].start();
        }
        work.run();
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    private void fill(Cell c, int g, ByteBuffer buf) {
        Group gr = groups[g];
        float[] loc = li.local;
        byte[] nrm = li.normals;
        float[] uv = li.model.uvs;
        int floor = MeshImporterConfig.shadowLevel * 16;
        int a = (gr.color >>> 24) & 255, r = (gr.color >> 16) & 255, gg = (gr.color >> 8) & 255, b = gr.color & 255;
        int s = c.groupStart[g], e = s + c.groupCount[g];
        for (int k = s; k < e; k++) {
            int v = c.corners[k];
            float x = loc[3 * v], y = loc[3 * v + 1], z = loc[3 * v + 2];
            buf.putFloat(x).putFloat(y).putFloat(z);
            float shade = 1f;
            if (!gr.glow) {
                float nx = nrm[3 * v] / 127f, ny = nrm[3 * v + 1] / 127f, nz = nrm[3 * v + 2] / 127f;
                shade = Math.min(1f, nx * nx * 0.6f + nz * nz * 0.8f + ny * ny * (ny > 0 ? 1f : 0.5f));
            }
            buf.put((byte) (int) (r * shade)).put((byte) (int) (gg * shade)).put((byte) (int) (b * shade)).put((byte) a);
            float u = 0, w = 0;
            if (gr.worldUv) {
                switch (gr.face) {
                    case 0:
                    case 1:
                        u = x + ux;
                        w = z + uz;
                        break;
                    case 2:
                    case 3:
                        u = x + ux;
                        w = -(y + uy);
                        break;
                    default:
                        u = z + uz;
                        w = -(y + uy);
                }
            } else if (gr.modelUv && uv != null) {
                u = uv[2 * v];
                w = uv[2 * v + 1];
            }
            buf.putFloat(u).putFloat(w);
            char l = c.light[k];
            int sky = l >> 8, block = l & 255;
            if (gr.glow) {
                sky = 240;
                block = 240;
            } else if (!shaded && vertexSky != null) {
                sky = Math.min(sky, Math.max(vertexSky[v] & 255, floor));
            }
            buf.putShort((short) block).putShort((short) sky);
            buf.put(nrm[3 * v]).put(nrm[3 * v + 1]).put(nrm[3 * v + 2]).put((byte) 0);
        }
    }

    // ---- GPU (game thread) ----

    /** Uploads cells until about {@code budget} bytes went to the GPU; returns the bytes used (at least 1). */
    public long upload(long budget) {
        if (!built || disposed) return 1;
        if (!decoded.isEmpty()) {
            for (Map.Entry<String, Textures.Image> e : decoded.entrySet()) Textures.uploadModelTexture(e.getKey(), e.getValue());
            decoded.clear();
        }
        for (Group g : groups) {
            if (g.texture < 0) {
                int id = g.textureKey == null ? -1 : Textures.modelTexture(g.textureKey);
                g.texture = id >= 0 ? id : Textures.white();
            }
        }
        long used = 0;
        while (uploadCell < cells.length && used < budget) {
            Cell c = cells[uploadCell++];
            for (int g = 0; g < c.pending.length; g++) {
                ByteBuffer b = c.pending[g];
                if (b == null) continue;
                VertexBuffer vb = new VertexBuffer(FORMAT);
                vb.bufferData(b);
                c.vbo[g] = vb;
                c.pending[g] = null;
                used += b.limit();
            }
            if (c.volPos != null) {
                c.texPos = texture3d(c.volPos, c.volW, c.volH, c.volD);
                c.texNeg = texture3d(c.volNeg, c.volW, c.volH, c.volD);
                used += c.volPos.limit() * 2L;
                c.volPos = null;
                c.volNeg = null;
            }
            if (c.worldMask != null && c.texWorld < 0) {
                uploadWorld(c);
                used += 4L * c.volW * c.volH * c.volD;
            }
        }
        return Math.max(1, used);
    }

    // ---- world light texture (game thread) ----

    private static final char NOT_SAMPLED = 0xFFFF;
    private static final int PENDING_MAX = 512;
    private static final ByteBuffer TEXEL = ByteBuffer.allocateDirect(4);
    private static ByteBuffer worldScratch;

    /** Uploads the whole world light texture of a cell; texels not sampled yet are open sky without block light. */
    private static void uploadWorld(Cell c) {
        int texels = c.volW * c.volH * c.volD;
        if (worldScratch == null || worldScratch.capacity() < 4 * texels) worldScratch = ByteBuffer.allocateDirect(Math.max(4 * texels, 1 << 20));
        ByteBuffer buf = worldScratch;
        buf.clear();
        for (int t = 0; t < texels; t++) buf.put((byte) -1).put((byte) 0).put((byte) 0).put((byte) -1);
        int ord = 0;
        for (int bit = c.worldMask.nextSetBit(0); bit >= 0; bit = c.worldMask.nextSetBit(bit + 1), ord++)
            if (c.worldValues[ord] != NOT_SAMPLED) encode(c.worldValues[ord], buf, 4 * bit);
        buf.flip();
        if (c.texWorld < 0) {
            c.texWorld = texture3d(buf, c.volW, c.volH, c.volD);
            return;
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, c.texWorld);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, c.volW, c.volH, c.volD, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    /** Texel bytes: sky and block light (x17), premultiplied by the weight; blocks inside terrain barely count. */
    private static void encode(char v, ByteBuffer buf, int at) {
        int a = (v & 0x100) != 0 ? LightBake.BLOCKED_ALPHA : 255;
        buf.put(at, (byte) Math.round(((v >> 4) & 15) * 17 * a / 255f));
        buf.put(at + 1, (byte) Math.round((v & 15) * 17 * a / 255f));
        buf.put(at + 2, (byte) 0);
        buf.put(at + 3, (byte) a);
    }

    /**
     * Continues the world light sweep of a cell until the deadline. Returns true when the sweep is complete (and its
     * changes are on the GPU), false when time ran out.
     */
    private boolean sampleWorld(Cell c, World world, long deadline, BlockPos.MutableBlockPos mp) {
        int w = c.volW, wh = c.volW * c.volH, step = c.volStep, half = step / 2;
        int bx = li.instance.anchorX + c.volX + half, by = li.instance.anchorY + c.volY + half, bz = li.instance.anchorZ + c.volZ + half;
        int bit = c.worldBit, ord = c.worldOrdinal, count = 0;
        while ((bit = c.worldMask.nextSetBit(bit)) >= 0) {
            if ((++count & 127) == 0 && System.nanoTime() > deadline) {
                c.worldBit = bit;
                c.worldOrdinal = ord;
                return false;
            }
            char v = worldLight(world, mp, bx + (bit % w) * step, by + ((bit / w) % c.volH) * step, bz + (bit / wh) * step);
            if (c.worldValues[ord] != v) {
                c.worldValues[ord] = v;
                if (c.worldSampled) {
                    if (c.worldPending == null) c.worldPending = new int[2 * PENDING_MAX];
                    if (c.pendingCount < PENDING_MAX) {
                        c.worldPending[2 * c.pendingCount] = bit;
                        c.worldPending[2 * c.pendingCount + 1] = ord;
                    }
                    c.pendingCount++;
                }
            }
            ord++;
            bit++;
        }
        if (!c.worldSampled || c.pendingCount > PENDING_MAX) {
            uploadWorld(c);
        } else if (c.pendingCount > 0) {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, c.texWorld);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            for (int p = 0; p < c.pendingCount; p++) {
                int t = c.worldPending[2 * p];
                encode(c.worldValues[c.worldPending[2 * p + 1]], TEXEL, 0);
                TEXEL.clear();
                GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, t % w, (t / w) % c.volH, t / wh, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, TEXEL);
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        }
        c.worldSampled = true;
        c.pendingCount = 0;
        c.worldBit = 0;
        c.worldOrdinal = 0;
        return true;
    }

    private static char worldLight(World world, BlockPos.MutableBlockPos mp, int x, int y, int z) {
        mp.setPos(x, y, z);
        if (!world.getBlockState(mp).isOpaqueCube()) return (char) ((world.getLightFor(EnumSkyBlock.SKY, mp) << 4) | world.getLightFor(EnumSkyBlock.BLOCK, mp));
        // inside terrain the stored light is 0: keep the brightest neighbour for lookups that have nothing better
        int sky = 0, block = 0;
        for (EnumFacing f : EnumFacing.VALUES) {
            mp.setPos(x + f.getXOffset(), y + f.getYOffset(), z + f.getZOffset());
            sky = Math.max(sky, world.getLightFor(EnumSkyBlock.SKY, mp));
            block = Math.max(block, world.getLightFor(EnumSkyBlock.BLOCK, mp));
        }
        return (char) (0x100 | (sky << 4) | block);
    }

    private static int texture3d(ByteBuffer data, int w, int h, int d) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, w, h, d, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return id;
    }

    public boolean uploadDone() {
        return built && uploadCell >= cells.length;
    }

    public boolean hasSomethingToDraw() {
        return built && uploadCell > 0 && !disposed;
    }

    public void draw(int pass, Frustum frustum, double camX, double camY, double camZ, double maxDist2) {
        if (!hasSomethingToDraw()) return;
        double ax = li.instance.anchorX, ay = li.instance.anchorY, az = li.instance.anchorZ;
        if (visible.length < cells.length) visible = new int[cells.length];
        int nv = 0;
        for (int i = 0; i < uploadCell; i++) {
            Cell c = cells[i];
            double x0 = ax + c.minX, y0 = ay + c.minY, z0 = az + c.minZ, x1 = ax + c.maxX, y1 = ay + c.maxY, z1 = az + c.maxZ;
            double dx = camX < x0 ? x0 - camX : camX > x1 ? camX - x1 : 0;
            double dy = camY < y0 ? y0 - camY : camY > y1 ? camY - y1 : 0;
            double dz = camZ < z0 ? z0 - camZ : camZ > z1 ? camZ - z1 : 0;
            if (dx * dx + dy * dy + dz * dz > maxDist2) continue;
            if (!frustum.isBoxInFrustum(x0, y0, z0, x1, y1, z1)) continue;
            visible[nv++] = i;
        }
        if (nv == 0) return;
        boolean shader = shaded && Shaders.active();
        GlStateManager.pushMatrix();
        GlStateManager.translate(ax - camX, ay - camY, az - camZ);
        boolean translucentPass = pass == 1;
        for (int g = 0; g < groups.length; g++) {
            Group gr = groups[g];
            if (gr.translucent != translucentPass) continue;
            boolean bound = false;
            Cell lastVolume = null;
            for (int k = 0; k < nv; k++) {
                Cell c = cells[visible[k]];
                VertexBuffer vb = c.vbo[g];
                if (vb == null) continue;
                if (!bound) {
                    GlStateManager.bindTexture(gr.texture >= 0 ? gr.texture : Textures.white());
                    if (shader) Shaders.glow(gr.glow);
                    bound = true;
                }
                if (shader && c != lastVolume) {
                    Shaders.volume(c.texPos, c.texNeg, c.texWorld, c.volX, c.volY, c.volZ, c.volW * c.volStep, c.volH * c.volStep, c.volD * c.volStep);
                    lastVolume = c;
                }
                vb.bindBuffer();
                pointers();
                vb.drawArrays(GL11.GL_TRIANGLES);
            }
        }
        GlStateManager.popMatrix();
    }

    private static void pointers() {
        GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_BYTES, 0);
        GlStateManager.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_BYTES, 12);
        GlStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_BYTES, 16);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glTexCoordPointer(2, GL11.GL_SHORT, VERTEX_BYTES, 24);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glNormalPointer(GL11.GL_BYTE, VERTEX_BYTES, 28L);
    }

    /**
     * Refreshes corner light from the world, nearest cells first, until the deadline. Returns true when it stopped
     * because of the deadline (more work left for the next frame).
     */
    public boolean refreshLight(World world, long deadline, double camX, double camY, double camZ, BlockPos.MutableBlockPos mp) {
        if (!uploadDone() || disposed || cells.length == 0) return false;
        long now = System.currentTimeMillis();
        if (lightOrder == null || lightPos >= lightOrder.length) {
            if (lightOrder != null && now < nextSweep) return false;
            lightOrder = byDistance(camX, camY, camZ);
            lightPos = 0;
            nextSweep = now + 4000;
        }
        boolean perPixel = shaded && Shaders.active();
        while (lightPos < lightOrder.length) {
            if (System.nanoTime() > deadline) return true;
            Cell c = cells[lightOrder[lightPos]];
            if (perPixel && c.texWorld >= 0) {
                if (!sampleWorld(c, world, deadline, mp)) return true;
            } else if (relight(c, world, mp)) {
                reupload(c);
            }
            lightPos++;
        }
        return false;
    }

    private int[] byDistance(double camX, double camY, double camZ) {
        double ax = li.instance.anchorX, ay = li.instance.anchorY, az = li.instance.anchorZ;
        long[] keys = new long[cells.length];
        for (int i = 0; i < cells.length; i++) {
            Cell c = cells[i];
            double dx = ax + (c.minX + c.maxX) / 2 - camX, dy = ay + (c.minY + c.maxY) / 2 - camY, dz = az + (c.minZ + c.maxZ) / 2 - camZ;
            long d = (long) Math.min((double) (1L << 42), dx * dx + dy * dy + dz * dz);
            keys[i] = (d << 20) | i;
        }
        Arrays.sort(keys);
        int[] order = new int[cells.length];
        for (int i = 0; i < keys.length; i++) order[i] = (int) (keys[i] & 0xFFFFF);
        return order;
    }

    private boolean relight(Cell c, World world, BlockPos.MutableBlockPos mp) {
        float[] loc = li.local;
        byte[] nrm = li.normals;
        int ax = li.instance.anchorX, ay = li.instance.anchorY, az = li.instance.anchorZ;
        boolean changed = false;
        int lastX = Integer.MIN_VALUE, lastY = 0, lastZ = 0, lastSky = 15, lastBlock = 0;
        for (int g = 0; g < groups.length; g++) {
            if (groups[g].glow) continue;
            int s = c.groupStart[g], e = s + c.groupCount[g];
            for (int k = s; k < e; k++) {
                int v = c.corners[k];
                int x = (int) Math.floor(ax + loc[3 * v] + nrm[3 * v] / 254f);
                int y = (int) Math.floor(ay + loc[3 * v + 1] + nrm[3 * v + 1] / 254f);
                int z = (int) Math.floor(az + loc[3 * v + 2] + nrm[3 * v + 2] / 254f);
                if (x != lastX || y != lastY || z != lastZ) {
                    lastX = x;
                    lastY = y;
                    lastZ = z;
                    mp.setPos(x, y, z);
                    if (world.getBlockState(mp).isOpaqueCube()) {
                        // the surface sits inside terrain: look one block further out, then above
                        EnumFacing outward = EnumFacing.getFacingFromVector(nrm[3 * v], nrm[3 * v + 1], nrm[3 * v + 2]);
                        mp.setPos(x + outward.getXOffset(), y + outward.getYOffset(), z + outward.getZOffset());
                        if (world.getBlockState(mp).isOpaqueCube()) mp.setPos(x, y + 1, z);
                    }
                    lastSky = world.getLightFor(EnumSkyBlock.SKY, mp);
                    lastBlock = world.getLightFor(EnumSkyBlock.BLOCK, mp);
                }
                char val = (char) (((lastSky * 16) << 8) | (lastBlock * 16));
                if (c.light[k] != val) {
                    c.light[k] = val;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void reupload(Cell c) {
        for (int g = 0; g < groups.length; g++) {
            VertexBuffer vb = c.vbo[g];
            if (vb == null) continue;
            int bytes = c.groupCount[g] * VERTEX_BYTES;
            if (scratch == null || scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 20)).order(ByteOrder.nativeOrder());
            scratch.clear();
            fill(c, g, scratch);
            scratch.flip();
            vb.bufferData(scratch);
        }
    }

    /** Game thread. */
    public void dispose() {
        disposed = true;
        for (Cell c : cells) {
            if (c == null) continue;
            for (int g = 0; g < c.vbo.length; g++) {
                if (c.vbo[g] != null) c.vbo[g].deleteGlBuffers();
                c.vbo[g] = null;
                c.pending[g] = null;
            }
            if (c.texPos >= 0) GL11.glDeleteTextures(c.texPos);
            if (c.texNeg >= 0) GL11.glDeleteTextures(c.texNeg);
            if (c.texWorld >= 0) GL11.glDeleteTextures(c.texWorld);
            c.texPos = c.texNeg = c.texWorld = -1;
            c.volPos = c.volNeg = null;
        }
    }

    public int cellCount() {
        return cells.length;
    }
}
