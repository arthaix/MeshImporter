package ru.arthaix.meshimporter.client.gui;

import java.io.IOException;
import java.util.function.Consumer;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.client.config.GuiSlider;

/** Red / green / blue / opacity sliders and a hex field. */
public class GuiColorPicker extends CompactScreen implements GuiSlider.ISlider {

    private static final int W = 230, H = 172;

    private final GuiScreen parent;
    private final Consumer<Integer> onPick;
    private int argb;
    private GuiSlider red, green, blue, alpha;
    private GuiTextField hex;
    private int left, top;
    private boolean updating;

    public GuiColorPicker(GuiScreen parent, int argb, Consumer<Integer> onPick) {
        this.parent = parent;
        this.argb = argb;
        this.onPick = onPick;
    }

    @Override
    protected int neededWidth() {
        return W + 4;
    }

    @Override
    protected int neededHeight() {
        return H + 4;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        updating = true;
        red = slider(10, 20, "Red: ", (argb >> 16) & 255);
        green = slider(11, 44, "Green: ", (argb >> 8) & 255);
        blue = slider(12, 68, "Blue: ", argb & 255);
        alpha = slider(13, 92, "Opacity: ", (argb >>> 24) & 255);
        updating = false;
        hex = new GuiTextField(0, fontRenderer, left + 10, top + 124, 70, 14);
        hex.setMaxStringLength(8);
        hex.setText(hexString());
        buttonList.add(new GuiButton(1, left + W - 132, top + H - 26, 58, 20, "Done"));
        buttonList.add(new GuiButton(2, left + W - 68, top + H - 26, 58, 20, "Cancel"));
    }

    private GuiSlider slider(int id, int y, String label, int value) {
        GuiSlider s = new GuiSlider(id, left + 10, top + y, W - 20, 20, label, "", 0, 255, value, false, true, this);
        buttonList.add(s);
        return s;
    }

    private String hexString() {
        int a = (argb >>> 24) & 255;
        return a == 255 ? String.format("%06X", argb & 0xFFFFFF) : String.format("%08X", argb);
    }

    @Override
    public void onChangeSliderValue(GuiSlider slider) {
        if (updating || red == null || green == null || blue == null || alpha == null) return;
        argb = (alpha.getValueInt() << 24) | (red.getValueInt() << 16) | (green.getValueInt() << 8) | blue.getValueInt();
        if (hex != null) hex.setText(hexString());
    }

    @Override
    protected void drawContent(int mx, int my, float partialTicks) {
        drawDefaultBackground();
        drawRect(left, top, left + W, top + H, 0xF0101010);
        drawRect(left, top, left + W, top + 1, 0xFF00B8CC);
        drawString(fontRenderer, "Colour", left + 10, top + 7, 0xFFFFFF);
        super.drawContent(mx, my, partialTicks);
        hex.drawTextBox();
        int sx = left + 90, sy = top + 120;
        // checkerboard behind the swatch shows the opacity
        for (int i = 0; i < 12; i++)
            for (int j = 0; j < 3; j++)
                drawRect(sx + i * 10, sy + j * 7, sx + i * 10 + 10, sy + j * 7 + 7, ((i + j) & 1) == 0 ? 0xFF909090 : 0xFF505050);
        drawRect(sx, sy, sx + 120, sy + 21, argb);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (hex.textboxKeyTyped(c, key)) {
            String s = hex.getText().trim();
            try {
                long v = Long.parseLong(s, 16);
                int parsed;
                if (s.length() == 6) parsed = 0xFF000000 | (int) v;
                else if (s.length() == 8) parsed = (int) v;
                else return;
                argb = parsed;
                updating = true;
                red.setValue((argb >> 16) & 255);
                green.setValue((argb >> 8) & 255);
                blue.setValue(argb & 255);
                alpha.setValue((argb >>> 24) & 255);
                red.updateSlider();
                green.updateSlider();
                blue.updateSlider();
                alpha.updateSlider();
                updating = false;
            } catch (NumberFormatException ignored) {
                // incomplete input
            }
        }
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        super.mouseClicked(mx, my, button);
        if (mc.currentScreen == this) hex.mouseClicked(mx, my, button);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            onPick.accept(argb);
            mc.displayGuiScreen(parent);
        } else if (button.id == 2) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void updateScreen() {
        hex.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
