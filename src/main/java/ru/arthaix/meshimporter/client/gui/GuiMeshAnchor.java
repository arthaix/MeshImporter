package ru.arthaix.meshimporter.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshimporter.block.TileEntityMeshAnchor;
import ru.arthaix.meshimporter.client.BuildSession;
import ru.arthaix.meshimporter.client.ClientMeshes;
import ru.arthaix.meshimporter.client.ClientPrefs;
import ru.arthaix.meshimporter.client.FileChooserHelper;
import ru.arthaix.meshimporter.client.ModelCache;
import ru.arthaix.meshimporter.common.BlockRef;
import ru.arthaix.meshimporter.common.ImportSettings;
import ru.arthaix.meshimporter.common.MaterialSetup;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.instance.MeshWorld;
import ru.arthaix.meshimporter.model.MtlLibrary;
import ru.arthaix.meshimporter.model.ObjStreamParser;
import ru.arthaix.meshimporter.network.MsgAnchorSettings;
import ru.arthaix.meshimporter.network.MsgTeleport;
import ru.arthaix.meshimporter.network.Net;

/**
 * The anchor's screen. One anchor can hold several models: the row at the top picks which one is being edited and
 * adds new ones, below it are the model file, its placement, one row per material and build / place / remove.
 */
public class GuiMeshAnchor extends GuiScreen {

    private static final int W = 440, H = 284;
    private static final int LIST_Y = 114, LIST_H = 108, ROW_H = 20;
    /** What sits below the material list, and the shortest list still worth showing. */
    private static final int BELOW_LIST = H - LIST_Y - LIST_H, LIST_MIN = 40;
    private static final int SLOT_ROW_H = 14;
    private static final String[] MODE_NAMES = { "Block", "Color", "Texture" };
    private static final String[] ROT_NAMES = { "0°", "90°", "180°", "270°" };

    private static final int B_BROWSE = 1, B_SCAN = 2, B_AXIS = 3, B_ROT = 4, B_MX = 5, B_MY = 6, B_MZ = 7, B_SXZ = 8, B_SY = 9, B_ORIGIN = 10, B_DEFBLOCK = 11,
        B_BUILD = 20, B_PLACE = 21, B_REMOVE = 22, B_TP = 23, B_DONE = 24, B_SLOT = 30, B_ADD = 31, B_DROP = 32;

    private final BlockPos pos;
    /** The height of the screen and of the material list, both cut down to what the window can show. */
    private int guiH = H, listH = LIST_H;
    private ImportSettings settings;
    /** Which model of this anchor the screen is editing. */
    private int slot;
    private boolean slotListOpen;
    private final List<String> shown = new ArrayList<>();
    private int left, top, scroll;
    private String note = "";
    private boolean autoScanned;
    private GuiTextField path, scale, offX, offY, offZ;
    private GuiButton axis, rot, mirX, mirY, mirZ, snapXZ, snapY, origin, defBlock, build, place, slotButton, dropSlot;

    public GuiMeshAnchor(BlockPos pos) {
        this.pos = pos;
        loadSlot(0);
    }

    /** The anchor block itself, null when it is gone or not loaded. */
    private TileEntityMeshAnchor anchor() {
        Minecraft mc = Minecraft.getMinecraft();
        TileEntity te = mc.world == null ? null : mc.world.getTileEntity(pos);
        return te instanceof TileEntityMeshAnchor ? (TileEntityMeshAnchor) te : null;
    }

    private int slotCount() {
        TileEntityMeshAnchor te = anchor();
        return Math.max(1, te == null ? 1 : te.slotCount());
    }

    /** Takes the settings of one model of this anchor into the screen. */
    private void loadSlot(int index) {
        TileEntityMeshAnchor te = anchor();
        slot = Math.max(0, index);
        settings = te != null ? te.settings(slot) : new ImportSettings();
        shown.clear();
        for (MaterialSetup m : settings.materials) shown.add(m.name);
        scroll = 0;
    }

    /** Saves what is on screen, then switches to another model of this anchor. */
    private void switchSlot(int index) {
        collect();
        save();
        loadSlot(index);
        if (path != null) {
            path.setText(settings.modelPath);
            scale.setText(trimNumber(settings.scale));
            offX.setText(String.valueOf(settings.offsetX));
            offY.setText(String.valueOf(settings.offsetY));
            offZ.setText(String.valueOf(settings.offsetZ));
        }
        note = "";
        refreshLabels();
        if (!settings.modelPath.isEmpty() && new java.io.File(settings.modelPath).isFile()) scan();
    }

    /** What the picker shows for a model: its name in the world, else the file, else empty. */
    private String slotName(int index) {
        TileEntityMeshAnchor te = anchor();
        int id = te == null ? 0 : te.instanceId(index);
        if (id > 0 && mc != null && mc.world != null) {
            LoadedInstance li = MeshWorld.CLIENT.get(mc.world.provider.getDimension(), id);
            if (li != null && !li.instance.name.isEmpty()) return li.instance.name;
            return "model #" + id;
        }
        String file = te != null ? te.settings(index).modelPath : "";
        if (file.isEmpty()) return TextFormatting.DARK_GRAY + "empty";
        return file.substring(file.replace((char) 92, (char) 47).lastIndexOf((char) 47) + 1);
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        // a small window (or a large GUI scale) leaves less room: the material list gives it up, the rest stays put
        listH = Math.max(LIST_MIN, Math.min(LIST_H, height - 8 - LIST_Y - BELOW_LIST));
        guiH = LIST_Y + listH + BELOW_LIST;
        left = (width - W) / 2;
        top = Math.max(2, (height - guiH) / 2);
        buttonList.clear();
        slotButton = add(new GuiButton(B_SLOT, left + 44, top + 6, 300, 16, ""));
        add(new GuiButton(B_ADD, left + 350, top + 6, 22, 16, "+"));
        dropSlot = add(new GuiButton(B_DROP, left + 376, top + 6, 58, 16, "Delete"));

        path = field(1, left + 44, top + 30, 300, 32767, settings.modelPath);
        add(new GuiButton(B_BROWSE, left + 350, top + 28, 22, 16, "..."));
        add(new GuiButton(B_SCAN, left + 376, top + 28, 58, 16, "Scan"));

        scale = field(2, left + 44, top + 54, 40, 16, trimNumber(settings.scale));
        axis = add(new GuiButton(B_AXIS, left + 90, top + 52, 46, 16, ""));
        rot = add(new GuiButton(B_ROT, left + 140, top + 52, 36, 16, ""));
        mirX = add(new GuiButton(B_MX, left + 218, top + 52, 26, 16, ""));
        mirY = add(new GuiButton(B_MY, left + 246, top + 52, 26, 16, ""));
        mirZ = add(new GuiButton(B_MZ, left + 274, top + 52, 26, 16, ""));
        snapXZ = add(new GuiButton(B_SXZ, left + 336, top + 52, 34, 16, ""));
        snapY = add(new GuiButton(B_SY, left + 372, top + 52, 26, 16, ""));

        offX = field(3, left + 44, top + 78, 40, 8, String.valueOf(settings.offsetX));
        offY = field(4, left + 90, top + 78, 40, 8, String.valueOf(settings.offsetY));
        offZ = field(5, left + 136, top + 78, 40, 8, String.valueOf(settings.offsetZ));
        origin = add(new GuiButton(B_ORIGIN, left + 184, top + 76, 72, 16, ""));
        defBlock = add(new GuiButton(B_DEFBLOCK, left + 294, top + 76, 140, 16, ""));

        build = add(new GuiButton(B_BUILD, left + 6, top + guiH - 24, 84, 20, "Build preview"));
        place = add(new GuiButton(B_PLACE, left + 94, top + guiH - 24, 70, 20, "Place"));
        add(new GuiButton(B_REMOVE, left + 168, top + guiH - 24, 70, 20, "Remove"));
        add(new GuiButton(B_TP, left + 242, top + guiH - 24, 40, 20, "TP"));
        add(new GuiButton(B_DONE, left + W - 76, top + guiH - 24, 70, 20, "Done"));
        refreshLabels();
        scroll = Math.min(scroll, maxScroll());
        if (!autoScanned) {
            // the anchor may remember materials of an older model: always show the ones of the current file
            autoScanned = true;
            if (!settings.modelPath.isEmpty() && new java.io.File(settings.modelPath).isFile()) scan();
        }
    }

    private GuiButton add(GuiButton b) {
        buttonList.add(b);
        return b;
    }

    private GuiTextField field(int id, int x, int y, int w, int maxLength, String text) {
        GuiTextField f = new GuiTextField(id, fontRenderer, x, y, w, 12);
        f.setMaxStringLength(maxLength);
        f.setText(text == null ? "" : text);
        return f;
    }

    private List<GuiTextField> fields() {
        return Arrays.asList(path, scale, offX, offY, offZ);
    }

    private static String trimNumber(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String on(boolean b) {
        return (b ? TextFormatting.GREEN : TextFormatting.DARK_GRAY).toString();
    }

    /** A switch shows whether it is on: a green tick, or a grey cross. */
    private static String check(boolean b) {
        return b ? TextFormatting.GREEN + "✔ " : TextFormatting.DARK_GRAY + "✕ ";
    }

    private void refreshLabels() {
        axis.displayString = settings.zUp ? "Up: Z" : "Up: Y";
        rot.displayString = ROT_NAMES[settings.rotation & 3];
        mirX.displayString = check(settings.mirrorX) + "X";
        mirY.displayString = check(settings.mirrorY) + "Y";
        mirZ.displayString = check(settings.mirrorZ) + "Z";
        snapXZ.displayString = check(settings.snapXZ) + "XZ";
        snapY.displayString = check(settings.snapY) + "Y";
        origin.displayString = check(settings.modelOrigin) + "Origin";
        // Origin puts the Blender origin on the anchor and leaves no room for snapping: the two switches go dark
        snapXZ.enabled = !settings.modelOrigin;
        snapY.enabled = !settings.modelOrigin;
        boolean busy = BuildSession.INSTANCE.busy;
        build.enabled = !busy;
        place.enabled = !busy;
        int count = slotCount();
        if (slot >= count) loadSlot(count - 1);
        slotButton.displayString = (slot + 1) + "/" + count + "   " + mc.fontRenderer.trimStringToWidth(slotName(slot), 240);
        dropSlot.enabled = count > 1 || anchor() == null || anchor().instanceId(slot) > 0;
    }

    private void collect() {
        if (path == null) return;
        settings.modelPath = path.getText().trim();
        try {
            double sc = Double.parseDouble(scale.getText().trim().replace(',', '.'));
            if (sc > 0 && !Double.isInfinite(sc)) settings.scale = sc;
        } catch (NumberFormatException ignored) {
            // keep the previous scale
        }
        settings.offsetX = parseInt(offX.getText());
        settings.offsetY = parseInt(offY.getText());
        settings.offsetZ = parseInt(offZ.getText());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void save() {
        Net.CH.sendToServer(new MsgAnchorSettings(pos, slot, settings.write(new NBTTagCompound())));
        ClientPrefs.rememberPresets(settings.materials);
        ClientPrefs.addRecent(settings.modelPath);
    }

    private MaterialSetup setup(String name) {
        MaterialSetup m = settings.material(name);
        if (m == null) {
            m = settings.materialOrCreate(name);
            ClientPrefs.applyPreset(m);
        }
        return m;
    }

    // ---- drawing ----

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        drawDefaultBackground();
        drawRect(left, top, left + W, top + guiH, 0xF0101010);
        drawRect(left, top, left + W, top + 1, 0xFF00B8CC);
        drawString(fontRenderer, "Models", left + 6, top + 10, 0xFFFFFF);
        drawString(fontRenderer, "Model", left + 6, top + 32, 0xFFFFFF);
        drawString(fontRenderer, "Scale", left + 6, top + 56, 0xFFFFFF);
        drawString(fontRenderer, "Mirror", left + 182, top + 56, 0xFFFFFF);
        drawString(fontRenderer, "Snap", left + 306, top + 56, 0xFFFFFF);
        drawString(fontRenderer, "Offset", left + 6, top + 80, 0xFFFFFF);
        drawString(fontRenderer, "Block", left + 262, top + 80, 0xFFFFFF);
        for (GuiTextField f : fields()) f.drawTextBox();
        drawList(mx, my);

        BuildSession bs = BuildSession.INSTANCE;
        int py = top + LIST_Y + listH + 6;
        drawRect(left + 6, py, left + W - 6, py + 4, 0xFF2A2A2A);
        float p = bs.progress >= 0 ? bs.progress : ModelCache.INSTANCE.downloadProgress();
        if (p >= 0) drawRect(left + 6, py, left + 6 + (int) ((W - 12) * Math.min(1f, p)), py + 4, 0xFF00B8CC);
        String status = !note.isEmpty() && !bs.busy ? note : bs.status;
        drawString(fontRenderer, fontRenderer.trimStringToWidth(status, W - 12), left + 6, py + 9, 0xFFFFFF);
        drawString(fontRenderer, fontRenderer.trimStringToWidth(placedInfo(), W - 12), left + 6, py + 21, 0x9A9A9A);

        super.drawScreen(mx, my, partialTicks);

        ItemStack def = BlockRef.toStack(settings.defaultBlock);
        RenderHelper.enableGUIStandardItemLighting();
        itemRender.renderItemAndEffectIntoGUI(def, defBlock.x + 3, defBlock.y);
        RenderHelper.disableStandardItemLighting();
        drawString(fontRenderer, fontRenderer.trimStringToWidth(def.getDisplayName(), defBlock.width - 26), defBlock.x + 22, defBlock.y + 4, 0xFFFFFF);
        if (slotListOpen) drawSlotList(mx, my);
        else tooltips(mx, my);
    }

    private void drawList(int mx, int my) {
        int x0 = left + 6, y0 = top + LIST_Y, w = W - 12;
        drawString(fontRenderer, "Materials: " + shown.size() + (shown.isEmpty() ? "   choose a model and press Scan" : ""), x0, y0 - 11, 0xFFFFFF);
        if (!shown.isEmpty()) {
            drawString(fontRenderer, "block texture", x0 + 100, y0 - 11, 0x808080);
            drawString(fontRenderer, "look", x0 + 266, y0 - 11, 0x808080);
        }
        drawRect(x0, y0, x0 + w, y0 + listH, 0xFF000000);
        int sf = new ScaledResolution(mc).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x0 * sf, mc.displayHeight - (y0 + listH) * sf, w * sf, listH * sf);
        boolean inList = mx >= x0 && mx < x0 + w && my >= y0 && my < y0 + listH;
        for (int i = 0; i < shown.size(); i++) {
            int ry = y0 + i * ROW_H - scroll;
            if (ry + ROW_H <= y0 || ry >= y0 + listH) continue;
            MaterialSetup m = setup(shown.get(i));
            boolean rowHover = inList && my >= ry && my < ry + ROW_H;
            if (rowHover) drawRect(x0, ry, x0 + w, ry + ROW_H, 0xFF1C1C1C);
            drawString(fontRenderer, (m.skip ? TextFormatting.DARK_GRAY.toString() : "") + fontRenderer.trimStringToWidth(m.name, 92), x0 + 4, ry + 6, 0xFFFFFF);

            boolean blockMode = m.mode == MaterialSetup.Mode.BLOCK;
            cell(x0 + 100, ry + 2, 150, 16, rowHover && mx >= x0 + 100 && mx < x0 + 250);
            ItemStack stack = BlockRef.toStack(m.blockId);
            RenderHelper.enableGUIStandardItemLighting();
            itemRender.renderItemAndEffectIntoGUI(stack, x0 + 101, ry + 2);
            RenderHelper.disableStandardItemLighting();
            drawString(fontRenderer, fontRenderer.trimStringToWidth(stack.getDisplayName(), 128), x0 + 119, ry + 6, blockMode ? 0xFFFFFF : 0x707070);

            cell(x0 + 254, ry + 2, 50, 16, rowHover && mx >= x0 + 254 && mx < x0 + 304);
            drawCenteredString(fontRenderer, MODE_NAMES[m.mode.ordinal()], x0 + 279, ry + 6, 0xFFFFFF);

            boolean swatchHover = rowHover && mx >= x0 + 307 && mx < x0 + 325;
            drawRect(x0 + 307, ry + 2, x0 + 325, ry + 18, swatchHover ? 0xFFFFFFFF : 0xFF606060);
            drawRect(x0 + 308, ry + 3, x0 + 324, ry + 17, 0xFF000000 | (m.color & 0xFFFFFF));

            cell(x0 + 329, ry + 2, 40, 16, rowHover && mx >= x0 + 329 && mx < x0 + 369);
            drawCenteredString(fontRenderer, (m.glow ? TextFormatting.YELLOW : TextFormatting.DARK_GRAY) + "Glow", x0 + 349, ry + 6, 0xFFFFFF);
            cell(x0 + 373, ry + 2, 40, 16, rowHover && mx >= x0 + 373 && mx < x0 + 413);
            drawCenteredString(fontRenderer, (m.skip ? TextFormatting.RED : TextFormatting.DARK_GRAY) + "Skip", x0 + 393, ry + 6, 0xFFFFFF);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        int max = maxScroll();
        if (max > 0) {
            int barH = Math.max(12, listH * listH / (shown.size() * ROW_H));
            int barY = y0 + (listH - barH) * scroll / max;
            drawRect(x0 + w - 3, barY, x0 + w, barY + barH, 0xFF808080);
        }
    }

    /** The open picker: one row per model of this anchor. */
    private void drawSlotList(int mx, int my) {
        int count = slotCount();
        int x0 = slotButton.x, y0 = slotButton.y + 17, w = slotButton.width, h = count * SLOT_ROW_H;
        drawRect(x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, 0xFF00B8CC);
        drawRect(x0, y0, x0 + w, y0 + h, 0xFF101010);
        for (int i = 0; i < count; i++) {
            int ry = y0 + i * SLOT_ROW_H;
            boolean hover = mx >= x0 && mx < x0 + w && my >= ry && my < ry + SLOT_ROW_H;
            if (hover) drawRect(x0, ry, x0 + w, ry + SLOT_ROW_H, 0xFF2A2A2A);
            drawString(fontRenderer, (i + 1) + ".", x0 + 4, ry + 3, i == slot ? 0x00D8F0 : 0x9A9A9A);
            drawString(fontRenderer, fontRenderer.trimStringToWidth(slotName(i), w - 30), x0 + 22, ry + 3, i == slot ? 0x00D8F0 : 0xFFFFFF);
        }
    }

    private void cell(int x, int y, int w, int h, boolean hover) {
        drawRect(x, y, x + w, y + h, hover ? 0xFF505050 : 0xFF303030);
    }

    private int maxScroll() {
        return Math.max(0, shown.size() * ROW_H - listH);
    }

    private String placedInfo() {
        if (mc.world == null) return "";
        int id = anchor() == null ? 0 : anchor().instanceId(slot);
        LoadedInstance li = placed();
        if (li != null) return "Placed: model #" + li.instance.id + " " + li.instance.name + ", " + String.format("%,d", li.instance.triangles) + " triangles";
        if (id > 0) return "Placed: model #" + id + " (loading...)";
        return "Nothing placed in this slot yet";
    }

    /** The model of the selected slot, null when this slot is empty or still loading. */
    private LoadedInstance placed() {
        TileEntityMeshAnchor te = anchor();
        if (te == null || mc.world == null) return null;
        int id = te.instanceId(slot);
        return id > 0 ? MeshWorld.CLIENT.get(mc.world.provider.getDimension(), id) : null;
    }

    private void tooltips(int mx, int my) {
        for (GuiButton b : buttonList) {
            if (!b.isMouseOver()) continue;
            String[] lines;
            switch (b.id) {
                case B_SLOT: lines = new String[] { "Which model of this anchor is being edited", "One anchor can hold as many models as you like" }; break;
                case B_ADD: lines = new String[] { "Add another model to this anchor", "It starts with the placement of the model shown now" }; break;
                case B_DROP: lines = new String[] { "Remove this model from the world and drop the slot" }; break;
                case B_BROWSE: lines = new String[] { "Choose a .obj file", "(recent files are listed in the dialog)" }; break;
                case B_SCAN: lines = new String[] { "Read the materials of the model" }; break;
                case B_AXIS: lines = new String[] { "Which axis points UP in the .obj file", "Up: Y - Blender's default export", "Up: Z - exported with Up = Z", "Offset and Mirror are always in Blender axes (Z = up)" }; break;
                case B_ROT: lines = new String[] { "Rotation around the anchor block" }; break;
                case B_MX: case B_MY: case B_MZ: lines = new String[] { "Flip the model inside its bounding box", "(Blender axes, Z = up)" }; break;
                case B_SXZ: lines = settings.modelOrigin
                    ? new String[] { "The model's min X/Z corner goes onto the anchor block", "Origin is on, so this switch has no say" }
                    : new String[] { "The model's min X/Z corner goes onto the anchor block", "Off: X/Z from the Blender origin" };
                    break;
                case B_SY: lines = settings.modelOrigin
                    ? new String[] { "The model's lowest point goes onto the anchor block", "Origin is on, so this switch has no say" }
                    : new String[] { "The model's lowest point goes onto the anchor block", "Off: height from the Blender origin (stable across re-exports)" };
                    break;
                case B_ORIGIN: lines = new String[] { "Blender scene origin (0,0,0) = anchor block, no snapping",
                    "On: models of one scene stand together exactly as they do there" }; break;
                case B_DEFBLOCK: lines = new String[] { "Block texture for materials that have no setup yet" }; break;
                case B_BUILD: lines = new String[] { "Build the mesh and show it where it will stand", "Nothing is sent to the server" }; break;
                case B_PLACE: lines = new String[] { "Upload the model and put it into the world", "Replaces the model of this slot" }; break;
                case B_REMOVE: lines = new String[] { "Take the model of this slot out of the world", "The slot and its settings stay" }; break;
                case B_TP: lines = new String[] { "Teleport above the model (or the preview)" }; break;
                default: lines = null;
            }
            if (lines != null) drawHoveringText(Arrays.asList(lines), mx, my);
            return;
        }
        int x0 = left + 6, y0 = top + LIST_Y;
        if (mx >= x0 && mx < x0 + W - 12 && my >= y0 && my < y0 + listH) {
            int rx = mx - x0;
            String[] lines = null;
            if (rx >= 100 && rx < 250) lines = new String[] { "Block whose texture covers this material", "(tiled once per block, like real blocks)" };
            else if (rx >= 254 && rx < 304) lines = new String[] { "Block: the block texture above", "Color: flat colour", "Texture: the model's own texture (map_Kd)", "Right-click: previous" };
            else if (rx >= 307 && rx < 325) lines = new String[] { "Colour, or tint for Block/Texture", "Opacity below 100% = see-through" };
            else if (rx >= 329 && rx < 369) lines = new String[] { "Full brightness at night (lamps, screens, neon)" };
            else if (rx >= 373 && rx < 413) lines = new String[] { "Leave this material out" };
            if (lines != null) drawHoveringText(Arrays.asList(lines), mx, my);
        }
    }

    // ---- input ----

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        collect();
        if (b.id != B_SLOT) slotListOpen = false;
        switch (b.id) {
            case B_SLOT:
                slotListOpen = !slotListOpen;
                break;
            case B_ADD:
                collect();
                save();
                // Another model of the same anchor almost always comes out of the same .blend, so the new slot starts
                // with the placement of the one it was added from - scale, axis, rotation, mirrors, offset, snapping.
                // With its own defaults instead it would snap by its own bounding box and land beside and below.
                ImportSettings from = settings.copy();
                from.modelPath = "";
                from.materials.clear();
                loadSlot(slotCount());
                settings = from;
                shown.clear();
                if (path != null) {
                    path.setText("");
                    scale.setText(trimNumber(settings.scale));
                    offX.setText(String.valueOf(settings.offsetX));
                    offY.setText(String.valueOf(settings.offsetY));
                    offZ.setText(String.valueOf(settings.offsetZ));
                }
                refreshLabels();
                save();
                note = TextFormatting.GRAY + "Model " + (slot + 1) + ": choose a .obj file, placed like model " + slot;
                break;
            case B_DROP:
                note = "";
                BuildSession.INSTANCE.remove(pos, slot, true);
                loadSlot(Math.max(0, slot - 1));
                if (path != null) {
                    path.setText(settings.modelPath);
                    scale.setText(trimNumber(settings.scale));
                    offX.setText(String.valueOf(settings.offsetX));
                    offY.setText(String.valueOf(settings.offsetY));
                    offZ.setText(String.valueOf(settings.offsetZ));
                }
                break;
            case B_BROWSE:
                FileChooserHelper.choose(settings.modelPath, ClientPrefs.recent(), picked -> {
                    settings.modelPath = picked;
                    if (path != null) path.setText(picked);
                    scan();
                });
                break;
            case B_SCAN:
                scan();
                break;
            case B_AXIS:
                settings.zUp = !settings.zUp;
                break;
            case B_ROT:
                settings.rotation = (settings.rotation + 1) & 3;
                break;
            case B_MX:
                settings.mirrorX = !settings.mirrorX;
                break;
            case B_MY:
                settings.mirrorY = !settings.mirrorY;
                break;
            case B_MZ:
                settings.mirrorZ = !settings.mirrorZ;
                break;
            case B_SXZ:
                settings.snapXZ = !settings.snapXZ;
                break;
            case B_SY:
                settings.snapY = !settings.snapY;
                break;
            case B_ORIGIN:
                settings.modelOrigin = !settings.modelOrigin;
                break;
            case B_DEFBLOCK:
                mc.displayGuiScreen(new GuiBlockPicker(this, settings.defaultBlock, id -> settings.defaultBlock = id));
                return;
            case B_BUILD:
                note = "";
                save();
                BuildSession.INSTANCE.build(settings, pos, slot, false);
                break;
            case B_PLACE:
                note = "";
                save();
                BuildSession.INSTANCE.build(settings, pos, slot, true);
                break;
            case B_REMOVE:
                note = "";
                BuildSession.INSTANCE.remove(pos, slot, false);
                break;
            case B_TP:
                teleport();
                break;
            case B_DONE:
                mc.displayGuiScreen(null);
                return;
            default:
                break;
        }
        refreshLabels();
    }

    /** Reads the model's material names in the background (the file can be hundreds of MB) and shows them. */
    private void scan() {
        collect();
        final String p = settings.modelPath;
        if (p.isEmpty()) {
            note = TextFormatting.RED + "Choose a .obj file first";
            return;
        }
        note = TextFormatting.GRAY + "Reading materials...";
        Thread t = new Thread(() -> {
            ObjStreamParser.MaterialScan result = null;
            String error = null;
            try {
                result = BuildSession.INSTANCE.scanMaterials(p);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final ObjStreamParser.MaterialScan sc = result;
            final String err = error;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().currentScreen != this || !p.equals(settings.modelPath)) return;
                if (sc == null) note = TextFormatting.RED + err;
                else applyScan(sc);
            });
        }, "meshimporter-scan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Shows exactly the materials of the scanned model. Setups of materials that are not in this model (left over
     * from models this anchor imported before) are dropped from the anchor; they stay in the presets by name.
     */
    private void applyScan(ObjStreamParser.MaterialScan sc) {
        List<String> names = sc.materialNames();
        int untinted = 0;
        boolean changedList = !names.equals(shown);
        shown.clear();
        shown.addAll(names);
        MtlLibrary mtl = BuildSession.INSTANCE.mtl;
        for (String name : shown) {
            if (settings.material(name) != null) continue;
            MaterialSetup m = settings.materialOrCreate(name);
            MtlLibrary.Material mm = mtl == null ? null : mtl.materials.get(name);
            if (mm != null) {
                m.color = mm.kdArgb();
                m.mode = mm.mapKd != null ? MaterialSetup.Mode.TEXTURE : MaterialSetup.Mode.KD;
            }
            ClientPrefs.applyPreset(m);
        }
        // Block-mode materials still carrying the untouched .mtl colour (from before Block mode stopped using it)
        for (String name : shown) {
            MaterialSetup m = settings.material(name);
            MtlLibrary.Material mm = mtl == null ? null : mtl.materials.get(name);
            if (m != null && mm != null && m.mode == MaterialSetup.Mode.BLOCK && (m.color & 0xFFFFFF) == (mm.kdArgb() & 0xFFFFFF) && (m.color & 0xFFFFFF) != 0xFFFFFF) {
                m.color = (m.color & 0xFF000000) | 0xFFFFFF;
                untinted++;
            }
        }
        java.util.Set<String> keep = new java.util.HashSet<>(names);
        int before = settings.materials.size();
        settings.materials.removeIf(m -> !keep.contains(m.name));
        if (changedList) scroll = 0;
        List<String> problems = BuildSession.INSTANCE.problems;
        note = TextFormatting.GRAY + "Found " + shown.size() + " materials" + (problems.isEmpty() ? "" : " (" + problems.get(0) + ")");
        if (settings.materials.size() != before || untinted > 0) save();
    }

    private void teleport() {
        LoadedInstance li = placed();
        double[] b = li != null ? li.instance.bounds : ClientMeshes.INSTANCE.previewBounds();
        if (b == null) {
            note = TextFormatting.YELLOW + "Build a preview or place the model first";
            return;
        }
        Net.CH.sendToServer(new MsgTeleport((b[0] + b[3]) / 2, Math.min(255, b[4] + 3), (b[2] + b[5]) / 2));
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        if (slotListOpen) {
            int count = slotCount();
            int x0 = slotButton.x, y0 = slotButton.y + 17;
            boolean inList = mx >= x0 && mx < x0 + slotButton.width && my >= y0 && my < y0 + count * SLOT_ROW_H;
            if (inList) {
                int i = (my - y0) / SLOT_ROW_H;
                slotListOpen = false;
                if (i != slot) switchSlot(i);
                mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1f));
                return;
            }
            slotListOpen = false;
        }
        super.mouseClicked(mx, my, button);
        if (mc.currentScreen != this) return;
        for (GuiTextField f : fields()) f.mouseClicked(mx, my, button);
        int x0 = left + 6, y0 = top + LIST_Y;
        if (mx >= x0 && mx < x0 + W - 12 && my >= y0 && my < y0 + listH) {
            int i = (my - y0 + scroll) / ROW_H;
            if (i >= 0 && i < shown.size()) clickRow(setup(shown.get(i)), mx - x0, button);
        }
    }

    private void clickRow(MaterialSetup m, int rx, int button) {
        if (rx >= 100 && rx < 250) {
            collect();
            mc.displayGuiScreen(new GuiBlockPicker(this, m.blockId, id -> {
                m.blockId = id;
                if (m.mode != MaterialSetup.Mode.BLOCK) switchMode(m, MaterialSetup.Mode.BLOCK);
            }));
            return;
        }
        if (rx >= 307 && rx < 325) {
            collect();
            mc.displayGuiScreen(new GuiColorPicker(this, m.color, c -> m.color = c));
            return;
        }
        if (rx >= 254 && rx < 304) switchMode(m, MaterialSetup.Mode.fromOrdinal((m.mode.ordinal() + (button == 1 ? 2 : 1)) % 3));
        else if (rx >= 329 && rx < 369) m.glow = !m.glow;
        else if (rx >= 373 && rx < 413) m.skip = !m.skip;
        else return;
        mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    /**
     * A block is shown as the block looks: switching to Block mode drops the .mtl colour that was only meant for
     * Colour mode (a dark Kd would otherwise tint the block almost black). Switching back restores the .mtl colour.
     * Alpha (translucency) is kept. A tint picked by hand in Block mode stays until the mode changes.
     */
    private void switchMode(MaterialSetup m, MaterialSetup.Mode mode) {
        MaterialSetup.Mode old = m.mode;
        m.mode = mode;
        int alpha = m.color & 0xFF000000;
        if (mode == MaterialSetup.Mode.BLOCK && old != MaterialSetup.Mode.BLOCK) {
            m.color = alpha | 0xFFFFFF;
        } else if (mode != MaterialSetup.Mode.BLOCK && old == MaterialSetup.Mode.BLOCK) {
            MtlLibrary mtl = BuildSession.INSTANCE.mtl;
            MtlLibrary.Material mm = mtl == null ? null : mtl.materials.get(m.name);
            if (mm != null) m.color = alpha | (mm.kdArgb() & 0xFFFFFF);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(d) * ROW_H));
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && path.isFocused()) {
            scan();
            return;
        }
        for (GuiTextField f : fields()) {
            if (f.isFocused()) {
                f.textboxKeyTyped(c, key);
                return;
            }
        }
    }

    @Override
    public void updateScreen() {
        for (GuiTextField f : fields()) f.updateCursorCounter();
        refreshLabels();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        collect();
        save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
