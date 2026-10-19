package com.nirncraft.perf;

import java.util.function.Supplier;
import net.minecraft.server.level.TicketType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registered, transient loading tickets; no saved orphan tickets after a process stops. */
public final class PerfTickets {
    private static final DeferredRegister<TicketType> TYPES = DeferredRegister.create(Registries.TICKET_TYPE, "nirncraft");
    public static final Supplier<TicketType> PREWARM = ticket("prewarm");
    public static final Supplier<TicketType> PREGEN = ticket("pregen");
    public static final Supplier<TicketType> BENCH = ticket("bench");
    public static final Supplier<TicketType> REGEN = ticket("dev_regen");
    /** Chunks ahead of a player (Lookahead): expire after 2 s unless re-added, so turning away lets them lapse. */
    public static final Supplier<TicketType> LOOKAHEAD = TYPES.register("lookahead", () -> new TicketType(40L, TicketType.FLAG_LOADING));
    private PerfTickets() {}
    private static Supplier<TicketType> ticket(String id) { return TYPES.register(id, () -> new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING)); }
    public static void init(net.neoforged.bus.api.IEventBus bus) { TYPES.register(bus); }
}
