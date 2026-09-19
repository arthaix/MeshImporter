package ru.arthaix.meshimporter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import ru.arthaix.meshimporter.compat.MeshAim;

/**
 * LittleTiles places at the exact hit point only inside blocks that already hold tiles; in an empty block it moves
 * the placement to the next block face. When the aim is a mesh surface in an empty block, place at the hit point, so
 * tiles sit on the mesh instead of up to a block away from it.
 */
@Mixin(targets = "com.creativemd.littletiles.common.util.place.PlacementHelper", remap = false)
public abstract class MixinLittlePlacementHelper {

    @Inject(method = "canBePlacedInside(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/EnumFacing;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void meshimporter$placeOnMesh(World world, BlockPos pos, Vec3d hitVec, EnumFacing facing, CallbackInfoReturnable<Boolean> cir) {
        if (MeshAim.matches(pos, hitVec) && world.getBlockState(pos).getBlock().isReplaceable(world, pos)) cir.setReturnValue(true);
    }
}
