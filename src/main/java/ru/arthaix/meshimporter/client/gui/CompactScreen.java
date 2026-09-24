package ru.arthaix.meshimporter.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Mouse;

/**
 * A screen drawn one GUI scale step finer than the rest of the game, and finer still until it fits the window.
 *
 * The screens of this mod are big; at the scale Minecraft picks on its own they ran off the bottom of the monitor.
 * The screen is simply given the width and height it would have at the smaller scale and drawn through a matching
 * shrink, so every position inside it stays as written. Clicks already come in at the right place, because Minecraft
 * works them out from the screen's own width and height; only the pointer handed to drawScreen needs the same care.
 */
public abstract class CompactScreen extends GuiScreen {

    /** Pixels per GUI unit on this screen. */
    protected int guiScale = 1;
    private float shrink = 1f;

    /** The room the screen needs, in GUI units; the scale goes down until the window has it. */
    protected int neededWidth() {
        return 0;
    }

    protected int neededHeight() {
        return 0;
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        int game = new ScaledResolution(mc).getScaleFactor();
        int s = Math.max(1, game - 1);
        while (s > 1 && (mc.displayWidth / s < neededWidth() || mc.displayHeight / s < neededHeight())) s--;
        guiScale = s;
        shrink = (float) s / game;
        super.setWorldAndResolution(mc, Math.max(1, mc.displayWidth / s), Math.max(1, mc.displayHeight / s));
    }

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int mx = Mouse.getX() * width / mc.displayWidth;
        int my = height - Mouse.getY() * height / mc.displayHeight - 1;
        GlStateManager.pushMatrix();
        GlStateManager.scale(shrink, shrink, 1f);
        try {
            drawContent(mx, my, partialTicks);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** What drawScreen is elsewhere: the pointer is already in this screen's own units. */
    protected void drawContent(int mx, int my, float partialTicks) {
        super.drawScreen(mx, my, partialTicks);
    }
}
