package com.nirncraft.survival.client;

import com.mojang.authlib.GameProfile;
import com.nirncraft.survival.corpse.PlayerCorpseEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The lying body of a {@link PlayerCorpseEntity}: a never-ticked client player with the owner's profile (their skin
 * if they are online, a default skin otherwise), drawn by the creature corpse renderer. Its own team hides the name
 * tag (the corpse shows "X's Body" when looked at instead).
 */
public final class PlayerCorpseClient {
    private static final PlayerTeam HIDDEN = new PlayerTeam(new Scoreboard(), "nirncraft_corpse");

    static {
        HIDDEN.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
    }

    private PlayerCorpseClient() {}

    @Nullable
    public static Entity dummy(PlayerCorpseEntity corpse) {
        if (!(corpse.level() instanceof ClientLevel level)) return null;
        UUID owner = corpse.owner();
        if (owner == null) return null;
        String name = corpse.ownerName().isEmpty() ? "Dovahkiin" : corpse.ownerName();
        Body body = new Body(level, new GameProfile(owner, name));
        body.setPos(corpse.getX(), corpse.getY(), corpse.getZ());
        body.setYRot(180f);
        body.yRotO = 180f;
        body.yBodyRot = 180f;
        body.yBodyRotO = 180f;
        body.yHeadRot = 180f;
        body.yHeadRotO = 180f;
        body.setXRot(0f);
        body.xRotO = 0f;
        return body;
    }

    /** A remote player that is never added to the level. */
    private static final class Body extends RemotePlayer {
        Body(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public PlayerTeam getTeam() {
            return HIDDEN;
        }

        @Override
        public boolean isSpectator() {
            return false;
        }
    }
}
