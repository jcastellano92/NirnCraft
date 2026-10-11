package com.skycraft.realms.client;

import com.skycraft.Skycraft;
import com.skycraft.realms.Realm;
import com.skycraft.realms.RealmBlocks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client side of the realms: their dimension effects (sky, light), fog that closes in or opens up with the biome
 * (Sovngarde's misty vale and chasm, Dementia's bogs, Apocrypha's murk; Alduin's soul-mist during the battle), and
 * ambient particles around the camera: rising embers in the Deadlands, drifting soul-mist in Sovngarde, floating
 * glyphs in Apocrypha, souls in the Soul Cairn, falling ash in Coldharbour, sparkles and spores in the Isles.
 */
public final class RealmsClient {
    private RealmsClient() {}

    @Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void effects(RegisterDimensionSpecialEffectsEvent event) {
            for (Realm r : Realm.values()) event.register(r.effects(), new RealmEffects(r));
        }

        @SuppressWarnings("deprecation")
        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                RenderType cutout = RenderType.cutout(), translucent = RenderType.translucent();
                for (var b : new net.minecraftforge.registries.RegistryObject[]{RealmBlocks.BLOODGRASS, RealmBlocks.HARRADA,
                        RealmBlocks.MANIA_BLOOM, RealmBlocks.DEMENTIA_BULB, RealmBlocks.DAEDRIC_SPIKE, RealmBlocks.SIGIL_FLAME,
                        RealmBlocks.MADNESS_DOOR, RealmBlocks.BLACK_BOOK, RealmBlocks.SOUL_GEM_CLUSTER, RealmBlocks.SIGIL_STONE}) {
                    ItemBlockRenderTypes.setRenderLayer((net.minecraft.world.level.block.Block) b.get(), cutout);
                }
                for (var b : new net.minecraftforge.registries.RegistryObject[]{RealmBlocks.OBLIVION_PORTAL, RealmBlocks.VALOR_PORTAL,
                        RealmBlocks.SOUL_PORTAL, RealmBlocks.COLDHARBOUR_PORTAL}) {
                    ItemBlockRenderTypes.setRenderLayer((net.minecraft.world.level.block.Block) b.get(), translucent);
                }
            });
        }
    }

    // ------------------------------------------------------------------ fog

    private static float fogNear = -1, fogFar = -1;
    /** 0..1: how strongly Alduin's soul-mist is gathered (Sovngarde, while the World-Eater lives nearby). */
    private static float alduinMist;

    @Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private ForgeEvents() {}

        @SubscribeEvent
        public static void fog(ViewportEvent.RenderFog event) {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            Realm realm = Realm.of(level);
            if (realm == null || event.getMode() != net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN) return;
            Camera cam = event.getCamera();
            if (cam.getFluidInCamera() != FogType.NONE) return;
            if (mc.player != null && (mc.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    || mc.player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS))) return;
            float far = event.getFarPlaneDistance();
            float[] want = target(realm, level, cam.getBlockPosition(), far);
            if (fogFar < 0) {
                fogNear = want[0];
                fogFar = want[1];
            }
            // ease toward the biome's fog (frame-rate independent enough at 60-200 fps)
            fogNear = Mth.lerp(0.02f, fogNear, want[0]);
            fogFar = Mth.lerp(0.02f, fogFar, want[1]);
            event.setNearPlaneDistance(fogNear);
            event.setFarPlaneDistance(Math.min(far, fogFar));
            event.setCanceled(true);
        }

        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.isPaused()) {
                fogFar = -1;
                return;
            }
            Realm realm = Realm.of(mc.level);
            if (realm == null) {
                fogFar = -1;
                alduinMist = 0;
                return;
            }
            boolean alduin = realm == Realm.SOVNGARDE && !mc.level.getEntitiesOfClass(EnderDragon.class,
                    mc.player.getBoundingBox().inflate(220)).isEmpty();
            alduinMist = Mth.clamp(alduinMist + (alduin ? 0.01f : -0.02f), 0f, 1f);
            if (mc.options.particles().get() != net.minecraft.client.ParticleStatus.MINIMAL) particles(realm, mc);
        }
    }

    private static String biomeOf(ClientLevel level, BlockPos pos) {
        Holder<Biome> b = level.getBiome(pos);
        return b.unwrapKey().map(k -> k.location().getPath()).orElse("");
    }

    /** {near, far} fog distances for where the camera is. */
    private static float[] target(Realm realm, ClientLevel level, BlockPos pos, float far) {
        String biome = biomeOf(level, pos);
        float n, f;
        switch (realm) {
            case DEADLANDS -> {
                f = Math.min(far, 230);
                n = f * 0.25f;
            }
            case SOVNGARDE -> {
                if (biome.endsWith("chasm")) {
                    n = 0;
                    f = 90;
                } else if (biome.endsWith("mistvale")) {
                    n = 16;
                    f = 150;
                } else {
                    f = Math.min(far, 210);
                    n = f * 0.3f;
                }
                // Alduin's soul-mist swallows the vale while he lives
                f = Mth.lerp(alduinMist, f, 34);
                n = Mth.lerp(alduinMist, n, 0);
            }
            case SHIVERING_ISLES -> {
                if (biome.contains("dementia")) {
                    n = 2;
                    f = 52;
                } else {
                    f = far;
                    n = far * 0.55f;
                }
            }
            case APOCRYPHA -> {
                n = 6;
                f = Math.min(far, 96);
            }
            case SOUL_CAIRN -> {
                n = 8;
                f = Math.min(far, biome.endsWith("boneyard") ? 70 : 110);
            }
            case COLDHARBOUR -> {
                n = 12;
                f = Math.min(far, 130);
            }
            default -> {
                n = far * 0.75f;
                f = far;
            }
        }
        return new float[]{n, Math.max(n + 8, f)};
    }

    // ------------------------------------------------------------------ particles

    private static void particles(Realm realm, Minecraft mc) {
        ClientLevel level = mc.level;
        RandomSource r = level.random;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        switch (realm) {
            case DEADLANDS -> {
                for (int i = 0; i < 3; i++) {
                    double x = px + (r.nextDouble() - 0.5) * 32, z = pz + (r.nextDouble() - 0.5) * 32;
                    double y = py + (r.nextDouble() - 0.4) * 14;
                    emit(level, r.nextInt(4) == 0 ? ParticleTypes.LAVA : ParticleTypes.SMALL_FLAME, x, y, z, 0, 0.03 + r.nextDouble() * 0.04, 0);
                }
                if (r.nextInt(2) == 0) emit(level, ParticleTypes.ASH, px + (r.nextDouble() - 0.5) * 24, py + 6 + r.nextDouble() * 6,
                        pz + (r.nextDouble() - 0.5) * 24, 0, -0.02, 0);
            }
            case SOVNGARDE -> {
                int n = 1 + Math.round(alduinMist * 6);
                double t = level.getGameTime() * 0.02;
                for (int i = 0; i < n; i++) {
                    double a = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * (alduinMist > 0.1 ? 18 : 28);
                    double x = px + Math.cos(a) * d, z = pz + Math.sin(a) * d;
                    double y = py - 1 + r.nextDouble() * (alduinMist > 0.1 ? 8 : 3);
                    // the mist swirls around the hunted (tangential drift)
                    double sw = 0.04 + alduinMist * 0.08;
                    emit(level, ParticleTypes.CLOUD, x, y, z, -Math.sin(a + t) * sw, 0.003, Math.cos(a + t) * sw);
                }
                if (r.nextInt(6) == 0) emit(level, ParticleTypes.END_ROD, px + (r.nextDouble() - 0.5) * 30, py + r.nextDouble() * 8,
                        pz + (r.nextDouble() - 0.5) * 30, 0, 0.005, 0);
            }
            case SHIVERING_ISLES -> {
                boolean dementia = biomeOf(level, mc.player.blockPosition()).contains("dementia");
                if (r.nextInt(2) == 0) {
                    ParticleOptions p = dementia ? ParticleTypes.MYCELIUM : (r.nextBoolean() ? ParticleTypes.END_ROD : ParticleTypes.GLOW);
                    emit(level, p, px + (r.nextDouble() - 0.5) * 26, py + r.nextDouble() * 6, pz + (r.nextDouble() - 0.5) * 26,
                            (r.nextDouble() - 0.5) * 0.02, 0.01, (r.nextDouble() - 0.5) * 0.02);
                }
            }
            case APOCRYPHA -> {
                for (int i = 0; i < 2; i++) {
                    emit(level, ParticleTypes.ENCHANT, px + (r.nextDouble() - 0.5) * 20, py + r.nextDouble() * 8,
                            pz + (r.nextDouble() - 0.5) * 20, (r.nextDouble() - 0.5) * 0.4, 0.1, (r.nextDouble() - 0.5) * 0.4);
                }
                if (r.nextInt(4) == 0) emit(level, ParticleTypes.SQUID_INK, px + (r.nextDouble() - 0.5) * 16, py + 4 + r.nextDouble() * 6,
                        pz + (r.nextDouble() - 0.5) * 16, 0, -0.01, 0);
            }
            case SOUL_CAIRN -> {
                if (r.nextInt(2) == 0) emit(level, ParticleTypes.SOUL, px + (r.nextDouble() - 0.5) * 24, py - 1 + r.nextDouble() * 3,
                        pz + (r.nextDouble() - 0.5) * 24, 0, 0.02, 0);
                if (r.nextInt(3) == 0) emit(level, ParticleTypes.WHITE_ASH, px + (r.nextDouble() - 0.5) * 24, py + r.nextDouble() * 6,
                        pz + (r.nextDouble() - 0.5) * 24, 0, 0, 0);
            }
            case COLDHARBOUR -> {
                for (int i = 0; i < 2; i++) {
                    emit(level, ParticleTypes.ASH, px + (r.nextDouble() - 0.5) * 26, py + 3 + r.nextDouble() * 9,
                            pz + (r.nextDouble() - 0.5) * 26, 0.01, -0.03, 0.005);
                }
                if (r.nextInt(8) == 0) emit(level, ParticleTypes.SOUL_FIRE_FLAME, px + (r.nextDouble() - 0.5) * 20, py + r.nextDouble() * 4,
                        pz + (r.nextDouble() - 0.5) * 20, 0, 0.02, 0);
            }
        }
    }

    private static void emit(ClientLevel level, ParticleOptions p, double x, double y, double z, double vx, double vy, double vz) {
        BlockPos at = BlockPos.containing(x, y, z);
        if (!level.getBlockState(at).isAir()) return;
        level.addParticle(p, x, y, z, vx, vy, vz);
    }
}
