package com.skycraft.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/** Decorates the active keyboard/controller input through public APIs so perks can modify its movement vector. */
public final class SkyMovementInput extends ClientInput {
    private final ClientInput source;

    private SkyMovementInput(ClientInput source) {
        this.source = source;
        copyInput();
    }

    static SkyMovementInput prepare(MovementInputUpdateEvent event) {
        if (event.getInput() instanceof SkyMovementInput input) return input;
        // The event retains its original input reference. Later handlers in this same dispatch must keep earlier edits.
        if(event.getEntity() instanceof LocalPlayer player && player.input instanceof SkyMovementInput input
                && input.source==event.getInput())return input;
        SkyMovementInput input = new SkyMovementInput(event.getInput());
        if (event.getEntity() instanceof LocalPlayer player) player.input = input;
        return input;
    }

    private void copyInput() {
        keyPresses = source.keyPresses;
        moveVector = source.getMoveVector();
    }

    @Override
    public void tick() {
        source.tick();
        copyInput();
    }

    public static void stop(MovementInputUpdateEvent event) {
        SkyMovementInput input=prepare(event);
        input.moveVector=Vec2.ZERO;
        input.keyPresses=new net.minecraft.world.entity.player.Input(false,false,false,false,false,false,false);
    }

    void scaleMovement(float factor) {
        moveVector = moveVector.scale(factor);
    }

    void limitForward(float maximum) {
        if (moveVector.y > maximum) moveVector = new Vec2(moveVector.x, maximum);
    }
}
