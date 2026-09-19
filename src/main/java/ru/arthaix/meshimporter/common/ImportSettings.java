package ru.arthaix.meshimporter.common;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Everything the anchor block remembers about its model, serialisable to NBT (tile entity and packets). */
public final class ImportSettings {

    public String modelPath = "";
    public double scale = 1.0;
    /**
     * Which axis points up in the .obj FILE. false = Y up (Blender's default export already converts to Y up),
     * true = Z up (exported with Forward Y / Up Z). The GUI always uses Blender axes (Z up) regardless.
     */
    public boolean zUp = false;
    /** Rotation around the anchor block in quarter turns (0..3). */
    public int rotation = 0;
    /** Mirror inside the model's bounding box, in Blender axes (Z = up). */
    public boolean mirrorX = false, mirrorY = false, mirrorZ = false;
    /** Offset in blocks, in Blender axes (Z = up). */
    public int offsetX, offsetY, offsetZ;
    /** Horizontal snap: the model's min X/Z corner lands on the anchor block. */
    public boolean snapXZ = true;
    /** Vertical snap: the model's lowest point lands on the anchor block. Off = height from the Blender origin. */
    public boolean snapY = false;
    /** "Origin as in model": ignore both snaps, the Blender scene origin lands on the anchor block. */
    public boolean modelOrigin = false;
    public String defaultBlock = "minecraft:stone";
    public final List<MaterialSetup> materials = new ArrayList<>();

    public MaterialSetup material(String name) {
        for (MaterialSetup m : materials)
            if (m.name.equals(name)) return m;
        return null;
    }

    /** Returns the setup for the name, creating a default one when unknown. */
    public MaterialSetup materialOrCreate(String name) {
        MaterialSetup m = material(name);
        if (m == null) {
            m = new MaterialSetup(name);
            m.blockId = defaultBlock;
            materials.add(m);
        }
        return m;
    }

    public ImportSettings copy() {
        return read(write(new NBTTagCompound()));
    }

    public NBTTagCompound write(NBTTagCompound nbt) {
        nbt.setString("mi_model", modelPath);
        nbt.setDouble("mi_scale", scale);
        nbt.setBoolean("mi_zup", zUp);
        nbt.setInteger("mi_rot", rotation);
        nbt.setBoolean("mi_mirx", mirrorX);
        nbt.setBoolean("mi_miry", mirrorY);
        nbt.setBoolean("mi_mirz", mirrorZ);
        nbt.setInteger("mi_offx", offsetX);
        nbt.setInteger("mi_offy", offsetY);
        nbt.setInteger("mi_offz", offsetZ);
        nbt.setBoolean("mi_snapxz", snapXZ);
        nbt.setBoolean("mi_snapy", snapY);
        nbt.setBoolean("mi_origin", modelOrigin);
        nbt.setString("mi_defblock", defaultBlock);
        NBTTagList list = new NBTTagList();
        for (MaterialSetup m : materials) {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("name", m.name);
            t.setString("blk", m.blockId);
            t.setInteger("mode", m.mode.ordinal());
            t.setInteger("tint", m.color);
            if (m.texturePath != null) t.setString("tex", m.texturePath);
            t.setBoolean("skip", m.skip);
            t.setBoolean("glow", m.glow);
            list.appendTag(t);
        }
        nbt.setTag("mi_materials", list);
        return nbt;
    }

    public static ImportSettings read(NBTTagCompound nbt) {
        ImportSettings s = new ImportSettings();
        s.modelPath = nbt.getString("mi_model");
        if (nbt.hasKey("mi_scale")) s.scale = nbt.getDouble("mi_scale");
        s.zUp = nbt.getBoolean("mi_zup");
        s.rotation = nbt.getInteger("mi_rot") & 3;
        s.mirrorX = nbt.getBoolean("mi_mirx");
        s.mirrorY = nbt.getBoolean("mi_miry");
        s.mirrorZ = nbt.getBoolean("mi_mirz");
        s.offsetX = nbt.getInteger("mi_offx");
        s.offsetY = nbt.getInteger("mi_offy");
        s.offsetZ = nbt.getInteger("mi_offz");
        s.snapXZ = !nbt.hasKey("mi_snapxz") || nbt.getBoolean("mi_snapxz");
        s.snapY = nbt.getBoolean("mi_snapy");
        s.modelOrigin = nbt.getBoolean("mi_origin");
        if (nbt.hasKey("mi_defblock")) s.defaultBlock = nbt.getString("mi_defblock");
        NBTTagList list = nbt.getTagList("mi_materials", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            MaterialSetup m = new MaterialSetup(t.getString("name"));
            m.blockId = t.getString("blk");
            m.mode = MaterialSetup.Mode.fromOrdinal(t.getInteger("mode"));
            m.color = t.getInteger("tint");
            m.texturePath = t.hasKey("tex") ? t.getString("tex") : null;
            m.skip = t.getBoolean("skip");
            m.glow = t.getBoolean("glow");
            s.materials.add(m);
        }
        return s;
    }
}
