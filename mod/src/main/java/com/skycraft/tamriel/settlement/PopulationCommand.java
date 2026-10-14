package com.skycraft.tamriel.settlement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.society.Housing;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.tamriel.settlement.plan.Plan;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /skypop <place id>}: plans a named place exactly as world generation does (no building, no chunk loads),
 * replays its pieces' anchors, runs the {@link Housing} model over them and writes
 * {@code skycraft-previews/population_<id>.json}: beds by kind, every resident with job, bed and workplace, jobs left
 * unstaffed, the old model's head count. {@code tools/qa/population_lint.py} reads these files (qa.ps1 -Server).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PopulationCommand {
    private PopulationCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("skypop").requires(s -> s.hasPermission(2))
                .then(Commands.argument("id", StringArgumentType.word()).executes(PopulationCommand::run)));
    }

    private static int run(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        String id = StringArgumentType.getString(c, "id");
        Place p = Places.byId(id).orElse(null);
        if (p == null) {
            c.getSource().sendFailure(Component.literal("No place " + id));
            return 0;
        }
        Plan plan = SettlementStructure.planPlace(SettlementDevCommands.context(level, p.x(), p.z()), p, "auto");
        List<Anchor> anchors = new ArrayList<>();
        long seed = Cities.seed(p);
        for (int i = 0; i < plan.specs.size(); i++) {
            new SettlementPiece(plan.specs.get(i), seed ^ (i * 0x9E3779B97F4A7C15L), p.id(), i).record(anchors);
        }
        int radius = Math.max(p.radius(), Cities.layoutRadius(p));
        int tier = p.type() == PlaceType.CAPITAL || p.type() == PlaceType.CITY ? 0 : p.type() == PlaceType.TOWN || radius >= 45 ? 1 : 2;
        Housing.Plan h = Housing.plan(id, anchors, tier);
        int legacy = Housing.legacyCount(anchors, tier);

        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("tier", tier);
        o.addProperty("anchors", anchors.size());
        JsonObject beds = new JsonObject();
        for (Housing.BedKind k : Housing.BedKind.values()) beds.addProperty(k.name().toLowerCase(), h.beds.getOrDefault(k, 0));
        o.add("beds", beds);
        o.addProperty("capacity", h.capacity());
        o.addProperty("guest_beds", h.guestBeds);
        o.addProperty("inn_rooms", h.innRooms);
        o.addProperty("posts", h.posts);
        o.addProperty("meetings", h.meetings);
        o.addProperty("residents", h.residents());
        o.addProperty("post_sleepers", h.postSleepers());
        o.addProperty("inmates", h.inmates());
        o.addProperty("people", h.bySlot.size());
        // the watch (society.GuardWatch): exactly 2 posted guards at a major settlement's main gate, 1 patrol in a
        // minor one, none elsewhere; none of them is a housing resident (Helgen's scripted garrison excepted)
        com.skycraft.society.GuardWatch.Watch watch = com.skycraft.society.GuardWatch.of(p);
        o.addProperty("watch", watch.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("watch_posted", watch == com.skycraft.society.GuardWatch.Watch.MAJOR ? com.skycraft.society.GuardWatch.POSTED : 0);
        o.addProperty("watch_patrols", watch == com.skycraft.society.GuardWatch.Watch.MINOR ? com.skycraft.society.GuardWatch.PATROLS : 0);
        int guardResidents = 0;
        for (Housing.Resident r : h.bySlot.values()) if (r.slotType() == AnchorType.GUARD) guardResidents++;
        o.addProperty("guard_residents", guardResidents);
        o.addProperty("guard_profile", com.skycraft.creatures.GuardProfiles.of(p).profile().id());
        o.addProperty("legacy", legacy);
        o.addProperty("household_idle", h.householdIdle);
        JsonObject wp = new JsonObject();
        h.workplaces.forEach((t, n) -> wp.addProperty(t.id(), n));
        o.add("workplaces", wp);
        JsonArray un = new JsonArray();
        for (String s : h.unstaffed) un.add(s);
        o.add("unstaffed", un);
        JsonObject idle = new JsonObject();
        h.idleAt.forEach((m, n) -> idle.addProperty(String.valueOf(m), n));
        o.add("idle_at", idle);
        JsonArray an = new JsonArray();
        for (Anchor a : anchors) {
            JsonArray row = new JsonArray();
            row.add(a.type().id());
            row.add(a.owner());
            an.add(row);
        }
        o.add("anchor_list", an);
        JsonArray people = new JsonArray();
        for (Housing.Resident r : h.bySlot.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("slot", r.slot());
            j.addProperty("job", r.job());
            j.addProperty("role", r.role() == null ? "" : r.role().id);
            j.addProperty("bed", r.bed());
            j.addProperty("bed_kind", r.bedKind().name().toLowerCase());
            j.addProperty("work", r.work());
            j.addProperty("leisure", r.leisure());
            people.add(j);
        }
        o.add("people_list", people);
        File dir = SettlementDevCommands.DIR;
        dir.mkdirs();
        try (FileWriter fw = new FileWriter(new File(dir, "population_" + id + ".json"))) {
            fw.write(o.toString());
        } catch (IOException e) {
            Skycraft.LOGGER.error("Population dump failed", e);
            return 0;
        }
        String msg = String.format("[skypop] %s tier %d: %d beds, %d residents (+%d on post, %d inmates), %d jobs unstaffed, old model %d",
                id, tier, h.capacity(), h.residents(), h.postSleepers(), h.inmates(), h.unstaffed.size(), legacy);
        Skycraft.LOGGER.info(msg);
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return h.bySlot.size();
    }
}
