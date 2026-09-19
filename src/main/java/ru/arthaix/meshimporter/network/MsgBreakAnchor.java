package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: the player held the attack button on this anchor long enough, take it out with its models. */
public class MsgBreakAnchor implements IMessage {

    public long pos;

    public MsgBreakAnchor() {}

    public MsgBreakAnchor(BlockPos pos) {
        this.pos = pos.toLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = buf.readLong();
    }

    public static class Handler implements IMessageHandler<MsgBreakAnchor, IMessage> {
        @Override
        public IMessage onMessage(MsgBreakAnchor msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.breakAnchor(p, BlockPos.fromLong(msg.pos)));
        }
    }
}
