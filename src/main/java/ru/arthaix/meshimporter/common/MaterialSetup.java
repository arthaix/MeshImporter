package ru.arthaix.meshimporter.common;

/** How one material of the model looks in the world. Indexed like {@link ru.arthaix.meshimporter.model.ObjMesh#materials}. */
public final class MaterialSetup {

    public enum Mode {
        /** Texture of a Minecraft block, tiled once per block over the surface (optionally tinted). */
        BLOCK,
        /** Flat colour (Kd from the .mtl, or the colour picked in the GUI). */
        KD,
        /** The model's own texture (map_Kd or an explicit image) with the model's UV coordinates. */
        TEXTURE;

        public static Mode fromOrdinal(int o) {
            Mode[] v = values();
            return o >= 0 && o < v.length ? v[o] : BLOCK;
        }
    }

    public final String name;
    /** Registry name with optional meta, e.g. "minecraft:stone" or "minecraft:stone:1". */
    public String blockId = "minecraft:stone";
    public Mode mode = Mode.BLOCK;
    /** ARGB: the colour in KD mode, the tint in BLOCK and TEXTURE mode (white = untinted). Alpha < 255 = translucent. */
    public int color = 0xffffffff;
    /** Absolute or obj-relative image path used in TEXTURE mode; null = map_Kd from the .mtl. */
    public String texturePath;
    /** Leave this material out of the import. */
    public boolean skip;
    /** Drawn at full brightness (lamps, screens, neon). */
    public boolean glow;

    public MaterialSetup(String name) {
        this.name = name;
    }

    public MaterialSetup copy() {
        MaterialSetup m = new MaterialSetup(name);
        m.blockId = blockId;
        m.mode = mode;
        m.color = color;
        m.texturePath = texturePath;
        m.skip = skip;
        m.glow = glow;
        return m;
    }
}
