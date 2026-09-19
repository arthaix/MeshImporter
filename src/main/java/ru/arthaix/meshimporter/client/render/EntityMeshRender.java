package ru.arthaix.meshimporter.client.render;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/**
 * Client-only placeholder that lives in the world's weather-effect list. Minecraft renders that list in the entity
 * pass (after solid terrain, before water) and again in the translucent pass, whatever chunks are loaded, which is
 * exactly where meshes belong. It never moves, never saves and never collides.
 */
public class EntityMeshRender extends Entity {

    public EntityMeshRender(World world) {
        super(world);
        setSize(0f, 0f);
        ignoreFrustumCheck = true;
        noClip = true;
    }

    @Override
    protected void entityInit() {}

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {}

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {}

    @Override
    public void onUpdate() {}

    @Override
    public boolean writeToNBTOptional(NBTTagCompound compound) {
        return false;
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return pass == 0 || pass == 1;
    }

    @Override
    public boolean isInRangeToRender3d(double x, double y, double z) {
        return true;
    }

    @Override
    public boolean isInRangeToRenderDist(double distance) {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }
}
