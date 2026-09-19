package ru.arthaix.meshimporter.block;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import ru.arthaix.meshimporter.common.ImportSettings;

/**
 * One anchor can hold several models. Every slot remembers its import settings (so the GUI reopens as it was left)
 * and which placed model it belongs to. Anchors saved by older versions read back as a single slot.
 */
public class TileEntityMeshAnchor extends TileEntity {

    /** One model of this anchor. */
    public static final class Slot {
        /** Id of the placed model, 0 = this slot has nothing in the world yet. */
        public int instanceId;
        public NBTTagCompound settings = new NBTTagCompound();
    }

    private final List<Slot> slots = new ArrayList<>();

    public int slotCount() {
        return slots.size();
    }

    public ImportSettings settings(int slot) {
        return ImportSettings.read(slot >= 0 && slot < slots.size() ? slots.get(slot).settings : new NBTTagCompound());
    }

    public int instanceId(int slot) {
        return slot >= 0 && slot < slots.size() ? slots.get(slot).instanceId : 0;
    }

    /** Slot holding this placed model, -1 when none does. */
    public int slotOf(int instanceId) {
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).instanceId == instanceId) return i;
        return -1;
    }

    public void setSettings(int slot, ImportSettings s) {
        grow(slot);
        slots.get(slot).settings = s.write(new NBTTagCompound());
        sync();
    }

    public void setInstanceId(int slot, int id) {
        grow(slot);
        slots.get(slot).instanceId = id;
        sync();
    }

    /** Drops a slot entirely; the model it held has to be removed separately. */
    public void removeSlot(int slot) {
        if (slot < 0 || slot >= slots.size()) return;
        slots.remove(slot);
        sync();
    }

    private void grow(int slot) {
        while (slots.size() <= slot) slots.add(new Slot());
    }

    private void sync() {
        markDirty();
        if (world != null) world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        slots.clear();
        NBTTagList list = nbt.getTagList("slots", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            Slot slot = new Slot();
            slot.instanceId = t.getInteger("instance");
            slot.settings = t.getCompoundTag("settings");
            slots.add(slot);
        }
        if (slots.isEmpty() && (nbt.hasKey("instance") || nbt.hasKey("settings"))) {
            // an anchor from before one anchor could hold several models
            Slot slot = new Slot();
            slot.instanceId = nbt.getInteger("instance");
            slot.settings = nbt.getCompoundTag("settings");
            slots.add(slot);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagList list = new NBTTagList();
        for (Slot slot : slots) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("instance", slot.instanceId);
            t.setTag("settings", slot.settings);
            list.appendTag(t);
        }
        nbt.setTag("slots", list);
        return nbt;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }
}
