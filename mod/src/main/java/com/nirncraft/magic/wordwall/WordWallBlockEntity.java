package com.nirncraft.magic.wordwall;

import com.nirncraft.magic.MagicRegistry;
import com.nirncraft.magic.shout.Shout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Remembers which shout a Word Wall teaches (chosen at random, weighted, the first time it's needed). */
public class WordWallBlockEntity extends BlockEntity {
    private String shout = "";

    public WordWallBlockEntity(BlockPos pos, BlockState state) {
        super(MagicRegistry.WORD_WALL_BE.get(), pos, state);
    }

    /**
     * The shout this wall teaches. A wall assigned an id this version doesn't know (a shout from a newer content
     * list, a typo in a structure) keeps that id for later but teaches a random shout meanwhile.
     */
    public Shout shout(RandomSource random) {
        Shout s = Shout.byId(shout);
        if (s == null) {
            if (shout.isEmpty()) {
                s = WordWalls.randomShout(random);
                setShout(s);
            } else {
                s = WordWalls.fallbackFor(shout);
            }
        }
        return s;
    }

    public void setShout(Shout s) {
        this.shout = s.id();
        setChanged();
    }

    /**
     * Assigns a shout by canonical id (see {@link Shout#byId}; dragon words like {@code fus_ro_dah} work too).
     * Unknown ids are stored as given and handled gracefully. Returns true if the id is a known shout.
     */
    public boolean setShoutId(String id) {
        Shout s = Shout.byId(id);
        this.shout = s != null ? s.id() : id == null ? "" : id.trim();
        setChanged();
        return s != null;
    }

    /** The raw id stored on this wall ("" until first needed). */
    public String shoutId() {
        return shout;
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput tag) {
        super.loadAdditional(tag);
        this.shout = tag.getStringOr("shout", "");
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput tag) {
        super.saveAdditional(tag);
        tag.putString("shout", shout);
    }

    /** Players who walk up to the wall (within 3 blocks) read it; nearby players hear the Nordic throat chant. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, WordWallBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        long time = level.getGameTime();
        if (time % 80 == (pos.asLong() & 31)) {
            AABB chantArea = new AABB(pos).inflate(16.0);
            if (!server.getEntitiesOfClass(ServerPlayer.class, chantArea, pl -> pl.isAlive() && !pl.isSpectator()).isEmpty()) {
                server.playSound(null, pos, com.nirncraft.world.WorldSounds.WORDWALL_CHANT.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.4f, 1.0f);
            }
        }
        if (time % 20 != (pos.asLong() & 15)) return;
        AABB area = new AABB(pos).inflate(3.0);
        for (ServerPlayer p : server.getEntitiesOfClass(ServerPlayer.class, area, pl -> pl.isAlive() && !pl.isSpectator())) {
            if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 3.5 * 3.5) {
                WordWalls.teach(p, server, pos, be.shout(level.getRandom()), false);
            }
        }
    }
}
