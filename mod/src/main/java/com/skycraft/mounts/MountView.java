package com.skycraft.mounts;

import net.minecraft.network.FriendlyByteBuf;

/**
 * What one viewer is allowed to know about one mount: the payload of the target card, the stat sheet and the stable's
 * comparison screen. Built on the server ({@link MountInspect}) so the perks of the viewer decide what is in it; the
 * client only draws.
 *
 * <p>{@code detail}: 0 words only (no grades, no numbers: "swift, sturdy"), 1 grades (an estimate {@code lo..hi} when
 * the viewer cannot judge a stranger's animal exactly), 2 grades and numbers. {@code owner}: 0 wild, 1 yours, 2
 * someone else's, 3 for sale. A kind id of "" is "a plain animal": the viewer cannot tell what it is.</p>
 */
public record MountView(String kindId, String name, byte owner, String ownerName, byte detail, byte level, float levelProgress,
                        byte[] gradeLo, byte[] gradeHi, float[] value, byte[] growth, byte[] caps, float trust,
                        String saddle, int armor, float bags, float health, float maxHealth) {
    public static final int STATS = MountProfile.N + 1;
    /** Number a hidden value is sent as. */
    public static final float HIDDEN = -1f;

    public static MountView plain(String name, float health, float maxHealth) {
        byte[] z = new byte[STATS];
        float[] v = new float[MountProfile.N];
        java.util.Arrays.fill(v, HIDDEN);
        return new MountView("", name, (byte) 0, "", (byte) 0, (byte) 0, 0f, z, z, v, new byte[MountProfile.N], new byte[MountProfile.N],
                HIDDEN, "", 0, 0f, health, maxHealth);
    }

    public MountKind kind() {
        return MountKind.byId(kindId);
    }

    public boolean known() {
        return !kindId.isEmpty();
    }

    public boolean exact(int stat) {
        return gradeLo[stat] == gradeHi[stat];
    }

    public boolean numbers() {
        return detail >= 2;
    }

    public void write(FriendlyByteBuf b) {
        b.writeUtf(kindId, 40);
        b.writeUtf(name, 80);
        b.writeByte(owner);
        b.writeUtf(ownerName, 40);
        b.writeByte(detail);
        b.writeByte(level);
        b.writeFloat(levelProgress);
        for (int i = 0; i < STATS; i++) {
            b.writeByte(gradeLo[i]);
            b.writeByte(gradeHi[i]);
        }
        for (int i = 0; i < MountProfile.N; i++) {
            b.writeFloat(value[i]);
            b.writeByte(growth[i]);
            b.writeByte(caps[i]);
        }
        b.writeFloat(trust);
        b.writeUtf(saddle, 20);
        b.writeVarInt(armor);
        b.writeFloat(bags);
        b.writeFloat(health);
        b.writeFloat(maxHealth);
    }

    public static MountView read(FriendlyByteBuf b) {
        String kind = b.readUtf(40);
        String name = b.readUtf(80);
        byte owner = b.readByte();
        String ownerName = b.readUtf(40);
        byte detail = b.readByte();
        byte level = b.readByte();
        float progress = b.readFloat();
        byte[] lo = new byte[STATS], hi = new byte[STATS];
        for (int i = 0; i < STATS; i++) {
            lo[i] = b.readByte();
            hi[i] = b.readByte();
        }
        float[] value = new float[MountProfile.N];
        byte[] growth = new byte[MountProfile.N], caps = new byte[MountProfile.N];
        for (int i = 0; i < MountProfile.N; i++) {
            value[i] = b.readFloat();
            growth[i] = b.readByte();
            caps[i] = b.readByte();
        }
        float trust = b.readFloat();
        String saddle = b.readUtf(20);
        int armor = b.readVarInt();
        float bags = b.readFloat();
        float health = b.readFloat();
        float maxHealth = b.readFloat();
        return new MountView(kind, name, owner, ownerName, detail, level, progress, lo, hi, value, growth, caps, trust, saddle, armor, bags,
                health, maxHealth);
    }
}
