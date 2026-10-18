package com.skycraft.audio.client;

import com.mojang.blaze3d.audio.Channel;
import com.skycraft.Skycraft;
import com.skycraft.audio.AudioConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skycraft's own "sound physics": an OpenAL EFX reverb send on every world sound while the listener is in an enclosed
 * space ({@link ListenerEnvironment}: caves, ruins, halls, houses), and a low-pass direct filter for sounds that are
 * occluded (walls between you and them, or outside while you are inside, or you are underwater).
 *
 * <ul>
 *   <li>Occlusion is estimated on the client thread when a sound starts ({@link #onPlay}): a cheap block march from
 *   the camera to the sound counts solid blocks in between.</li>
 *   <li>The filters are attached on the sound thread from NeoForge's {@code PlaySoundSourceEvent} /
 *   {@code PlayStreamingSourceEvent}, which hand us the {@link Channel}; its OpenAL source id is the private field
 *   {@code Channel.source}. This exact native target has no public source/EFX accessor, so that one field is read
 *   explicitly; no legacy obfuscation mapping, engine internals, mixin or transformer is used.</li>
 *   <li>The single reverb effect is re-tuned (decay, density, reflections) about once a second as the space changes.</li>
 *   <li>Ambience beds ({@link AmbienceEngine.Bed}) are long-lived streams, so their muffling filter is refreshed live.</li>
 * </ul>
 *
 * Anything missing (no EFX extension, reflection failure, AL errors) logs once and disables the feature.
 */
public final class Acoustics {
    private static boolean tried;
    private static volatile boolean available;
    private static volatile long context;
    private static Field sourceField;
    private static int effect;
    private static int slot;
    private static int sendFilter;
    private static int directFilter;
    private static int bedFilter;

    private static float tunedDecay = -1f;
    private static float tunedSize = -1f;
    private static float bedMuffle = -1f;

    /** Occlusion 0..1 per sound instance, computed when the sound starts (client thread), read on the sound thread. */
    private static final Map<SoundInstance, Float> OCCLUSION = Collections.synchronizedMap(new WeakHashMap<>());
    /** Live OpenAL sources of the ambience beds. */
    private static final Map<AmbienceEngine.Bed, Integer> BEDS = new ConcurrentHashMap<>();

    private Acoustics() {}

    // ------------------------------------------------------------------ set-up

    private static synchronized boolean ensure() {
        long current = ALC10.alcGetCurrentContext();
        if (tried && context == current) return available;
        // The old device/context owns and destroys its EFX objects. Never reuse or delete those IDs in a new one.
        if (context != current) invalidateContext();
        context = current;
        tried = true;
        try {
            long device = current == 0L ? 0L : ALC10.alcGetContextsDevice(current);
            if (device == 0L || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                Skycraft.LOGGER.info("Skycraft acoustics: OpenAL EFX is not available; reverb and occlusion are off");
                return false;
            }
            if (sourceField == null) {
                Field field = Channel.class.getDeclaredField("source");
                if (field.getType() != int.class || !field.trySetAccessible())
                    throw new IllegalStateException("Native Channel.source is not an accessible int; EFX attachment requires that missing public API");
                sourceField = field;
            }
            AL10.alGetError();
            effect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            slot = EXTEfx.alGenAuxiliaryEffectSlots();
            sendFilter = newLowpass();
            directFilter = newLowpass();
            bedFilter = newLowpass();
            tune(1.0f, 8f, 0.6f);
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR) {
                Skycraft.LOGGER.warn("Skycraft acoustics: OpenAL error {} while creating the reverb; reverb and occlusion are off", err);
                releaseFailedSetup();
                return false;
            }
            available = true;
            Skycraft.LOGGER.info("Skycraft acoustics: EFX reverb ready (aux sends: {})",
                    ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS));
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Skycraft acoustics disabled: {}", t.toString());
            releaseFailedSetup();
            available = false;
        }
        return available;
    }

    private static void invalidateContext() {
        available = false;
        tried = false;
        context = 0L;
        effect = slot = sendFilter = directFilter = bedFilter = 0;
        tunedDecay = tunedSize = bedMuffle = -1f;
        BEDS.clear();
    }

    private static void releaseFailedSetup() {
        if (context == 0L || context != ALC10.alcGetCurrentContext()) return;
        if (slot != 0) EXTEfx.alDeleteAuxiliaryEffectSlots(slot);
        if (effect != 0) EXTEfx.alDeleteEffects(effect);
        if (sendFilter != 0) EXTEfx.alDeleteFilters(sendFilter);
        if (directFilter != 0) EXTEfx.alDeleteFilters(directFilter);
        if (bedFilter != 0) EXTEfx.alDeleteFilters(bedFilter);
        effect = slot = sendFilter = directFilter = bedFilter = 0;
    }

    private static void checkError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) throw new IllegalStateException(operation + ": OpenAL error " + error);
    }

    /** SoundEngineLoadEvent runs after the native sound library is rebuilt, including a device/resource reload. */
    public static synchronized void onEngineLoad() {
        invalidateContext();
        OCCLUSION.clear();
    }

    private static int newLowpass() {
        int f = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(f, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(f, EXTEfx.AL_LOWPASS_GAIN, 1f);
        EXTEfx.alFilterf(f, EXTEfx.AL_LOWPASS_GAINHF, 1f);
        return f;
    }

    /** Re-tunes the reverb for a space with this decay (s), mean wall distance (blocks) and hardness (0..1). */
    private static void tune(float decay, float size, float hardness) {
        float s = Mth.clamp(size / 32f, 0f, 1f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, Mth.clamp(0.3f + s, 0f, 1f));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 0.85f + 0.15f * hardness);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAIN, 0.32f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF, 0.55f + 0.4f * hardness);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, Mth.clamp(decay, 0.1f, 20f));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.45f + 0.4f * hardness);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, Mth.clamp(0.2f + 0.9f * (1f - s), 0f, 3.16f));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, Mth.clamp(size / 343f, 0.004f, 0.3f));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, Mth.clamp(0.8f + 1.0f * s, 0f, 10f));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, Mth.clamp(size / 400f, 0.005f, 0.1f));
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF, 0.994f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_ROOM_ROLLOFF_FACTOR, 0f);
        EXTEfx.alEffecti(effect, EXTEfx.AL_REVERB_DECAY_HFLIMIT, AL10.AL_TRUE);
        // the slot copies the effect's parameters when it is (re)attached
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
    }

    // ------------------------------------------------------------------ client thread

    /** Once a second: follow the listener's space with the reverb, and refresh the beds' muffling. */
    public static void tick(Minecraft mc) {
        if (mc.level == null || !tried || !available) return;
        if (mc.level.getGameTime() % 20 != 0) return;
        try {
            if (context == 0L || context != ALC10.alcGetCurrentContext()) {
                synchronized (Acoustics.class) { invalidateContext(); }
                return;
            }
            float decay = ListenerEnvironment.decay;
            float size = ListenerEnvironment.roomSize;
            if (Math.abs(decay - tunedDecay) > tunedDecay * 0.08f || Math.abs(size - tunedSize) > 3f) {
                tunedDecay = decay;
                tunedSize = size;
                tune(decay, size, ListenerEnvironment.hardness);
            }
            float m = bedMuffleNow();
            if (Math.abs(m - bedMuffle) > 0.03f) {
                bedMuffle = m;
                EXTEfx.alFilterf(bedFilter, EXTEfx.AL_LOWPASS_GAIN, 1f);
                EXTEfx.alFilterf(bedFilter, EXTEfx.AL_LOWPASS_GAINHF, Mth.clamp(1f - 0.9f * m, 0.05f, 1f));
                for (var bed : BEDS.entrySet()) {
                    int src = bed.getValue();
                    if (AL10.alIsSource(src)) AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, bedFilter);
                    else BEDS.remove(bed.getKey(), src);
                }
            }
            checkError("Updating room reverb and ambience occlusion");
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static float bedMuffleNow() {
        if (!AudioConfig.OCCLUSION.get()) return 0f;
        return ListenerEnvironment.underwater ? 1f : ListenerEnvironment.muffle;
    }

    /** Called from {@code PlaySoundEvent} (client thread) for every sound about to start: estimates its occlusion. */
    public static void onPlay(SoundInstance sound) {
        if (sound == null || !AudioConfig.OCCLUSION.get() || (tried && !available)) return;
        if (sound.isRelative() || sound.getAttenuation() == SoundInstance.Attenuation.NONE) return;
        SoundSource cat = sound.getSource();
        if (cat == SoundSource.MUSIC || cat == SoundSource.MASTER) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.mainCamera();
        if (!camera.isInitialized()) return;
        float occ = occlusion(mc.level, camera.position(), new Vec3(sound.getX(), sound.getY(), sound.getZ()));
        if (occ > 0.01f) OCCLUSION.put(sound, occ);
    }

    /** Solid blocks between listener and source (up to 1.0), plus "it is outside and I am inside". */
    static float occlusion(Level level, Vec3 from, Vec3 to) {
        double dist = from.distanceTo(to);
        if (dist < 1.5) return 0f;
        int steps = Math.min(48, (int) Math.ceil(dist * 1.5));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos start = BlockPos.containing(from);
        BlockPos end = BlockPos.containing(to);
        long last = Long.MIN_VALUE;
        float solid = 0f;
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            p.set(Mth.floor(Mth.lerp(t, from.x, to.x)), Mth.floor(Mth.lerp(t, from.y, to.y)), Mth.floor(Mth.lerp(t, from.z, to.z)));
            long key = p.asLong();
            if (key == last || p.equals(start) || p.equals(end)) continue;
            last = key;
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (s.canOcclude() && s.isSolidRender()) solid += 1f;
            else if (s.canOcclude()) solid += 0.4f;
        }
        float occ = Math.min(1f, solid * 0.22f);
        if ((ListenerEnvironment.interior || ListenerEnvironment.underground) && ListenerEnvironment.outdoors(level, end)) {
            occ = Math.max(occ, 0.55f);
        }
        return occ;
    }

    // ------------------------------------------------------------------ sound thread

    /** A channel was set up for {@code sound}: attach the reverb send and the occlusion filter. */
    public static void onSource(SoundInstance sound, Channel channel) {
        boolean reverb = AudioConfig.REVERB.get();
        boolean occlusion = AudioConfig.OCCLUSION.get();
        if (!reverb && !occlusion) return;
        if (sound == null || channel == null || !ensure()) return;
        try {
            int src = sourceField.getInt(channel);
            if (src <= 0 || !AL10.alIsSource(src)) throw new IllegalStateException("Native Channel.source is not a live OpenAL source");
            if (sound instanceof AmbienceEngine.Bed bed) {
                BEDS.put(bed, src);
                AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, bedFilter);
                checkError("Attaching ambience occlusion");
                return;
            }
            SoundSource cat = sound.getSource();
            if (cat == SoundSource.MUSIC || cat == SoundSource.MASTER) return;
            if (sound.isRelative() && sound.getAttenuation() == SoundInstance.Attenuation.NONE) return; // UI, music, stings
            Float o = OCCLUSION.remove(sound);
            float occ = occlusion && o != null ? o : 0f;
            if (occlusion && ListenerEnvironment.underwater) occ = Math.max(occ, 0.8f);
            if (occ > 0.01f) {
                EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAIN, 1f - 0.45f * occ);
                EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAINHF, Mth.clamp(1f - 0.92f * occ, 0.04f, 1f));
                AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, directFilter);
            } else AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            float wet = reverb ? ListenerEnvironment.wet * AudioConfig.REVERB_AMOUNT.get().floatValue() : 0f;
            if (wet > 0.02f) {
                EXTEfx.alFilterf(sendFilter, EXTEfx.AL_LOWPASS_GAIN, Mth.clamp(wet, 0f, 1f));
                EXTEfx.alFilterf(sendFilter, EXTEfx.AL_LOWPASS_GAINHF, Mth.clamp(1f - 0.5f * occ, 0.1f, 1f));
                AL11.alSource3i(src, EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, sendFilter);
            } else AL11.alSource3i(src, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
            checkError("Attaching world-sound reverb and occlusion");
        } catch (Throwable t) {
            disable(t);
        }
    }

    public static void forgetBed(AmbienceEngine.Bed bed) {
        BEDS.remove(bed);
    }

    private static void disable(Throwable t) {
        if (available) Skycraft.LOGGER.warn("Skycraft acoustics disabled after an error: {}", t.toString());
        available = false;
    }

    public static void reset() {
        OCCLUSION.clear();
        BEDS.clear();
        tunedDecay = tunedSize = bedMuffle = -1f;
    }
}
