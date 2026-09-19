package ru.arthaix.meshimporter.client;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import ru.arthaix.meshimporter.collision.TriangleGrid;
import ru.arthaix.meshimporter.compat.MeshAim;
import ru.arthaix.meshimporter.instance.MeshWorld;
import ru.arthaix.meshimporter.network.MsgPlaceOnMesh;
import ru.arthaix.meshimporter.network.Net;

/**
 * Looking at a mesh: right-click with a block places it on the surface, left-click does not dig the blocks hidden
 * behind the mesh, and the target block is outlined. Items that are not plain blocks (LittleTiles tools, Immersive
 * Railroading blueprints) get Minecraft's own aim moved onto the mesh surface, so they act there instead of on
 * whatever stands behind the mesh. Also draws the preview's bounding frame.
 */
public final class MeshPicking {

    public static final MeshPicking INSTANCE = new MeshPicking();

    private static final class Target {
        /** Block cell in front of the surface. */
        BlockPos pos;
        /** The model is only built for this player and stands nowhere yet. */
        boolean preview;
        EnumFacing facing;
        float hx, hy, hz;
        double x, y, z, nx, ny, nz;
    }

    private Class<?> littleTool;
    private boolean littleToolLooked;
    private Field rightClickDelay;
    private boolean rightClickDelayLooked;
    /** The aim result this class put into Minecraft, null when Minecraft's own aim stands. */
    private RayTraceResult aimed;
    private boolean aimedInside;

    private MeshPicking() {}

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (!event.isButtonstate()) return;
        int button = event.getButton();
        if (button != 0 && button != 1) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.player == null || mc.world == null || !mc.inGameHasFocus || mc.player.isSpectator()) return;
        Target t = target(mc, 1f);
        if (t == null) return;
        if (button == 0) {
            event.setCanceled(true);
            return;
        }
        EnumHand hand = mc.player.getHeldItemMainhand().isEmpty() ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
        ItemStack stack = mc.player.getHeldItem(hand);
        if (stack.isEmpty()) return;
        boolean little = holdsLittleTool(mc);
        if (t.preview) {
            // the preview stands nowhere yet: the server knows nothing about it and could not put anything on it
            mc.ingameGUI.setOverlayMessage("This model is only a preview - place it first", false);
            event.setCanceled(true);
            return;
        }
        if (!little) {
            // A block, an Immersive Railroading blueprint, a bucket: the server uses the item at the mesh hit, the
            // same way blocks are placed on a mesh. Running it on the client as well would leave it with blocks the
            // server never placed - an Immersive Railroading track preview among them.
            if (stack.getItem() instanceof ItemBlock && !mc.world.getBlockState(t.pos).getBlock().isReplaceable(mc.world, t.pos)) return;
            Net.CH.sendToServer(new MsgPlaceOnMesh(t.pos, t.facing, t.hx, t.hy, t.hz, hand));
            mc.player.swingArm(hand);
            event.setCanceled(true);
            return;
        }
        // A LittleTiles tool: it places at the hit point itself, inside the block holding the surface, and it needs
        // the click on the client. Minecraft ignores right-clicks on air blocks, so the block click runs from here.
        if (!aimAtMesh(mc, 1f, little)) return;
        event.setCanceled(true);
        RayTraceResult r = aimed;
        EnumActionResult result = mc.playerController.processRightClickBlock(mc.player, mc.world, r.getBlockPos(), r.sideHit, r.hitVec, hand);
        // nothing wanted the block: use the item itself, so eating and drawing a bow still work in front of a mesh
        if (result != EnumActionResult.SUCCESS && !mc.player.isHandActive()) result = mc.playerController.processRightClick(mc.player, mc.world, hand);
        if (result == EnumActionResult.SUCCESS) mc.player.swingArm(hand);
    }

    /** Every frame, after Minecraft updated its aim: move it onto the mesh for LittleTiles' preview and outline. */
    @SubscribeEvent
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || mc.player.isSpectator() || !holdsLittleTool(mc)) {
            clearAim();
            return;
        }
        aimAtMesh(mc, (float) event.getRenderPartialTicks(), true);
    }

    /**
     * Holding right-click repeats the click every few ticks with Minecraft's own aim, which would place behind the
     * mesh. While a LittleTiles tool aims at a mesh the repeat is held back: one placement per click.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || aimed == null || mc.objectMouseOver != aimed || !mc.gameSettings.keyBindUseItem.isKeyDown()) return;
        if (!rightClickDelayLooked) {
            rightClickDelayLooked = true;
            try {
                rightClickDelay = ObfuscationReflectionHelper.findField(Minecraft.class, "field_71467_ac");
            } catch (RuntimeException e) {
                rightClickDelay = null;
            }
        }
        if (rightClickDelay == null) return;
        try {
            if (rightClickDelay.getInt(mc) < 1) rightClickDelay.setInt(mc, 1);
        } catch (IllegalAccessException ignored) {
            // leave the repeat as it is
        }
    }

    private boolean holdsLittleTool(Minecraft mc) {
        if (!littleToolLooked) {
            littleToolLooked = true;
            if (Loader.isModLoaded("littletiles")) {
                try {
                    littleTool = Class.forName("com.creativemd.littletiles.common.api.ILittleTool", false, MeshPicking.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError e) {
                    littleTool = null;
                }
            }
        }
        return littleTool != null && littleTool.isInstance(mc.player.getHeldItemMainhand().getItem());
    }

    /** Points Minecraft's aim at the mesh surface when the mesh is the nearest thing in reach; true when it did. */
    private boolean aimAtMesh(Minecraft mc, float partialTicks, boolean insideBlock) {
        if (aimed != null && mc.objectMouseOver == aimed && aimedInside == insideBlock) return true;
        Target t = target(mc, partialTicks);
        if (t == null) {
            clearAim();
            return false;
        }
        // LittleTiles offsets its tiles from the hit point itself, so it gets the block holding the surface; other
        // items place into a block, so they get the empty block in front of it
        BlockPos pos = insideBlock ? new BlockPos(t.x - t.nx * 0.001, t.y - t.ny * 0.001, t.z - t.nz * 0.001) : t.pos;
        Vec3d hit = new Vec3d(t.x, t.y, t.z);
        RayTraceResult result = new RayTraceResult(hit, t.facing, pos);
        MeshAim.set(pos, hit);
        mc.objectMouseOver = result;
        aimed = result;
        aimedInside = insideBlock;
        return true;
    }

    private void clearAim() {
        if (aimed == null) return;
        aimed = null;
        MeshAim.clear();
    }

    /** The block cell in front of the mesh surface the player looks at, when the mesh is closer than any block or entity. */
    private static Target target(Minecraft mc, float partialTicks) {
        Entity view = mc.getRenderViewEntity();
        if (view == null || mc.playerController == null) return null;
        int dim = mc.world.provider.getDimension();
        if (MeshWorld.CLIENT.isEmpty(dim)) return null;
        double reach = mc.playerController.getBlockReachDistance();
        Vec3d eyes = view.getPositionEyes(partialTicks);
        Vec3d look = view.getLook(partialTicks);
        TriangleGrid.Hit hit = MeshWorld.CLIENT.raycast(dim, eyes.x, eyes.y, eyes.z, eyes.x + look.x * reach, eyes.y + look.y * reach, eyes.z + look.z * reach);
        if (hit == null) return null;
        double meshDistance = hit.t * reach;
        RayTraceResult over = mc.objectMouseOver;
        if (over != null && over.typeOfHit != RayTraceResult.Type.MISS && over.hitVec != null && over.hitVec.distanceTo(eyes) <= meshDistance) return null;
        Target t = new Target();
        t.preview = hit.preview;
        t.x = hit.x;
        t.y = hit.y;
        t.z = hit.z;
        t.nx = hit.nx;
        t.ny = hit.ny;
        t.nz = hit.nz;
        // On top of a mesh the cell is the one that starts at the surface, not the one holding it: a deck at 70.5
        // carries its rails and blocks at 71, not at 70 where they would end up half sunk into the model.
        t.pos = hit.ny > 0.5
            ? new BlockPos(MathHelper.floor(hit.x), Math.round(hit.y), MathHelper.floor(hit.z))
            : new BlockPos(hit.x + hit.nx * 0.02, hit.y + hit.ny * 0.02, hit.z + hit.nz * 0.02);
        t.facing = EnumFacing.getFacingFromVector((float) hit.nx, (float) hit.ny, (float) hit.nz);
        t.hx = (float) MathHelper.clamp(hit.x - t.pos.getX(), 0, 1);
        t.hy = (float) MathHelper.clamp(hit.y - t.pos.getY(), 0, 1);
        t.hz = (float) MathHelper.clamp(hit.z - t.pos.getZ(), 0, 1);
        return t;
    }

    @SubscribeEvent
    public void onRenderLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        float pt = event.getPartialTicks();
        double[] pb = ClientMeshes.INSTANCE.previewBounds();
        Target t = null;
        if (mc.currentScreen == null && !mc.player.isSpectator() && !holdsLittleTool(mc)
            && !(mc.player.getHeldItemMainhand().isEmpty() && mc.player.getHeldItemOffhand().isEmpty())) t = target(mc, pt);
        if (pb == null && t == null) return;
        double px = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double py = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double pz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(2f);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        if (t != null) RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(t.pos).grow(0.002).offset(-px, -py, -pz), 0f, 0f, 0f, 0.4f);
        if (pb != null) RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(pb[0], pb[1], pb[2], pb[3], pb[4], pb[5]).offset(-px, -py, -pz), 1f, 0.9f, 0.2f, 0.8f);
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }
}
