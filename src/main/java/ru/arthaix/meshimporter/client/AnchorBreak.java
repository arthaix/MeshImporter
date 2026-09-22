package ru.arthaix.meshimporter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import ru.arthaix.meshimporter.block.BlockMeshAnchor;
import ru.arthaix.meshimporter.network.MsgBreakAnchor;
import ru.arthaix.meshimporter.network.Net;

/**
 * An anchor carries whole models, so a stray click must not take it out: it goes only after the attack button has
 * been held on it for five seconds, with the countdown shown above the hotbar. Letting go starts over.
 *
 * The aim is looked for twice. While an item is in hand Minecraft's own aim is moved onto the mesh surface
 * (MeshPicking) and an anchor usually stands inside its own model, so that aim alone had the countdown starting over
 * every tick; the player's own ray still finds the block. A crosshair that slips off the small anchor for a moment
 * does not start it over either - the aim may wander for a little over half a second.
 */
public final class AnchorBreak {

    public static final AnchorBreak INSTANCE = new AnchorBreak();
    private static final int HOLD_TICKS = 100;

    /** How long the aim may be off the anchor before the countdown gives up. */
    private static final int GRACE_TICKS = 12;

    private BlockPos target;
    private int held;
    private int away;

    private AnchorBreak() {}

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean holding = mc.player != null && mc.world != null && mc.currentScreen == null
            && mc.gameSettings.keyBindAttack.isKeyDown();
        BlockPos aimed = holding ? anchorAimedAt(mc) : null;
        if (aimed != null) {
            if (!aimed.equals(target)) {
                target = aimed;
                held = 0;
            }
            away = 0;
        } else if (!holding || target == null || ++away > GRACE_TICKS) {
            stop(mc);
            return;
        }
        held++;
        if (held >= HOLD_TICKS) {
            Net.CH.sendToServer(new MsgBreakAnchor(target));
            mc.ingameGUI.setOverlayMessage(TextFormatting.RED + "Anchor removed", false);
            target = null;
            held = 0;
            away = 0;
            return;
        }
        double left = (HOLD_TICKS - held) / 20.0;
        int bars = 20 * held / HOLD_TICKS;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) bar.append(i < bars ? TextFormatting.RED + "|" : TextFormatting.DARK_GRAY + "|");
        mc.ingameGUI.setOverlayMessage(TextFormatting.YELLOW + "Keep holding to remove the anchor and its models  " + bar
            + TextFormatting.YELLOW + String.format("  %.1f s", left), false);
    }

    private void stop(Minecraft mc) {
        if (target != null && held > 0 && mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage("", false);
        target = null;
        held = 0;
        away = 0;
    }

    /** The anchor the player looks at, from Minecraft's aim or, when that one sits on a mesh, from their own ray. */
    private static BlockPos anchorAimedAt(Minecraft mc) {
        BlockPos pos = anchorOf(mc, mc.objectMouseOver);
        if (pos != null) return pos;
        double reach = mc.playerController == null ? 5.0 : mc.playerController.getBlockReachDistance();
        return anchorOf(mc, mc.player.rayTrace(reach, 1f));
    }

    private static BlockPos anchorOf(Minecraft mc, RayTraceResult hit) {
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK || hit.getBlockPos() == null) return null;
        return mc.world.getBlockState(hit.getBlockPos()).getBlock() instanceof BlockMeshAnchor ? hit.getBlockPos() : null;
    }
}
