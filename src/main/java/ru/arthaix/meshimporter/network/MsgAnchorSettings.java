package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: store the GUI settings of one anchor slot, so they survive reopening and restarts. */
public class MsgAnchorSettings implements IMessage {

    public long anchor;
    public NBTTagCompound settings = new NBTTagCompound();
    public int slot;

    public MsgAnchorSettings() {}

    public MsgAnchorSettings(BlockPos anchor, int slot, NBTTagCompound settings) {
        this.anchor = anchor.toLong();
        this.slot = slot;
        this.settings = settings;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(anchor);
        buf.writeInt(slot);
        ByteBufUtils.writeTag(buf, settings);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        anchor = buf.readLong();
        slot = buf.readInt();
        settings = ByteBufUtils.readTag(buf);
        if (settings == null) settings = new NBTTagCompound();
    }

    public static class Handler implements IMessageHandler<MsgAnchorSettings, IMessage> {
        @Override
        public IMessage onMessage(MsgAnchorSettings msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.storeSettings(p, BlockPos.fromLong(msg.anchor), msg.slot, msg.settings));
        }
    }
}
