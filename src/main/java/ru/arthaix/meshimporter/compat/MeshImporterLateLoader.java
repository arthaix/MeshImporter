package ru.arthaix.meshimporter.compat;

import java.util.Collections;
import java.util.List;

import net.minecraftforge.fml.common.Loader;
import zone.rong.mixinbooter.ILateMixinLoader;

/** Registers the LittleTiles and Immersive Railroading mixins with MixinBooter, when those mods are installed. */
public class MeshImporterLateLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.meshimporter.json");
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        return Loader.isModLoaded("littletiles") || Loader.isModLoaded("immersiverailroading");
    }
}
