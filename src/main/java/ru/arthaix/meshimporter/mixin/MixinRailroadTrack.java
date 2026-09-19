package ru.arthaix.meshimporter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ru.arthaix.meshimporter.compat.MeshRails;

/**
 * Immersive Railroading refuses to build track whose ground is not solid. A mesh bridge or deck carries track just as
 * well, so a rail resting on mesh geometry counts as grounded.
 */
@Mixin(targets = "cam72cam.immersiverailroading.track.TrackBase", remap = false)
public abstract class MixinRailroadTrack {

    @Inject(method = "isDownSolid(Z)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void meshimporter$meshIsGround(boolean flexible, CallbackInfoReturnable<Boolean> cir) {
        if (MeshRails.onMesh(this)) cir.setReturnValue(true);
    }
}
