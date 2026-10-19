package com.nirncraft.crime.client;

import com.nirncraft.client.ClientState;
import com.nirncraft.core.Holds;
import com.nirncraft.crime.Bounty;
import com.nirncraft.crime.CrimePackets;
import com.nirncraft.crime.Crimes;
import com.nirncraft.crime.Jail;
import com.nirncraft.crime.Locks;
import com.nirncraft.crime.Ownership;
import com.nirncraft.network.CorePackets;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Crime HUD: the bounty in the current hold (small red text under the compass), the jail countdown, the
 * "resisting arrest" status, and subtle crosshair hints ("Steal", "Locked (Adept)", "Pickpocket").
 */
public final class CrimeHud {
    private static final int RED = 0xE04040;

    private static long sneakAt = -100;
    private static SneakState sneakCached = SneakState.HIDDEN;
    private static net.minecraft.core.BlockPos estPos;
    private static long estAt;
    private static String estName;

    private CrimeHud() {}

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) return;
        Font font = mc.font;

        // ---- status line under the compass
        Component status = null;
        int color = RED;
        if (Jail.isJailed(mc.player)) {
            status = Jail.remaining(mc.player) <= 0 ? Component.translatable("hud.nirncraft.crime.served")
                    : Component.translatable("hud.nirncraft.crime.sentence", Jail.formatTime(Jail.remaining(mc.player)));
            color = 0xE0C080;
        } else {
            String hold = Holds.holdAt(mc.level, mc.player.blockPosition());
            int bounty = Bounty.get(mc.player, hold);
            if (bounty > 0) {
                MutableComponent line = Component.translatable("hud.nirncraft.crime.bounty", bounty, Holds.displayName(hold));
                if (bounty >= Bounty.KILL_ON_SIGHT || Bounty.isHostile(mc.player)) {
                    line.append(Component.translatable(bounty >= Bounty.KILL_ON_SIGHT && !Bounty.isHostile(mc.player)
                            ? "hud.nirncraft.crime.wanted" : "hud.nirncraft.crime.resisting"));
                }
                status = line;
            }
        }
        // the enemy health bar owns the strip right under the compass: keep the bounty line clear of it
        int statusY = com.nirncraft.client.hud.TargetHud.isVisible() ? 62 : 32;
        if (status != null) drawSmall(g, font, status, width / 2, statusY, 0.8f, 0xE0 << 24 | color);

        // ---- Skyrim Sneak Detection Eye
        if (mc.player.isCrouching()) {
            renderSneakEye(g, font, mc.player, width, height);
        }

        // ---- crosshair hints (Skyrim interactive prompts; ordinary blocks show nothing)
        if (mc.gui.screen() != null) return;
        int cx = width / 2;
        int cy = height / 2 + 10;

        // Item entity under crosshair
        net.minecraft.world.entity.item.ItemEntity item = getLookedAtItem(mc, 3.5);
        if (item != null) {
            boolean owned = Ownership.isOwnedByOther(mc.player, item);
            Component prompt = Component.literal((owned ? "[F] Steal  " : "[F] Take  ") + item.getItem().getHoverName().getString());
            drawSmall(g, font, prompt, cx, cy, 0.75f, owned ? (0xD0000000 | RED) : 0xD0E8E2D0);
            return;
        }

        // Entities under crosshair
            if (mc.crosshairPickEntity instanceof com.nirncraft.fauna.entity.InsectEntity insect && insect.isAlive()) {
                drawSmall(g, font, Component.literal("[F] Catch  " + insect.getName().getString()), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }
            if (mc.crosshairPickEntity instanceof com.nirncraft.creatures.entity.CorpseEntity corpse) {
                Component prompt = Component.literal("[F] Search  " + corpse.getDisplayName().getString());
                drawSmall(g, font, prompt, cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }
            if (mc.crosshairPickEntity instanceof net.minecraft.world.entity.decoration.ArmorStand fallen && fallen.isAlive()
                    && fallen.hasCustomName() && hasGear(fallen)) {
                drawSmall(g, font, Component.literal("[F] Loot  " + fallen.getCustomName().getString() + "'s Remains"), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }
            if (mc.crosshairPickEntity instanceof net.minecraft.world.entity.LivingEntity talker && talker.isAlive()
                    && (talker instanceof AbstractVillager || talker instanceof com.nirncraft.society.entity.NpcEntity)) {
                if (mc.player.isShiftKeyDown()) {
                    drawSmall(g, font, Component.literal("[F] Pickpocket  " + talker.getDisplayName().getString()), cx, cy, 0.75f, 0xC0C8C0B0);
                } else {
                    drawSmall(g, font, Component.literal("[F] Talk  " + talker.getDisplayName().getString()), cx, cy, 0.75f, 0xD0E8E2D0);
                }
                return;
            }

        // Blocks under crosshair
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            var bpos = bhr.getBlockPos();
            var bstate = mc.level.getBlockState(bpos);
            var block = bstate.getBlock();

            if (com.nirncraft.survival.Harvesting.isHarvestable(bstate)) {
                if (com.nirncraft.survival.Harvesting.isReadyToHarvest(bstate)) {
                    drawSmall(g, font, Component.literal("[F] Harvest  " + block.getName().getString()), cx, cy, 0.75f, 0xD0E8E2D0);
                } else {
                    drawSmall(g, font, Component.literal("Growing  " + block.getName().getString()), cx, cy, 0.75f, 0x80AAAAAA);
                }
                return;
            }

            // a trap the player can disarm (crouch and interact): tripwires, their hooks, dungeon pressure plates
            boolean wire = block instanceof net.minecraft.world.level.block.TripWireBlock
                    || (block instanceof net.minecraft.world.level.block.TripWireHookBlock
                    && bstate.getValue(net.minecraft.world.level.block.TripWireHookBlock.ATTACHED));
            boolean plate = block instanceof net.minecraft.world.level.block.BasePressurePlateBlock && !mc.level.canSeeSky(bpos.above());
            if (wire || plate) {
                String key = mc.player.isShiftKeyDown() ? "[F] Disarm  " : "[Crouch + F] Disarm  ";
                drawSmall(g, font, Component.literal(key + (wire ? "Tripwire" : "Pressure Plate")), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }

            if (block instanceof net.minecraft.world.level.block.BedBlock) {
                if (Jail.isJailed(mc.player)) {
                    int served = Bounty.state(mc.player).getCompoundOrEmpty("jail").getIntOr("served",0);
                    int left = Math.max(0, 60 - served);
                    if (left > 0) {
                        drawSmall(g, font, Component.literal("[F] Rest  Serve Sentence (" + left + "s wait)"), cx, cy, 0.75f, 0xD0000000 | RED);
                    } else {
                        drawSmall(g, font, Component.literal("[F] Sleep  Serve Sentence"), cx, cy, 0.75f, 0xD0E8E2D0);
                    }
                    return;
                }
                boolean owned = Ownership.isOwnedByOther(mc.player, mc.level, bpos);
                boolean occ = bstate.hasProperty(net.minecraft.world.level.block.BedBlock.OCCUPIED) && bstate.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED);
                String t = occ ? "Bed (occupied)" : owned ? "[F] Sleep  Bed (owned)" : "[F] Sleep  Bed";
                drawSmall(g, font, Component.literal(t), cx, cy, 0.75f, (owned || occ) ? (0xD0000000 | RED) : 0xD0E8E2D0);
                return;
            }

            if (Jail.isJailed(mc.player) && block instanceof net.minecraft.world.level.block.TrapDoorBlock) {
                drawSmall(g, font, Component.literal("[F] Escape  Old Sewer Grate"), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }

            CrimePackets.ContainerInfo info = CrimeClientHandlers.containerInfo;
            boolean hasLock = info != null && (info.pos().equals(bpos) || info.pos().equals(bpos.below()) || info.pos().equals(bpos.above())) && info.lock() != Locks.NOT_LOCKED && !info.unlocked();
            if (hasLock) {
                Component lock = Component.translatable("hud.nirncraft.crime.locked",
                        Component.translatable("crime.nirncraft.lock." + Locks.TIERS[Math.max(0, Math.min(4, info.lock()))]));
                drawSmall(g, font, lock, cx, cy, 0.75f, 0xD0000000 | RED);
                cy += 8;
            }

            boolean isContainer = block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BarrelBlock
                    || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                    || block instanceof com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlock;

            if (isContainer) {
                boolean owned = (info != null && info.pos().equals(bpos) && info.owned()) || Ownership.isOwnedByOther(mc.player, mc.level, bpos);
                String name = block.getName().getString();
                if (mc.level.getBlockEntity(bpos) instanceof com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlockEntity fbe) {
                    name = fbe.kind().displayName;
                }
                String action = owned ? "[F] Steal  " : "[F] Open  ";
                drawSmall(g, font, Component.literal(action + name), cx, cy, 0.75f, owned ? (0xD0000000 | RED) : 0xD0E8E2D0);
                return;
            }

            if (mc.level.getBlockEntity(bpos) instanceof com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlockEntity fbe) {
                var kind = fbe.kind();
                if (kind.isSeat()) {
                    drawSmall(g, font, Component.literal("[F] Sit  " + kind.displayName), cx, cy, 0.75f, 0xD0E8E2D0);
                    return;
                }
                if (kind.isTakeableClutter() && !fbe.isEmpty()) {
                    boolean owned = Ownership.isOwnedByOther(mc.player, mc.level, bpos);
                    String action = owned ? "[F] Steal  " : "[F] Take  ";
                    drawSmall(g, font, Component.literal(action + kind.displayName), cx, cy, 0.75f, owned ? (0xD0000000 | RED) : 0xD0E8E2D0);
                    return;
                }
                if (kind == com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.DESK && !fbe.isEmpty()) {
                    drawSmall(g, font, Component.literal("[F] Search  Writing Desk"), cx, cy, 0.75f, 0xD0E8E2D0);
                    return;
                }
            }

            if (bstate.is(net.minecraft.world.level.block.Blocks.CHISELED_BOOKSHELF)) {
                int occ = com.nirncraft.lore.LoreEvents.findOccupiedSlot(bstate);
                if (occ >= 0) {
                    boolean owned = Ownership.isOwnedByOther(mc.player, mc.level, bpos);
                    String action = owned ? "[F] Steal Book  " : "[F] Take Book  ";
                    drawSmall(g, font, Component.literal(action + "Bookshelf"), cx, cy, 0.75f, owned ? (0xD0000000 | RED) : 0xD0E8E2D0);
                } else {
                    drawSmall(g, font, Component.literal("Bookshelf (Empty)"), cx, cy, 0.75f, 0x80AAAAAA);
                }
                return;
            }

            if (bstate.is(net.minecraft.world.level.block.Blocks.BOOKSHELF)) {
                boolean owned = Ownership.isOwnedByOther(mc.player, mc.level, bpos);
                String action = owned ? "[F] Steal Book  " : "[F] Take Book  ";
                drawSmall(g, font, Component.literal(action + "Bookshelf"), cx, cy, 0.75f, owned ? (0xD0000000 | RED) : 0xD0E8E2D0);
                return;
            }

            if (block instanceof net.minecraft.world.level.block.DoorBlock
                    || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                    || block instanceof net.minecraft.world.level.block.FenceGateBlock) {
                boolean owned = Ownership.isOwnedByOther(mc.player, mc.level, bpos);
                long dayTime = mc.level.getOverworldClockTime() % 24000L;
                boolean night = dayTime >= 13000L && dayTime <= 23000L;
                boolean matchesInfo = info != null && (info.pos().equals(bpos) || info.pos().equals(bpos.below()) || info.pos().equals(bpos.above()));
                boolean locked = (matchesInfo && info.lock() != Locks.NOT_LOCKED && !info.unlocked())
                        || (owned && night && (block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.TrapDoorBlock));
                String serverLabel = (matchesInfo && info.label() != null && !info.label().getString().isBlank())
                        ? info.label().getString()
                        : null;
                String est = serverLabel != null ? serverLabel : getEstablishmentName(mc.level, bpos);
                String label = est != null ? est : "Door";
                String t;
                if (locked) {
                    t = "[F] Pick Lock  " + label + (owned ? " (Breaking & Entering)" : "");
                } else if (owned) {
                    t = "[F] Open  " + label + " (owned)";
                } else {
                    t = "[F] Open  " + label;
                }
                drawSmall(g, font, Component.literal(t), cx, cy, 0.75f, (owned || locked) ? (0xD0000000 | RED) : 0xD0E8E2D0);
                return;
            }

            if (mc.level.getBlockEntity(bpos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity signBe) {
                Component signHud = com.nirncraft.tamriel.settlement.client.ShopSignRenderer.describeSignForHud(signBe);
                if (signHud != null) {
                    drawSmall(g, font, signHud, cx, cy, 0.75f, 0xD0E8E2D0);
                    return;
                }
            }

            if (block.getDescriptionId().contains("shrine")) {
                drawSmall(g, font, Component.literal("[F] Pray  " + block.getName().getString()), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }

            if (block.getDescriptionId().contains("standing_stone")) {
                drawSmall(g, font, Component.literal("[F] Commune  " + block.getName().getString()), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }

            if (block.getDescriptionId().contains("word_wall")) {
                drawSmall(g, font, Component.literal("[F] Read  Word Wall"), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }

            if (block instanceof net.minecraft.world.level.block.CampfireBlock) {
                drawSmall(g, font, Component.literal("Rest  Campfire"), cx, cy, 0.75f, 0xD0E8E2D0);
                return;
            }


            if (block.getDescriptionId().contains("crafting") || block.getDescriptionId().contains("station")
                    || block.getDescriptionId().contains("furnace") || block.getDescriptionId().contains("anvil")
                    || block.getDescriptionId().contains("grindstone") || block.getDescriptionId().contains("cooking_pot")) {
                String action = block.getDescriptionId().contains("cooking") ? "Cook  " : "Use  ";
                drawSmall(g, font, Component.literal(action + block.getName().getString()), cx, cy, 0.75f, 0xD0E8E2D0);
            }
        }
    }

    private enum SneakState {
        HIDDEN, CAUTION, DETECTED
    }

    /** The sneak eye: "detected" is the server's verdict (synced vitals flag); "caution" is a client estimate, cached. */
    private static SneakState getSneakState(net.minecraft.world.entity.player.Player player) {
        if (ClientState.has(CorePackets.SyncVitals.DETECTED)) return SneakState.DETECTED;
        long now = player.level().getGameTime();
        if (now - sneakAt >= 5 || now < sneakAt) {
            sneakAt = now;
            sneakCached = estimateSneakState(player);
        }
        return sneakCached;
    }

    private static SneakState estimateSneakState(net.minecraft.world.entity.player.Player player) {
        var level = player.level();
        var box = player.getBoundingBox().inflate(24);
        var list = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                e -> e != player && e.isAlive() && !(e instanceof net.minecraft.world.entity.player.Player));
        if (list.isEmpty()) return SneakState.HIDDEN;

        int sneakSkill = com.nirncraft.core.SkyData.get(player).getSkill(com.nirncraft.core.Skill.SNEAK);
        int light = level.getMaxLocalRawBrightness(player.blockPosition());
        boolean dark = light < 7;
        boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 0.0004;

        // never claims DETECTED on its own (mob targets aren't synced): close calls are CAUTION until the server says so
        boolean anyCaution = false;
        for (net.minecraft.world.entity.LivingEntity e : list) {
            boolean civilian = Crimes.isCivilian(e);
            boolean hostile = e instanceof net.minecraft.world.entity.monster.Enemy;
            if (!civilian && !hostile) continue;
            if (e.isSleeping()) continue;

            double dist = e.distanceTo(player);
            double baseVision = 20.0;
            double vision = baseVision * (1.0 - (sneakSkill * 0.005));
            if (dark) vision *= 0.6;
            if (moving) vision *= 1.3;

            if (dist >= vision * 1.4) continue; // too far to matter: skip the raycast
            if (e.hasLineOfSight(player)) {
                boolean facing = Crimes.facing(e, player);
                if (facing ? dist < vision * 1.4 : dist < Math.max(3.0, vision * 0.7)) {
                    return SneakState.CAUTION;
                }
            } else {
                if (moving && dist < 6.0 && sneakSkill < 50) {
                    anyCaution = true;
                }
            }
        }
        return anyCaution ? SneakState.CAUTION : SneakState.HIDDEN;
    }

    private static void renderSneakEye(GuiGraphicsExtractor g, Font font, net.minecraft.world.entity.player.Player player, int width, int height) {
        SneakState state = getSneakState(player);
        // The Skyrim eye itself is drawn at the crosshair by client.hud.SneakEye; this module supplies the state.
        if (com.nirncraft.client.hud.SneakEye.report(state.ordinal())) return;
        int cx = width / 2;
        int cy = height / 2 - 20;

        Component label;
        String eyeBrackets;
        int color;
        switch (state) {
            case HIDDEN -> {
                label = Component.literal("HIDDEN");
                eyeBrackets = "— —  •  — —";
                color = 0xD0E0E0E0;
            }
            case CAUTION -> {
                label = Component.literal("CAUTION");
                eyeBrackets = "< ( • ) >";
                color = 0xE0E0C050;
            }
            case DETECTED -> {
                label = Component.literal("DETECTED");
                eyeBrackets = "(   ●   )";
                color = 0xE0E04040;
            }
            default -> {
                return;
            }
        }

        drawSmall(g, font, Component.literal(eyeBrackets), cx, cy - 7, 1.0f, color);
        drawSmall(g, font, label, cx, cy + 4, 0.75f, color);
    }

    /** True when an armour stand (a scene's fallen body) still wears or holds anything. */
    private static boolean hasGear(net.minecraft.world.entity.decoration.ArmorStand stand) {
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (!stand.getItemBySlot(slot).isEmpty()) return true;
        }
        return false;
    }

    private static void drawSmall(GuiGraphicsExtractor g, Font font, Component text, int x, int y, float scale, int argb) {
        g.pose().pushMatrix();
        g.pose().translate(x,  y);
        g.pose().scale(scale,  scale);
        g.centeredText(font, text, 0, 0, argb);
        g.pose().popMatrix();
    }

    /** Cached per looked-at block for 10 client ticks (two entity scans + a block scan are too much per frame). */
    private static String getEstablishmentName(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        long now = level.getGameTime();
        if (!pos.equals(estPos) || now - estAt >= 10 || now < estAt) {
            estPos = pos.immutable();
            estAt = now;
            estName = findEstablishmentName(level, pos);
        }
        return estName;
    }

    private static String findEstablishmentName(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos rawPos) {
        net.minecraft.core.BlockPos pos = com.nirncraft.crime.Locks.normalizePos(level, rawPos);
        for (com.nirncraft.economy.Houses.HouseDef h : com.nirncraft.economy.Houses.HOUSES) {
            if (!h.homestead() && h.contains(pos)) {
                return h.displayName().getString();
            }
        }
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        String bestSignTitle = null;
        double bestSignDist = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getBlockEntity(m) instanceof net.minecraft.world.level.block.entity.SignBlockEntity signBe) {
                        var c = com.nirncraft.tamriel.settlement.client.ShopSignRenderer.classify(signBe);
                        if (c.title() != null && !c.title().isBlank()) {
                            double d = dx * dx + dz * dz + dy * 0.5;
                            if (d < bestSignDist) {
                                bestSignDist = d;
                                bestSignTitle = c.title();
                            }
                        }
                    }
                }
            }
        }
        return bestSignTitle;
    }

    public static net.minecraft.world.entity.item.ItemEntity getLookedAtItem(Minecraft mc, double maxDist) {
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.world.phys.Vec3 eyePos = mc.player.getEyePosition(1.0f);
        net.minecraft.world.phys.Vec3 viewVec = mc.player.getViewVector(1.0f);
        net.minecraft.world.phys.Vec3 endPos = eyePos.add(viewVec.scale(maxDist));
        net.minecraft.world.phys.AABB box = mc.player.getBoundingBox().expandTowards(viewVec.scale(maxDist)).inflate(1.0);
        net.minecraft.world.entity.item.ItemEntity closest = null;
        double closestDist = maxDist * maxDist;
        for (net.minecraft.world.entity.item.ItemEntity item : mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box, net.minecraft.world.entity.item.ItemEntity::isAlive)) {
            net.minecraft.world.phys.AABB hitBox = item.getBoundingBox().inflate(0.35);
            java.util.Optional<net.minecraft.world.phys.Vec3> hit = hitBox.clip(eyePos, endPos);
            if (hit.isPresent()) {
                double d = eyePos.distanceToSqr(hit.get());
                if (d < closestDist) {
                    closest = item;
                    closestDist = d;
                }
            }
        }
        return closest;
    }
}
