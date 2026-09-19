package ru.arthaix.meshimporter.instance;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import ru.arthaix.meshimporter.common.Transform;

/** One placed model: which file, where, and how it is transformed. Small enough to live in the world's save data. */
public final class MeshInstance {

    public int id;
    public int dim;
    /** Model file hash (see ModelStore / ModelCache). */
    public String hash = "";
    public int anchorX, anchorY, anchorZ;
    /** Model → world, row-major 4x4. */
    public double[] matrix = Transform.identity();
    /** World bounding box: minX, minY, minZ, maxX, maxY, maxZ. */
    public double[] bounds = new double[6];
    /** Model file name, for lists. */
    public String name = "";
    public String owner = "";
    public long time;
    public int triangles;

    public AxisAlignedBB aabb() {
        return new AxisAlignedBB(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    public boolean intersects(AxisAlignedBB box) {
        return box.maxX > bounds[0] && box.minX < bounds[3] && box.maxY > bounds[1] && box.minY < bounds[4] && box.maxZ > bounds[2] && box.minZ < bounds[5];
    }

    public NBTTagCompound write() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("id", id);
        nbt.setInteger("dim", dim);
        nbt.setString("hash", hash);
        nbt.setInteger("ax", anchorX);
        nbt.setInteger("ay", anchorY);
        nbt.setInteger("az", anchorZ);
        NBTTagCompound m = new NBTTagCompound();
        for (int i = 0; i < 16; i++) m.setDouble("m" + i, matrix[i]);
        nbt.setTag("matrix", m);
        NBTTagCompound b = new NBTTagCompound();
        for (int i = 0; i < 6; i++) b.setDouble("b" + i, bounds[i]);
        nbt.setTag("bounds", b);
        nbt.setString("name", name);
        nbt.setString("owner", owner);
        nbt.setLong("time", time);
        nbt.setInteger("tris", triangles);
        return nbt;
    }

    public static MeshInstance read(NBTTagCompound nbt) {
        MeshInstance in = new MeshInstance();
        in.id = nbt.getInteger("id");
        in.dim = nbt.getInteger("dim");
        in.hash = nbt.getString("hash");
        in.anchorX = nbt.getInteger("ax");
        in.anchorY = nbt.getInteger("ay");
        in.anchorZ = nbt.getInteger("az");
        NBTTagCompound m = nbt.getCompoundTag("matrix");
        for (int i = 0; i < 16; i++) in.matrix[i] = m.getDouble("m" + i);
        NBTTagCompound b = nbt.getCompoundTag("bounds");
        for (int i = 0; i < 6; i++) in.bounds[i] = b.getDouble("b" + i);
        in.name = nbt.getString("name");
        in.owner = nbt.getString("owner");
        in.time = nbt.getLong("time");
        in.triangles = nbt.getInteger("tris");
        return in;
    }

    public void write(ByteBuf buf) {
        buf.writeInt(id);
        buf.writeInt(dim);
        ByteBufUtils.writeUTF8String(buf, hash);
        buf.writeInt(anchorX);
        buf.writeInt(anchorY);
        buf.writeInt(anchorZ);
        for (int i = 0; i < 16; i++) buf.writeDouble(matrix[i]);
        for (int i = 0; i < 6; i++) buf.writeDouble(bounds[i]);
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, owner);
        buf.writeLong(time);
        buf.writeInt(triangles);
    }

    public static MeshInstance read(ByteBuf buf) {
        MeshInstance in = new MeshInstance();
        in.id = buf.readInt();
        in.dim = buf.readInt();
        in.hash = ByteBufUtils.readUTF8String(buf);
        in.anchorX = buf.readInt();
        in.anchorY = buf.readInt();
        in.anchorZ = buf.readInt();
        for (int i = 0; i < 16; i++) in.matrix[i] = buf.readDouble();
        for (int i = 0; i < 6; i++) in.bounds[i] = buf.readDouble();
        in.name = ByteBufUtils.readUTF8String(buf);
        in.owner = ByteBufUtils.readUTF8String(buf);
        in.time = buf.readLong();
        in.triangles = buf.readInt();
        return in;
    }

    public MeshInstance copy() {
        MeshInstance c = new MeshInstance();
        c.id = id;
        c.dim = dim;
        c.hash = hash;
        c.anchorX = anchorX;
        c.anchorY = anchorY;
        c.anchorZ = anchorZ;
        c.matrix = matrix.clone();
        c.bounds = bounds.clone();
        c.name = name;
        c.owner = owner;
        c.time = time;
        c.triangles = triangles;
        return c;
    }
}
