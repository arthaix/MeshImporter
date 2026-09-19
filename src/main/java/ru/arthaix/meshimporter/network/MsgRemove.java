package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: remove the model of one anchor slot, and the slot itself when asked. */
public class MsgRemove implements IMessage {

    public long anchor;
    public int slot;
    public boolean dropSlot;

    public MsgRemove() {}

    public MsgRemove(BlockPos anchor, int slot, boolean dropSlot) {
        this.anchor = anchor.toLong();
        this.slot = slot;
        this.dropSlot = dropSlot;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(anchor);
        buf.writeInt(slot);
        buf.writeBoolean(dropSlot);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        anchor = buf.readLong();
        slot = buf.readInt();
        dropSlot = buf.readBoolean();
    }

    public static class Handler implements IMessageHandler<MsgRemove, IMessage> {
        @Override
        public IMessage onMessage(MsgRemove msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.removeByAnchor(p, BlockPos.fromLong(msg.anchor), msg.slot, msg.dropSlot));
        }
    }
}
