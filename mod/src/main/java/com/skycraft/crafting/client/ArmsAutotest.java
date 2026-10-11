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
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
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

    private static String stand(double dx, double dz, float yaw, SkyArmorMaterial set, Item main) {
        StringBuilder sb = new StringBuilder("summon armor_stand ~").append(dx).append(" ~ ~").append(dz)
                .append(" {ShowArms:1b,NoBasePlate:1b,NoGravity:1b,Invulnerable:1b,Rotation:[").append(yaw).append("f,0f],ArmorItems:[");
        ArmorItem.Type[] order = {ArmorItem.Type.BOOTS, ArmorItem.Type.LEGGINGS, ArmorItem.Type.CHESTPLATE, ArmorItem.Type.HELMET};
        for (int i = 0; i < order.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{id:\"").append(id(CraftingItems.armor(set, order[i]))).append("\",Count:1b}");
        }
        sb.append("],HandItems:[{id:\"").append(id(main)).append("\",Count:1b},");
        Item shield = CraftingItems.shield(set);
        sb.append(shield != null ? "{id:\"" + id(shield) + "\",Count:1b}" : "{}").append("],Pose:{RightArm:[-20f,0f,10f],LeftArm:[-30f,0f,-10f]}}");
        return sb.toString();
    }

    private static void build() {
        built = true;
        step(80, mc -> {
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.setScreen(null);
            mc.options.hideGui = true;
        });
        cmd(2, "gamerule doDaylightCycle false");
        cmd(2, "gamerule doMobSpawning false");
        cmd(2, "gamerule doWeatherCycle false");
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
                        + "HandItems:[{id:\"" + id(CraftingItems.weaponItem(tier, main)) + "\",Count:1b},{id:\""
                        + id(CraftingItems.weaponItem(tier, off)) + "\",Count:1b}]}";
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
        try {
            Files.createDirectories(OUT);
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            File out = OUT.resolve(name + ".png").toFile();
            img.writeToFile(out);
            img.close();
            Skycraft.LOGGER.info("Arms autotest: saved {}", out.getAbsolutePath());
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Arms autotest: screenshot {} failed", name, e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
            if (!worldRequested && (mc.screen instanceof TitleScreen || mc.screen instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
                worldRequested = true;
                String name = "armstest-" + System.currentTimeMillis();
                GameRules rules = new GameRules();
                LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
                        WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261010L, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
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
