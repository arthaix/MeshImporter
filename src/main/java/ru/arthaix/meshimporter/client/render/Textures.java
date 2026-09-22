package ru.arthaix.meshimporter.client.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLContext;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;

/**
 * GL textures for meshes. Block faces get their own repeating texture (the atlas cannot repeat), with mipmaps so
 * tiled ground does not shimmer at a distance. Model textures are decoded off the game thread and uploaded on it.
 */
public final class Textures {

    /** A decoded image with its mip chain, ready for upload. */
    public static final class Image {
        final List<int[]> levels = new ArrayList<>();
        final List<int[]> sizes = new ArrayList<>();
        final boolean pixelated;

        Image(boolean pixelated) {
            this.pixelated = pixelated;
        }
    }

    private static int white = -1;
    private static final Map<String, int[]> blockFaces = new HashMap<>();
    private static final Map<String, Integer> modelTextures = new ConcurrentHashMap<>();
    private static IntBuffer atlas;
    private static int atlasW, atlasH;
    private static boolean atlasTried;

    private Textures() {}

    public static int white() {
        if (white < 0) white = upload(prepare(new int[] { 0xFFFFFFFF }, 1, 1, true));
        return white;
    }

    private static final Map<Integer, Integer> whites = new HashMap<>();

    /**
     * White with the given alpha. An OptiFine shader pack takes the opacity of a translucent surface from its texture
     * alone (the vertex alpha is ambient occlusion there), so a plain see-through colour has to carry it here.
     */
    public static int white(int alpha) {
        if (alpha >= 255) return white();
        return whites.computeIfAbsent(alpha, a -> upload(prepare(new int[] { (a << 24) | 0xFFFFFF }, 1, 1, true)));
    }

    /** GL texture of a block face and the tint index of its quad (-1 = untinted). Game thread. */
    public static int[] blockFace(IBlockState state, EnumFacing face) {
        String key = Block.getStateId(state) + "/" + face.getIndex();
        int[] cached = blockFaces.get(key);
        if (cached != null) return cached;
        int[] tint = { -1 };
        Image img = spriteImage(faceSprite(state, face, tint));
        int[] result = { img == null ? white() : upload(img), tint[0] };
        blockFaces.put(key, result);
        return result;
    }

    /** First frame of a block face texture as {width, height, ARGB pixels...}; null when unreadable. Game thread. */
    public static int[] faceArgb(IBlockState state, EnumFacing face) {
        Image img = spriteImage(faceSprite(state, face, new int[1]));
        if (img == null) return null;
        int[] size = img.sizes.get(0), px = img.levels.get(0);
        int[] out = new int[2 + px.length];
        out[0] = size[0];
        out[1] = size[1];
        System.arraycopy(px, 0, out, 2, px.length);
        return out;
    }

    /** Sprite of a block face; its tint index goes to tintOut[0] (-1 = untinted). */
    private static TextureAtlasSprite faceSprite(IBlockState state, EnumFacing face, int[] tintOut) {
        Minecraft mc = Minecraft.getMinecraft();
        TextureAtlasSprite sprite = null;
        tintOut[0] = -1;
        try {
            IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
            List<BakedQuad> quads = model.getQuads(state, face, 0L);
            if (quads.isEmpty()) quads = model.getQuads(state, null, 0L);
            if (!quads.isEmpty()) {
                sprite = quads.get(0).getSprite();
                tintOut[0] = quads.get(0).hasTintIndex() ? quads.get(0).getTintIndex() : -1;
            }
            if (sprite == null) sprite = model.getParticleTexture();
        } catch (Throwable ignored) {
            // models that need a world or extended state: fall back below
        }
        return sprite != null ? sprite : mc.getTextureMapBlocks().getMissingSprite();
    }

    public static boolean hasModelTexture(String key) {
        return modelTextures.containsKey(key);
    }

    public static int modelTexture(String key) {
        Integer id = modelTextures.get(key);
        return id == null ? -1 : id;
    }

    /** Game thread. A null image (unreadable file) maps to white. */
    public static int uploadModelTexture(String key, Image img) {
        Integer id = modelTextures.get(key);
        if (id != null) return id;
        int nid = img == null ? white() : upload(img);
        modelTextures.put(key, nid);
        return nid;
    }

    /** Any thread. */
    public static Image decode(byte[] png) throws java.io.IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new java.io.IOException("unsupported image");
        int w = img.getWidth(), h = img.getHeight();
        return prepare(img.getRGB(0, 0, w, h, null, 0, w), w, h, false);
    }

    public static void clear() {
        for (int[] f : blockFaces.values()) if (f[0] != white) GlStateManager.deleteTexture(f[0]);
        for (Integer id : modelTextures.values()) if (id != white) GlStateManager.deleteTexture(id);
        blockFaces.clear();
        modelTextures.clear();
        if (white >= 0) GlStateManager.deleteTexture(white);
        white = -1;
        for (Integer id : whites.values()) GlStateManager.deleteTexture(id);
        whites.clear();
        atlas = null;
        atlasTried = false;
    }

    // ---- images ----

    private static Image spriteImage(TextureAtlasSprite sprite) {
        String name = sprite.getIconName();
        int c = name.indexOf(':');
        String domain = c < 0 ? "minecraft" : name.substring(0, c);
        String path = c < 0 ? name : name.substring(c + 1);
        try (InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(domain, "textures/" + path + ".png")).getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img != null) {
                int w = img.getWidth(), h = img.getHeight();
                if (h > w && h % w == 0) h = w; // animated texture: first frame
                return prepare(img.getRGB(0, 0, w, h, null, 0, w), w, h, true);
            }
        } catch (Exception ignored) {
            // generated or connected-texture sprites have no plain file: read them from the atlas
        }
        return fromAtlas(sprite);
    }

    private static Image fromAtlas(TextureAtlasSprite sprite) {
        if (!atlasTried) {
            atlasTried = true;
            try {
                GlStateManager.bindTexture(Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId());
                atlasW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                atlasH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (atlasW > 0 && atlasH > 0 && (long) atlasW * atlasH <= (1L << 26)) {
                    atlas = BufferUtils.createIntBuffer(atlasW * atlasH);
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, atlas);
                }
            } catch (Throwable t) {
                atlas = null;
            }
        }
        if (atlas == null) return null;
        int x0 = Math.round(sprite.getMinU() * atlasW), x1 = Math.round(sprite.getMaxU() * atlasW);
        int y0 = Math.round(sprite.getMinV() * atlasH), y1 = Math.round(sprite.getMaxV() * atlasH);
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0) return null;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = atlas.get((y0 + y) * atlasW + x0 + x);
        return prepare(px, w, h, true);
    }

    /** ARGB pixels → image with a full mip chain (alpha-weighted box filter). */
    public static Image prepare(int[] px, int w, int h, boolean pixelated) {
        Image img = new Image(pixelated);
        img.levels.add(px);
        img.sizes.add(new int[] { w, h });
        while ((w > 1 || h > 1) && img.levels.size() < 12) {
            int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
            int[] next = new int[nw * nh];
            for (int y = 0; y < nh; y++)
                for (int x = 0; x < nw; x++) {
                    long sa = 0, sr = 0, sg = 0, sb = 0, pr = 0, pg = 0, pb = 0;
                    for (int dy = 0; dy < 2; dy++)
                        for (int dx = 0; dx < 2; dx++) {
                            int c = px[Math.min(h - 1, 2 * y + dy) * w + Math.min(w - 1, 2 * x + dx)];
                            int a = (c >>> 24) & 255;
                            sa += a;
                            sr += ((c >> 16) & 255) * a;
                            sg += ((c >> 8) & 255) * a;
                            sb += (c & 255) * a;
                            pr += (c >> 16) & 255;
                            pg += (c >> 8) & 255;
                            pb += c & 255;
                        }
                    int r, g, b;
                    if (sa > 0) {
                        r = (int) (sr / sa);
                        g = (int) (sg / sa);
                        b = (int) (sb / sa);
                    } else {
                        r = (int) (pr / 4);
                        g = (int) (pg / 4);
                        b = (int) (pb / 4);
                    }
                    next[y * nw + x] = ((int) (sa / 4) << 24) | (r << 16) | (g << 8) | b;
                }
            px = next;
            w = nw;
            h = nh;
            img.levels.add(px);
            img.sizes.add(new int[] { w, h });
        }
        return img;
    }

    private static int upload(Image img) {
        int id = GlStateManager.generateTexture();
        GlStateManager.bindTexture(id);
        int n = img.levels.size();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, n - 1);
        for (int l = 0; l < n; l++) {
            int[] level = img.levels.get(l);
            int[] size = img.sizes.get(l);
            IntBuffer buf = BufferUtils.createIntBuffer(level.length);
            buf.put(level);
            buf.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, l, GL11.GL_RGBA, size[0], size[1], 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, img.pixelated ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, img.pixelated ? GL11.GL_NEAREST : GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        try {
            if (GLContext.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                float max = GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, Math.min(8f, max));
            }
        } catch (Throwable ignored) {
            // no anisotropic filtering on this driver
        }
        return id;
    }
}
