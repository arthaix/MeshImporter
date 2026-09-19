package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: put the uploaded model into the world at one slot of this anchor with this transform. */
public class MsgPlace implements IMessage {

    public long anchor;
    public String hash = "";
    public double[] matrix = new double[16];
    public String name = "";
    public NBTTagCompound settings = new NBTTagCompound();
    public int slot;

    public MsgPlace() {}

    public MsgPlace(BlockPos anchor, String hash, double[] matrix, String name, NBTTagCompound settings, int slot) {
        this.anchor = anchor.toLong();
        this.hash = hash;
        this.matrix = matrix;
        this.name = name;
        this.settings = settings;
        this.slot = slot;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(anchor);
        ByteBufUtils.writeUTF8String(buf, hash);
        for (int i = 0; i < 16; i++) buf.writeDouble(matrix[i]);
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeTag(buf, settings);
        buf.writeInt(slot);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        anchor = buf.readLong();
        hash = ByteBufUtils.readUTF8String(buf);
        for (int i = 0; i < 16; i++) matrix[i] = buf.readDouble();
        name = ByteBufUtils.readUTF8String(buf);
        settings = ByteBufUtils.readTag(buf);
        if (settings == null) settings = new NBTTagCompound();
        slot = buf.readInt();
    }

    public static class Handler implements IMessageHandler<MsgPlace, IMessage> {
        @Override
        public IMessage onMessage(MsgPlace msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.place(p, BlockPos.fromLong(msg.anchor), msg.hash, msg.matrix, msg.name, msg.settings, msg.slot));
        }
    }
}
