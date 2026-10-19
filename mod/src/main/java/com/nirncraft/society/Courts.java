package com.nirncraft.society;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Settlement courts (F-COURT / R3d-1.2): ensures every capital and city has a full court
 * ({@code >= 3} distinct court roles: {@code jarl} / ruler, {@code steward}, {@code court_wizard}, {@code housecarl})
 * seated at the keep/palace anchor, with named court members for every Skyrim hold capital/city and culture defaults
 * for other provinces.
 */
public final class Courts {
    public static final List<String> REQUIRED_JOBS = List.of("jarl", "steward", "court_wizard", "housecarl");
    public static final Set<String> COURT_JOBS = Set.of("jarl", "steward", "court_wizard", "housecarl", "wizard");
    public static final int MIN_COURT_ROLES = 3;

    public record Spec(String settlementId, String seat, String culture, String rulerTitle,
                       String jarl, String steward, String courtWizard, String housecarl, List<String> why) {
        public String memberName(String courtJob) {
            return switch (courtJob) {
                case "jarl" -> jarl;
                case "steward" -> steward;
                case "court_wizard", "wizard" -> courtWizard;
                case "housecarl" -> housecarl;
                default -> jarl;
            };
        }
    }

    private static final Map<String, Spec> SETTLEMENTS = new LinkedHashMap<>();
    private static final Map<String, Spec> CULTURE_DEFAULTS = new LinkedHashMap<>();
    private static boolean loaded = false;

    private Courts() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = Courts.class.getResourceAsStream("/nirncraft/society/courts.json")) {
            if (in == null) {
                NirnCraft.LOGGER.warn("Missing /nirncraft/society/courts.json");
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject settlements = root.getAsJsonObject("settlements");
            if (settlements != null) {
                for (var entry : settlements.entrySet()) {
                    SETTLEMENTS.put(entry.getKey(), parseSpec(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
            JsonObject cultures = root.getAsJsonObject("culture_defaults");
            if (cultures != null) {
                for (var entry : cultures.entrySet()) {
                    CULTURE_DEFAULTS.put(entry.getKey(), parseSpec(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            NirnCraft.LOGGER.error("Failed to load /nirncraft/society/courts.json", e);
        }
    }

    private static Spec parseSpec(String id, JsonObject obj) {
        String seat = str(obj, "seat", "Keep");
        String culture = str(obj, "culture", "nord");
        String rulerTitle = str(obj, "ruler_title", "Jarl");
        String jarl = str(obj, "jarl", "Balgruuf");
        String steward = str(obj, "steward", "Proventus");
        String wizard = str(obj, "court_wizard", "Farengar");
        String housecarl = str(obj, "housecarl", "Irileth");
        List<String> why = new ArrayList<>();
        if (obj.has("why") && obj.get("why").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("why")) why.add(el.getAsString());
        }
        return new Spec(id, seat, culture, rulerTitle, jarl, steward, wizard, housecarl, Collections.unmodifiableList(why));
    }

    private static String str(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }

    /** Resolves the court specification for a settlement, falling back to its culture defaults. */
    public static Spec courtFor(String settlementId) {
        ensureLoaded();
        Spec explicit = SETTLEMENTS.get(settlementId);
        if (explicit != null) return explicit;
        Optional<Place> place = Places.byId(settlementId);
        String culture = place.map(Place::culture).orElse("nord");
        Spec def = CULTURE_DEFAULTS.getOrDefault(culture, CULTURE_DEFAULTS.get("nord"));
        if (def == null) {
            return new Spec(settlementId, "Keep", "nord", "Jarl",
                    "Harald Ice-Brow", "Halvar Quill-Hand", "Sigridsdottir the Pale", "Torsten Shield-Wall", List.of());
        }
        return new Spec(settlementId, def.seat(), culture, def.rulerTitle(),
                def.jarl(), def.steward(), def.courtWizard(), def.housecarl(), def.why());
    }

    public static boolean isCourtJob(@Nullable String job) {
        return job != null && COURT_JOBS.contains(job);
    }

    public static boolean isCourtRole(@Nullable NpcRole role) {
        return role == NpcRole.JARL || role == NpcRole.STEWARD || role == NpcRole.HOUSECARL || role == NpcRole.MAGE;
    }

    public static NpcRole roleForCourtJob(String job) {
        return switch (job) {
            case "jarl" -> NpcRole.JARL;
            case "steward" -> NpcRole.STEWARD;
            case "court_wizard", "wizard" -> NpcRole.MAGE;
            case "housecarl" -> NpcRole.HOUSECARL;
            default -> NpcRole.JARL;
        };
    }

    public static Component jarlDisplayName(String settlementId) {
        Spec s = courtFor(settlementId);
        if (!"Jarl".equalsIgnoreCase(s.rulerTitle())) {
            return Component.literal(s.rulerTitle() + " " + s.jarl());
        }
        return Component.translatable("entity.nirncraft.jarl", s.jarl());
    }

    public static Component stewardDisplayName(String settlementId) {
        Spec s = courtFor(settlementId);
        return Component.translatable("entity.nirncraft.steward", s.steward());
    }

    public static Component wizardDisplayName(String settlementId) {
        Spec s = courtFor(settlementId);
        return Component.literal(s.courtWizard() + ", Court Wizard");
    }

    public static Component housecarlDisplayName(String settlementId) {
        Spec s = courtFor(settlementId);
        return Component.translatable("society.nirncraft.npc_name.housecarl", s.housecarl());
    }
}
