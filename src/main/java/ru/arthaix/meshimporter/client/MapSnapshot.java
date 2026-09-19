package ru.arthaix.meshimporter.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.meshimporter.client.render.Textures;
import ru.arthaix.meshimporter.common.BlockRef;
import ru.arthaix.meshimporter.common.MaterialSetup;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.model.MeshModel;

/**
 * A placed model seen from straight above, as an image a map mod can lay over the map. Triangles are rasterised into
 * the model's footprint keeping the highest surface per pixel, with the block texture of the material where it has
 * one; glass and other see-through materials are blended over whatever lies under them.
 */
public final class MapSnapshot {

    /** How a material looks from above: its block texture ({width, height, ARGB...}) and the colour over it. */
    public static final class Look {
        int[] texture;
        byte[] ownTexture;
        int colour = 0xFFFFFFFF;
        boolean translucent, glow;
    }

    private MapSnapshot() {}

    /** Game thread: block textures and biome tints come from the client. */
    public static Look[] look(MeshModel model, World world, BlockPos anchor) {
        Minecraft mc = Minecraft.getMinecraft();
        Look[] looks = new Look[model.materials.length];
        for (int i = 0; i < looks.length; i++) {
            MeshModel.Material mat = model.materials[i];
            Look look = new Look();
            look.glow = mat.glow;
            look.colour = mat.color;
            look.translucent = ((mat.color >>> 24) & 255) < 255;
            if (mat.mode == MaterialSetup.Mode.BLOCK) {
                IBlockState state = BlockRef.parse(mat.blockId);
                look.translucent |= state.getBlock().getRenderLayer() == BlockRenderLayer.TRANSLUCENT;
                int[] face = Textures.blockFace(state, EnumFacing.UP);
                int tint = -1;
                if (face[1] >= 0) {
                    try {
                        tint = mc.getBlockColors().colorMultiplier(state, world, anchor, face[1]);
                    } catch (RuntimeException ignored) {
                        tint = -1;
                    }
                }
                look.colour = multiply(mat.color, tint);
                look.texture = Textures.faceArgb(state, EnumFacing.UP);
            } else if (mat.mode == MaterialSetup.Mode.TEXTURE && mat.texture >= 0 && mat.texture < model.textures.length) {
                look.ownTexture = model.textures[mat.texture];
            }
            looks[i] = look;
        }
        return looks;
    }

    /** Worker thread. Null when the model has no footprint to draw. */
    public static BufferedImage render(LoadedInstance li, Look[] looks, int pixelsPerBlock) {
        double[] b = li.instance.bounds;
        int x0 = (int) Math.floor(b[0]), z0 = (int) Math.floor(b[2]);
        int blocksX = Math.max(1, (int) Math.ceil(b[3]) - x0), blocksZ = Math.max(1, (int) Math.ceil(b[5]) - z0);
        int px = Math.max(1, pixelsPerBlock);
        while (px > 1 && ((long) blocksX * px > 2048 || (long) blocksZ * px > 2048)) px--;
        int w = blocksX * px, h = blocksZ * px;
        if ((long) w * h > 8_000_000L) return null;

        for (Look look : looks)
            if (look.ownTexture != null) {
                look.colour = multiply(look.colour, average(look.ownTexture));
                look.ownTexture = null;
            }

        float[] depth = new float[w * h], glassDepth = new float[w * h];
        int[] colour = new int[w * h], glass = new int[w * h];
        Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        Arrays.fill(glassDepth, Float.NEGATIVE_INFINITY);
        MeshModel model = li.model;
        int[] idx = model.indices;
        float[] p = li.local;
        byte[] nrm = li.normals;
        double ax = li.instance.anchorX, ay = li.instance.anchorY, az = li.instance.anchorZ;
        for (int m = 0; m < model.materials.length; m++) {
            Look look = looks[m];
            boolean seeThrough = look.translucent;
            for (int t = model.matStart[m], end = t + model.matCount[m]; t < end; t++) {
                int a = idx[3 * t], b1 = idx[3 * t + 1], c = idx[3 * t + 2];
                double shade = 1;
                if (!look.glow) {
                    double nx = nrm[3 * a] / 127.0, ny = nrm[3 * a + 1] / 127.0, nz = nrm[3 * a + 2] / 127.0;
                    shade = Math.min(1, nx * nx * 0.6 + nz * nz * 0.8 + ny * ny * (ny > 0 ? 1 : 0.5));
                }
                triangle(p, a, b1, c, ax, ay, az, x0, z0, px, w, h, look, shade, seeThrough ? glassDepth : depth, seeThrough ? glass : colour);
            }
        }

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int under = depth[i] == Float.NEGATIVE_INFINITY ? 0 : colour[i];
            out[i] = glassDepth[i] > depth[i] ? blend(glass[i], under) : under;
        }
        image.setRGB(0, 0, w, h, out, 0, w);
        return image;
    }

    /** Rasterises one triangle from above, keeping the highest surface per pixel. */
    private static void triangle(float[] p, int a, int b, int c, double ax, double ay, double az, int x0, int z0, int px, int w, int h,
        Look look, double shade, float[] depth, int[] colour) {
        double axp = (p[3 * a] + ax - x0) * px, azp = (p[3 * a + 2] + az - z0) * px, ayw = p[3 * a + 1] + ay;
        double bxp = (p[3 * b] + ax - x0) * px, bzp = (p[3 * b + 2] + az - z0) * px, byw = p[3 * b + 1] + ay;
        double cxp = (p[3 * c] + ax - x0) * px, czp = (p[3 * c + 2] + az - z0) * px, cyw = p[3 * c + 1] + ay;
        double area = (bxp - axp) * (czp - azp) - (bzp - azp) * (cxp - axp);
        if (Math.abs(area) < 1e-9) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(axp, Math.min(bxp, cxp))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(axp, Math.max(bxp, cxp))));
        int minY = Math.max(0, (int) Math.floor(Math.min(azp, Math.min(bzp, czp))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(azp, Math.max(bzp, czp))));
        for (int y = minY; y <= maxY; y++) {
            double sy = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double sx = x + 0.5;
                double w0 = ((bxp - sx) * (czp - sy) - (bzp - sy) * (cxp - sx)) / area;
                double w1 = ((cxp - sx) * (azp - sy) - (czp - sy) * (axp - sx)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float top = (float) (w0 * ayw + w1 * byw + w2 * cyw);
                int i = y * w + x;
                if (top <= depth[i]) continue;
                depth[i] = top;
                colour[i] = shaded(look, shade, x0 + sx / px, z0 + sy / px);
            }
        }
    }

    private static int shaded(Look look, double shade, double worldX, double worldZ) {
        int c = look.colour;
        if (look.texture != null) {
            int tw = look.texture[0], th = look.texture[1];
            int u = (int) Math.floor((worldX - Math.floor(worldX)) * tw), v = (int) Math.floor((worldZ - Math.floor(worldZ)) * th);
            c = multiply(c, look.texture[2 + Math.min(th - 1, Math.max(0, v)) * tw + Math.min(tw - 1, Math.max(0, u))]);
        }
        int alpha = (c >>> 24) & 255;
        int r = (int) (((c >> 16) & 255) * shade), g = (int) (((c >> 8) & 255) * shade), b = (int) ((c & 255) * shade);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** See-through colour over what lies under it; with nothing under it, it stays partly transparent. */
    private static int blend(int over, int under) {
        int overAlpha = (over >>> 24) & 255;
        if (overAlpha == 0) return under;
        int underAlpha = (under >>> 24) & 255;
        double f = underAlpha == 0 ? 1 : 0.55;
        int r = (int) (((over >> 16) & 255) * f + ((under >> 16) & 255) * (1 - f));
        int g = (int) (((over >> 8) & 255) * f + ((under >> 8) & 255) * (1 - f));
        int b = (int) ((over & 255) * f + (under & 255) * (1 - f));
        int a = underAlpha == 0 ? Math.max(120, overAlpha) : Math.max(underAlpha, overAlpha);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int multiply(int argb, int rgb) {
        if (rgb == -1) return argb;
        int r = ((argb >> 16) & 255) * ((rgb >> 16) & 255) / 255;
        int g = ((argb >> 8) & 255) * ((rgb >> 8) & 255) / 255;
        int b = (argb & 255) * (rgb & 255) / 255;
        int a = ((argb >>> 24) & 255) * ((rgb >>> 24) & 255) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Average colour of a PNG, weighted by alpha; white when it cannot be read. */
    private static int average(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return -1;
            long r = 0, g = 0, b = 0, a = 0;
            int w = img.getWidth(), h = img.getHeight(), stepX = Math.max(1, w / 128), stepY = Math.max(1, h / 128);
            for (int y = 0; y < h; y += stepY)
                for (int x = 0; x < w; x += stepX) {
                    int c = img.getRGB(x, y), al = (c >>> 24) & 255;
                    r += ((c >> 16) & 255) * al;
                    g += ((c >> 8) & 255) * al;
                    b += (c & 255) * al;
                    a += al;
                }
            if (a == 0) return -1;
            return 0xFF000000 | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
