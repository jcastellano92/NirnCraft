package com.skycraft.curse.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.curse.CurseAnims;
import com.skycraft.curse.CursePerks;
import com.skycraft.curse.Form;
import com.skycraft.network.CorePackets;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer harness for the curse module: when {@code skycraft-cursetest.txt} exists in the game directory (or
 * {@code SKYCRAFT_CURSETEST=1}), it creates a fresh world, shows the werewolf, Vampire Lord, death hound and gargoyle,
 * transforms the player into each form (front, back, howling, levitating, first-person claws), opens the form and
 * perk screens, saves screenshots to {@code build/cursetest-screenshots/} and quits. Does nothing in normal play.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class CurseAutotest {
    private static final boolean ENABLED = Files.exists(Paths.get("skycraft-cursetest.txt")) || "1".equals(System.getenv("SKYCRAFT_CURSETEST"));
    private static final Path OUT = Paths.get("build", "cursetest-screenshots");

    private record Step(int delay, Consumer<Minecraft> action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean worldRequested;
    private static int inWorld;
    private static int stepIndex;
    private static int waitLeft;

    static {
        step(100, mc -> {
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.gui.setScreen(null);
        });
        cmd(2, "gamerule advance_time false");
        cmd(2, "gamerule spawn_mobs false");
        cmd(2, "time set 6000");
        cmd(2, "effect give @s minecraft:resistance 99999 255 true");
        cmd(2, "skycraft-weather set clear 60");
        // a stone stage high in the open sky: nothing in the way, even daylight
        cmd(2, "tp @s ~ 190 ~");
        cmd(4, "fill ~-10 ~-1 ~-10 ~10 ~-1 ~10 minecraft:polished_andesite");
        cmd(2, "kill @e[type=!player]");
        step(60, mc -> mc.gui.hud.getChat().clearMessages(false));
        // the creatures, one at a time in front of the camera
        creature("werewolf", "01_werewolf", 0);
        creature("werewolf", "02_werewolf_howl", 1);
        creature("vampire_lord", "03_vampire_lord", 0);
        creature("vampire_lord", "04_vampire_lord_cast", 2);
        creature("death_hound", "05_death_hound", 0);
        creature("gargoyle", "06_gargoyle", 0);
        cmd(2, "kill @e[type=!player]");
        // Beast Form
        cmd(20, "skycraft-curse beast");
        step(60, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        step(30, mc -> look(mc, 0f, 10f));
        step(20, mc -> shot(mc, "10_beast_front"));
        step(2, mc -> anim(mc, CurseAnims.Anim.HOWL));
        step(16, mc -> shot(mc, "11_beast_howl"));
        step(40, mc -> anim(mc, CurseAnims.Anim.ATTACK));
        step(5, mc -> shot(mc, "12_beast_swipe"));
        step(30, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        step(20, mc -> shot(mc, "13_beast_back"));
        step(10, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        step(20, mc -> shot(mc, "14_beast_first_person"));
        cmd(2, "skycraft-curse feed werewolf 9");
        step(10, mc -> mc.gui.setScreen(new CursePerkScreen(CursePerks.Tree.WEREWOLF, null)));
        step(20, mc -> shot(mc, "15_werewolf_perks"));
        step(2, mc -> mc.gui.setScreen(new CurseFormScreen(Form.BEAST)));
        step(20, mc -> shot(mc, "16_beast_menu"));
        step(2, mc -> mc.gui.setScreen(null));
        // the Vampire Lord
        cmd(10, "skycraft-curse lord");
        step(60, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        step(30, mc -> look(mc, 0f, 10f));
        step(20, mc -> shot(mc, "20_lord_front"));
        step(2, mc -> anim(mc, CurseAnims.Anim.HOWL));
        step(18, mc -> shot(mc, "21_lord_roar"));
        step(40, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        step(20, mc -> shot(mc, "22_lord_back"));
        step(2, mc -> {
            if (mc.player != null) {
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
                mc.player.setDeltaMovement(0, 0.4, 0);
            }
        });
        step(10, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        step(20, mc -> shot(mc, "23_lord_levitating"));
        step(2, mc -> anim(mc, CurseAnims.Anim.CAST));
        step(6, mc -> shot(mc, "24_lord_cast"));
        step(20, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        step(20, mc -> shot(mc, "25_lord_first_person"));
        cmd(2, "skycraft-curse feed vampire_lord 5");
        step(10, mc -> mc.gui.setScreen(new CursePerkScreen(CursePerks.Tree.VAMPIRE_LORD, null)));
        step(20, mc -> shot(mc, "26_lord_perks"));
        step(2, mc -> mc.gui.setScreen(new CurseFormScreen(Form.LORD)));
        step(20, mc -> shot(mc, "27_lord_menu"));
        step(2, mc -> mc.gui.setScreen(null));
        // a vampire in the sun (HUD), then cured
        cmd(10, "skycraft-curse revert");
        cmd(20, "skycraft-curse stage 4");
        step(60, mc -> look(mc, 0f, -10f));
        step(20, mc -> shot(mc, "30_vampire_stage4_sun_hud"));
        cmd(10, "skycraft-curse cure all");
        step(40, mc -> shot(mc, "31_cured"));
        step(20, mc -> {
            Skycraft.LOGGER.info("Curse autotest finished; screenshots in {}", OUT.toAbsolutePath());
            mc.stop();
        });
    }

    private CurseAutotest() {}

    private static void step(int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(wait, action));
    }

    private static void cmd(int wait, String command) {
        step(wait, mc -> {
            if (mc.player != null) mc.player.connection.sendCommand(command);
        });
    }

    /** Summons a still creature five blocks ahead, looks at it, optionally plays an animation, and shoots. */
    private static void creature(String id, String shot, int animation) {
        cmd(2, "kill @e[type=!player]");
        step(4, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        step(2, mc -> look(mc, 0f, 0f));
        String extra = id.equals("gargoyle") ? ",Dormant:0b" : "";
        double dist = id.equals("death_hound") ? 3 : 4.5;
        cmd(4, "summon skycraft:" + id + " ~0.5 ~ ~" + dist + " {NoAI:1b,PersistenceRequired:1b,Rotation:[160f,0f]" + extra + "}");
        step(2, mc -> {
            com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc,true);
            mc.gui.hud.getChat().clearMessages(false);
        });
        step(30, mc -> {
            Entity e = find(mc, id);
            if (e == null || mc.player == null) return;
            double dx = e.getX() - mc.player.getX(), dz = e.getZ() - mc.player.getZ();
            double dy = e.getY() + e.getBbHeight() * 0.55 - mc.player.getEyeY();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            look(mc, yaw, pitch);
            if (animation > 0 && e instanceof CurseAnims.Animated a) {
                a.anims().start(animation == 1 ? CurseAnims.Anim.HOWL : CurseAnims.Anim.CAST, e.tickCount);
            }
        });
        step(animation > 0 ? 14 : 10, mc -> shot(mc, shot));
        step(2, mc -> com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc,false));
    }

    private static Entity find(Minecraft mc, String id) {
        if (mc.level == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, id));
        for (Entity e : mc.level.entitiesForRendering()) if (e.getType() == type) return e;
        return null;
    }

    private static void anim(Minecraft mc, CurseAnims.Anim anim) {
        if (mc.player != null) CurseClient.onAnim(mc.player.getId(), anim.ordinal());
    }

    private static void look(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return;
        mc.gui.hud.getChat().clearMessages(false);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYBodyRot(yaw);
        mc.player.setYHeadRot(yaw);
    }

    private static void shot(Minecraft mc, String name) {
        com.skycraft.client.autotest.ReviewCaptures.capture(mc, OUT, name,
                output -> Skycraft.LOGGER.info("Curse autotest: saved {}", output.toAbsolutePath()));
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
                String name = "cursetest-" + System.currentTimeMillis();
                LevelSettings settings = com.skycraft.client.autotest.ReviewWorlds.settings(name,  GameType.SURVIVAL,  false,  Difficulty.EASY,  true,  WorldDataConfiguration.DEFAULT);
                com.skycraft.client.autotest.ReviewWorlds.create(mc, name,  settings,  new WorldOptions(20261010L, false, false), 
                        com.skycraft.client.autotest.ReviewWorlds::normal, mc.gui.screen());
            }
            return;
        }
        if (mc.player == null || stepIndex >= STEPS.size()) return;
        if (inWorld++ == 0) waitLeft = STEPS.get(0).delay();
        if (--waitLeft > 0) return;
        Step s = STEPS.get(stepIndex++);
        try {
            s.action().accept(mc);
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Curse autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
