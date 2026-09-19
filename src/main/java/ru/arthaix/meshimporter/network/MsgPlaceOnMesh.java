package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: the player right-clicked a mesh surface; use the held item there (place a block on the mesh). */
public class MsgPlaceOnMesh implements IMessage {

    public long pos;
    public int facing;
    public float hitX, hitY, hitZ;
    public int hand;

    public MsgPlaceOnMesh() {}

    public MsgPlaceOnMesh(BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, EnumHand hand) {
        this.pos = pos.toLong();
        this.facing = facing.getIndex();
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        this.hand = hand.ordinal();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos);
        buf.writeByte(facing);
        buf.writeFloat(hitX);
        buf.writeFloat(hitY);
        buf.writeFloat(hitZ);
        buf.writeByte(hand);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = buf.readLong();
        facing = buf.readByte();
        hitX = buf.readFloat();
        hitY = buf.readFloat();
        hitZ = buf.readFloat();
        hand = buf.readByte();
    }

    public static class Handler implements IMessageHandler<MsgPlaceOnMesh, IMessage> {
        @Override
        public IMessage onMessage(MsgPlaceOnMesh msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.placeOnMesh(p, BlockPos.fromLong(msg.pos), EnumFacing.byIndex(msg.facing), msg.hitX, msg.hitY, msg.hitZ,
                msg.hand == 1 ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND));
        }
    }
}
