package com.skycraft.crafting.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.crafting.CraftingItems;
import com.skycraft.crafting.SkyArmorMaterial;
import com.skycraft.crafting.SmithingTier;
import com.skycraft.crafting.WeaponType;
import com.skycraft.network.CorePackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer harness for the arms & armour workstream: when {@code skycraft-armstest.txt} exists in the game directory
 * (or {@code SKYCRAFT_ARMSTEST=1}), it creates a superflat creative world, lines up armour stands wearing every set
 * (with a weapon and the set's shield), photographs them from the front, the side and the back, then lays out every
 * weapon on stands and quits. Screenshots go to {@code build/armstest-screenshots/}. Does nothing in normal play.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ArmsAutotest {
    private static final boolean ENABLED = Files.exists(Paths.get("skycraft-armstest.txt")) || "1".equals(System.getenv("SKYCRAFT_ARMSTEST"));
    private static final Path OUT = Paths.get("build", "armstest-screenshots");
    private static final int PER_ROW = 6;

    private record Step(int delay, Consumer<Minecraft> action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean worldRequested;
    private static int inWorld;
    private static int stepIndex;
    private static int waitLeft;
    private static boolean built;

    private ArmsAutotest() {}

    private static void step(int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(wait, action));
    }

    private static void cmd(int wait, String command) {
        step(wait, mc -> {
            if (mc.player != null) mc.player.connection.sendCommand(command);
        });
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String stack(Item item) {
        return "{id:\"" + id(item) + "\",count:1}";
    }

    private static String stand(double dx, double dz, float yaw, SkyArmorMaterial set, Item main) {
        StringBuilder sb = new StringBuilder("summon armor_stand ~").append(dx).append(" ~ ~").append(dz)
                .append(" {ShowArms:1b,NoBasePlate:1b,NoGravity:1b,Invulnerable:1b,Rotation:[").append(yaw).append("f,0f],equipment:{");
        ArmorType[] order = {ArmorType.BOOTS, ArmorType.LEGGINGS, ArmorType.CHESTPLATE, ArmorType.HELMET};
        String[] slots = {"feet", "legs", "chest", "head"};
        for (int i = 0; i < order.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(slots[i]).append(':').append(stack(CraftingItems.armor(set, order[i])));
        }
        sb.append(",mainhand:").append(stack(main));
        Item shield = CraftingItems.shield(set);
        if (shield != null) sb.append(",offhand:").append(stack(shield));
        sb.append("},Pose:{RightArm:[-20f,0f,10f],LeftArm:[-30f,0f,-10f]}}");
        return sb.toString();
    }

    private static void build() {
        built = true;
        step(80, mc -> {
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.gui.setScreen(null);
            com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc,true);
        });
        cmd(2, "gamerule advance_time false");
        cmd(2, "gamerule spawn_mobs false");
        cmd(2, "gamerule advance_weather false");
        cmd(2, "weather clear");
        cmd(2, "time set 6000");
        cmd(2, "gamemode creative");
        cmd(2, "tp @s 0.5 -59.4 0.5 0 10");
        SkyArmorMaterial[] sets = SkyArmorMaterial.values();
        SmithingTier[] tiers = SmithingTier.values();
        for (int start = 0, batch = 0; start < sets.length; start += PER_ROW, batch++) {
            final int b = batch;
            cmd(10, "kill @e[type=armor_stand]");
            for (int k = 0; k < PER_ROW && start + k < sets.length; k++) {
                SkyArmorMaterial set = sets[start + k];
                SmithingTier tier = tiers[(start + k) % tiers.length];
                WeaponType type = tier.has(WeaponType.SWORD) ? WeaponType.SWORD : WeaponType.DAGGER;
                Item weapon = CraftingItems.weaponItem(tier, type);
                cmd(2, stand(-3.75 + k * 1.5, 6.0, 180f, set, weapon));
            }
            step(40, mc -> shot(mc, String.format("armor_%02d_front", b)));
            cmd(2, "execute as @e[type=armor_stand] at @s run tp @s ~ ~ ~ 130 0");
            step(20, mc -> shot(mc, String.format("armor_%02d_side", b)));
            cmd(2, "execute as @e[type=armor_stand] at @s run tp @s ~ ~ ~ 0 0");
            step(20, mc -> shot(mc, String.format("armor_%02d_back", b)));
        }
        // weapons: one stand per material holding its sword and greatsword
        for (int start = 0, batch = 0; start < tiers.length; start += PER_ROW, batch++) {
            final int b = batch;
            cmd(10, "kill @e[type=armor_stand]");
            for (int k = 0; k < PER_ROW && start + k < tiers.length; k++) {
                SmithingTier tier = tiers[start + k];
                WeaponType main = tier.has(WeaponType.GREATSWORD) ? WeaponType.GREATSWORD : WeaponType.SWORD;
                WeaponType off = tier.has(WeaponType.WAR_AXE) ? WeaponType.WAR_AXE : WeaponType.DAGGER;
                String cmd = "summon armor_stand ~" + (-3.75 + k * 1.5) + " ~-0.6 ~4.5 {ShowArms:1b,NoBasePlate:1b,NoGravity:1b,Rotation:[150f,0f],"
                        + "equipment:{mainhand:" + stack(CraftingItems.weaponItem(tier, main)) + ",offhand:"
                        + stack(CraftingItems.weaponItem(tier, off)) + "}}";
                cmd(2, cmd);
            }
            step(40, mc -> shot(mc, String.format("weapons_%02d", b)));
        }
        step(20, mc -> {
            Skycraft.LOGGER.info("Arms autotest finished; screenshots in {}", OUT.toAbsolutePath());
            mc.stop();
        });
    }

    private static void shot(Minecraft mc, String name) {
        com.skycraft.client.autotest.ReviewCaptures.capture(mc, OUT, name,
                output -> Skycraft.LOGGER.info("Arms autotest: saved {}", output.toAbsolutePath()));
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!ENABLED) return;
        if (com.skycraft.client.autotest.ReviewCaptures.pending() > 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.gui.setScreen(new TitleScreen());
            if (!worldRequested && (mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
                worldRequested = true;
                String name = "armstest-" + System.currentTimeMillis();
                LevelSettings settings = com.skycraft.client.autotest.ReviewWorlds.settings(name,  GameType.CREATIVE,  false,  Difficulty.PEACEFUL,  true, 
                        WorldDataConfiguration.DEFAULT);
                com.skycraft.client.autotest.ReviewWorlds.create(mc, name,  settings,  new WorldOptions(20261010L, false, false), 
                        access -> access.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.gui.screen());
            }
            return;
        }
        if (!built) build();
        if (mc.player == null || stepIndex >= STEPS.size()) return;
        if (inWorld++ == 0) waitLeft = STEPS.get(0).delay();
        if (--waitLeft > 0) return;
        Step s = STEPS.get(stepIndex++);
        try {
            s.action().accept(mc);
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Arms autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
