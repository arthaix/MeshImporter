package ru.arthaix.meshimporter.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumFacing;
import ru.arthaix.meshimporter.client.render.Textures;
import ru.arthaix.meshimporter.network.MsgWebTexture;
import ru.arthaix.meshimporter.network.Net;

/**
 * Sends the block face textures of placed models to the server once per session, for the web map export (a server
 * has no textures of its own). The server keeps them only from players allowed to place models.
 */
public final class WebTextures {

    private static final Set<String> sent = new HashSet<>();

    private WebTextures() {}

    /** Game thread. */
    public static void offer(String blockId, IBlockState state, EnumFacing face, int tint) {
        String key = blockId + "#" + face.getIndex();
        if (key.length() > 160 || !sent.add(key)) return;
        int[] image;
        try {
            image = Textures.faceArgb(state, face);
        } catch (RuntimeException e) {
            image = null;
        }
        if (image == null) return;
        int[] argb = image;
        ClientMeshes.WORKERS.execute(() -> {
            byte[] png = encode(argb);
            if (png == null || png.length > 28_000) return;
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                if (mc.getConnection() != null) Net.CH.sendToServer(new MsgWebTexture(key, tint, png));
            });
        });
    }

    public static void reset() {
        sent.clear();
    }

    /** Square power of two up to 32 pixels (nearest neighbour; first animation frame), as PNG. */
    private static byte[] encode(int[] image) {
        int w = image[0], h = image[1];
        int tw = pot(w), th = pot(h);
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < th; y++)
            for (int x = 0; x < tw; x++) out.setRGB(x, y, image[2 + (y * h / th) * w + x * w / tw]);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static int pot(int v) {
        int p = 1;
        while (p < v && p < 32) p <<= 1;
        return p;
    }
}
