package com.nirncraft.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.item.EnchantedStaffItem;
import com.nirncraft.arsenal.item.StaffItem;
import com.nirncraft.client.screen.HubScreen;
import com.nirncraft.client.screen.UiInput;
import com.nirncraft.combat.ArmorClass;
import com.nirncraft.combat.WeaponClass;
import com.nirncraft.core.Currency;
import com.nirncraft.economy.ItemCategory;
import com.nirncraft.economy.ItemValues;
import com.nirncraft.inventory.EquipmentData;
import com.nirncraft.inventory.ItemWeights;
import com.nirncraft.inventory.client.ClientInventoryHandlers;
import com.nirncraft.inventory.client.InventoryClientEvents;
import com.nirncraft.inventory.client.SkyUi;
import com.nirncraft.inventory.client.SkyrimInventoryScreen;
import com.nirncraft.loot.container.ContainerKind;
import com.nirncraft.loot.container.ContainerRegistry;
import com.nirncraft.loot.container.ContainerStyle;
import com.nirncraft.loot.container.LootContainerBlock;
import com.nirncraft.society.Cultures;
import com.nirncraft.society.NpcEntities;
import com.nirncraft.society.NpcLook;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.Npcs;
import com.nirncraft.society.Outfit;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.world.client.MapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * NirnCraft Skyrim-styled Creative & Dev Catalog Screen.
 *
 * <p>Unifies with {@link SkyrimInventoryScreen} using shared {@link SkyUi} chrome, {@link ItemCategory},
 * {@link ItemValues}, {@link ItemWeights}, {@link EquipmentData}, {@link ContainerKind}, {@link ContainerStyle},
 * {@link NpcRole}, and {@link NpcNames.Race}. Catalog entries are built lazily on demand so non-dev survival
 * gameplay incurs zero memory or CPU overhead. Scales cleanly from 427x267 (Steam Deck GUI scale 3) to 960x540+.</p>
 */
public class SkyrimCreativeScreen extends Screen {
    private static final int ROW_H = 17;
    private static final int CAT_ROW_H = 14;
    private static final int TOP_BAR_H = 20;
    private static final int FILTER_BAR_H = 34;
    private static final int COL_HEADER_H = 13;
    private static final int BOTTOM_BAR_H = 24;
    private static final int MARGIN = 6;

    private static final int SORT_NAME = 0;
    private static final int SORT_TAG = 1;
    private static final int SORT_WEIGHT = 2;
    private static final int SORT_VALUE = 3;

    private static final int SOURCE_ALL = 0;
    private static final int SOURCE_NIRNCRAFT = 1;
    private static final int SOURCE_VANILLA = 2;
    private static final String[] SOURCE_LABELS = {"All", "NirnCraft", "Vanilla"};

    private static final int[] QTY_PRESETS = {1, 5, 10, 32, 64};

    /** Curated NirnCraft container loot tables selectable in the Loot Chests configurator. */
    public record LootTablePreset(String id, String label, String themeTag) {}

    public static final LootTablePreset[] LOOT_TABLES = {
            new LootTablePreset("nirncraft:containers/dungeon/boss", "Boss Hoard", "Boss / Grand"),
            new LootTablePreset("nirncraft:containers/dungeon/major", "Dungeon Major", "Dungeon"),
            new LootTablePreset("nirncraft:containers/dungeon/minor", "Dungeon Minor", "Dungeon"),
            new LootTablePreset("nirncraft:containers/strongbox/normal", "Strongbox Lockbox", "Strongbox"),
            new LootTablePreset("nirncraft:containers/dwelling/rich", "Noble / Rich Dwelling", "Dwelling"),
            new LootTablePreset("nirncraft:containers/dwelling/common", "Common Dwelling", "Dwelling"),
            new LootTablePreset("nirncraft:containers/camp/bandit", "Bandit Camp", "Camp"),
            new LootTablePreset("nirncraft:containers/barrel/food", "Provisions Barrel", "Barrel"),
            new LootTablePreset("nirncraft:containers/urn/burial", "Nordic Burial Urn", "Burial Urn"),
            new LootTablePreset("nirncraft:containers/bookshelf/common", "Lore Bookshelf", "Bookshelf"),
            new LootTablePreset("nirncraft:containers/remains/skeleton", "Skeletal Remains", "Remains")
    };

    /** Creative catalog categories displayed in the left sidebar. */
    public enum CatalogCategory {
        ALL("ALL", new String[]{"All", "NirnCraft", "Nordic", "Dwemer", "Imperial", "Daedric"}),
        FAVORITES("FAVORITES", new String[]{"All", "Blocks", "Gear", "Chests", "NPCs", "Creatures"}),
        BLOCKS_ARCH("BLOCKS & ARCH", new String[]{"All", "Nordic", "Dwemer", "Imperial", "Stone", "Wood", "Roof/Stair"}),
        TERRAIN_FLORA("TERRAIN & FLORA", new String[]{"All", "Terrain", "Ores", "Flora", "Trees", "Mushrooms"}),
        FURNITURE_DECOR("FURNITURE", new String[]{"All", "Lights", "Tables/Chairs", "Banners", "Stations", "Clutter"}),
        LOOT_CHESTS("LOOT CHESTS", new String[]{"All", "Nordic", "Imperial", "Velothi", "Dwemer", "Elven", "Rustic"}),
        WEAPONS("WEAPONS", new String[]{"All", "Iron/Steel", "Dwemer/Elven", "Orcish/Ebony", "Daedric/Glass", "Ranged/Staff"}),
        APPAREL("APPAREL", new String[]{"All", "Heavy", "Light", "Shields", "Jewelry", "Robes/Cloaks"}),
        POTIONS_FOOD("POTIONS & FOOD", new String[]{"All", "Potions", "Poisons", "Food", "Drinks"}),
        BOOKS_LORE("BOOKS & LORE", new String[]{"All", "Spell Tomes", "Skill Books", "Lore Books", "Notes/Maps"}),
        INGREDIENTS_MISC("INGR & MISC", new String[]{"All", "Ingredients", "Ingots/Gems", "Soul Gems", "Tools/Keys", "Misc"}),
        NPCS_RACES("NPCS & RACES", new String[]{"All", "Townsfolk", "Merchants", "Soldiers", "Mages", "Outlaws"}),
        ENEMIES_CREATURES("CREATURES", new String[]{"All", "Undead", "Dragons/Boss", "Automatons", "Beasts", "Humanoids"});

        public static final CatalogCategory[] VALUES = values();
        public final String title;
        public final String[] subFilters;

        CatalogCategory(String title, String[] subFilters) {
            this.title = title;
            this.subFilters = subFilters;
        }
    }

    /** Kind of catalog entry. */
    public enum EntryKind {
        ITEM_OR_BLOCK,
        LOOT_CHEST,
        NPC,
        ENTITY
    }

    /** Immutable catalog row built lazily when the Creative screen is opened. */
    public static final class CatalogEntry {
        public final String key;
        public final EntryKind kind;
        public final CatalogCategory primaryCategory;
        public final String name;
        public final String nameLower;
        public final String registryId;
        public final String namespace;
        public final String typeLabel;
        public final String searchBlob;
        public final ItemStack icon;
        public final float weight;
        public final int value;
        public final double damage;
        public final double armor;

        // Optional specialization payloads
        @Nullable public final ContainerKind containerKind;
        @Nullable public final ContainerStyle containerStyle;
        @Nullable public final String lootTable;
        @Nullable public final NpcRole npcRole;
        @Nullable public final NpcNames.Race npcRace;
        @Nullable public final EntityType<?> entityType;

        CatalogEntry(
                String key,
                EntryKind kind,
                CatalogCategory primaryCategory,
                String name,
                String registryId,
                String namespace,
                String typeLabel,
                String searchBlob,
                ItemStack icon,
                float weight,
                int value,
                double damage,
                double armor,
                @Nullable ContainerKind containerKind,
                @Nullable ContainerStyle containerStyle,
                @Nullable String lootTable,
                @Nullable NpcRole npcRole,
                @Nullable NpcNames.Race npcRace,
                @Nullable EntityType<?> entityType) {
            this.key = key;
            this.kind = kind;
            this.primaryCategory = primaryCategory;
            this.name = name;
            this.nameLower = name.toLowerCase(Locale.ROOT);
            this.registryId = registryId;
            this.namespace = namespace;
            this.typeLabel = typeLabel;
            this.searchBlob = searchBlob.toLowerCase(Locale.ROOT);
            this.icon = icon;
            this.weight = weight;
            this.value = value;
            this.damage = damage;
            this.armor = armor;
            this.containerKind = containerKind;
            this.containerStyle = containerStyle;
            this.lootTable = lootTable;
            this.npcRole = npcRole;
            this.npcRace = npcRace;
            this.entityType = entityType;
        }
    }

    // Lazily populated catalog cache (only allocated when SkyrimCreativeScreen is opened)
    @Nullable
    private static List<CatalogEntry> lazyCatalog = null;

    // Persistent session state across screen openings
    private static CatalogCategory lastCategory = CatalogCategory.ALL;
    private static String lastSearchQuery = "";
    private static int lastSourceFilter = SOURCE_ALL;
    private static int lastSubFilterIdx = 0;
    private static int sortMode = SORT_NAME;
    private static boolean sortDescending = false;
    private static int selectedQty = 1;

    // Interactive configurator state for Loot Chests
    private static ContainerKind cfgContainerKind = ContainerKind.CHEST;
    private static ContainerStyle cfgContainerStyle = ContainerStyle.NORDIC;
    private static int cfgLootTableIdx = 0;
    private static int cfgLootLevel = 1;

    // Interactive configurator state for NPCs & Entities
    private static NpcNames.Race cfgNpcRace = NpcNames.Race.NORD;
    private static NpcRole cfgNpcRole = NpcRole.CITIZEN;
    private static boolean cfgNpcFemale = false;
    private static int cfgBehaviorMode = 0; // 0 = Default AI, 1 = Passive, 2 = Hostile

    @Nullable
    private final Screen parent;

    // Current screen state
    private CatalogCategory category = lastCategory;
    private String searchQuery = lastSearchQuery;
    private boolean searchFocused = false;
    private int sourceFilter = lastSourceFilter;
    private int subFilterIdx = lastSubFilterIdx;

    private final int[] categoryCounts = new int[CatalogCategory.VALUES.length];
    private List<CatalogEntry> shown = new ArrayList<>();
    private int selected = -1;
    @Nullable
    private CatalogEntry selectedEntry = null;
    private int listScroll = 0;
    private int catScroll = 0;

    // 3D Preview & Full-screen Inspect state
    private boolean inspectMode = false;
    private float previewYaw = -25f;
    private float inspectRotX = 15f;
    private float inspectRotY = 0f;
    private float inspectZoom = 1.0f;
    private long inspectLastFrame = 0L;
    private long inspectLastDrag = 0L;
    private boolean draggingPreview = false;
    private double lastDragX = 0.0;

    // Cached preview entity (created lazily on client for the currently selected NPC/creature configuration)
    @Nullable
    private LivingEntity cachedPreviewEntity = null;
    private String cachedPreviewEntityKey = "";

    // Layout coordinates
    private int x0, contentW, topY, bottomY;
    private int catX, catW, listX, listW, detX, detW;
    private int listTopY, listBottomY;

    // Dynamic clickable regions populated during extractRenderState
    private record ClickRect(int x, int y, int w, int h, Runnable action, @Nullable Component tooltip) {
        boolean contains(double mx, double my) {
            return SkyUi.inside(mx, my, x, y, x + w, y + h);
        }
    }

    private final List<ClickRect> clickZones = new ArrayList<>();
    private int hoverCat = -1;
    private int hoverRow = -1;
    private int hoverSort = -1;
    private boolean hoverSearchBox = false;
    private boolean hoverClearSearch = false;
    private boolean hoverPreviewBox = false;
    @Nullable
    private Component hoverTooltip = null;
    private long lastRowClickMs = 0L;
    private int lastClickedRow = -1;

    public SkyrimCreativeScreen() {
        this(null);
    }

    public SkyrimCreativeScreen(@Nullable Screen parent) {
        super(Component.literal("NirnCraft Creative Catalog"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        ensureCatalogBuilt();

        contentW = Math.min(width - 2 * MARGIN, 940);
        x0 = (width - contentW) / 2;
        topY = TOP_BAR_H + 4;
        bottomY = height - BOTTOM_BAR_H - 3;

        catW = Mth.clamp((int) (contentW * 0.20f), 78, 160);
        catX = x0;

        detW = Mth.clamp((int) (contentW * 0.34f), 132, 265);
        listW = Math.max(130, contentW - catW - detW - 10);
        listX = catX + catW + 5;
        detX = listX + listW + 5;
        detW = x0 + contentW - detX;

        listTopY = topY + FILTER_BAR_H + COL_HEADER_H;
        listBottomY = bottomY - 2;

        rebuildShownList();
    }

    @Override
    public void removed() {
        super.removed();
        lastCategory = category;
        lastSearchQuery = searchQuery;
        lastSourceFilter = sourceFilter;
        lastSubFilterIdx = subFilterIdx;
        cachedPreviewEntity = null;
        cachedPreviewEntityKey = "";
    }

    // ------------------------------------------------------------------ Lazy Catalog Construction

    private static synchronized void ensureCatalogBuilt() {
        if (lazyCatalog != null && !lazyCatalog.isEmpty()) return;
        List<CatalogEntry> entries = new ArrayList<>(2048);

        // 1. Loot Chest entries (ContainerKind x ContainerStyle + default presets)
        for (int ti = 0; ti < LOOT_TABLES.length; ti++) {
            LootTablePreset preset = LOOT_TABLES[ti];
            ContainerKind defaultKind = ContainerKind.forPlacement(preset.id(), null, ContainerStyle.NORDIC);
            for (ContainerStyle style : ContainerStyle.VALUES) {
                if (!defaultKind.styled && style != ContainerStyle.NORDIC) continue;
                ItemStack icon = createStyledContainerStack(defaultKind, style);
                String styleTitle = capitalize(style.id());
                String kindTitle = formatIdentifierWords(defaultKind.id);
                String displayName = defaultKind.styled
                        ? styleTitle + " " + kindTitle + " (" + preset.label() + ")"
                        : kindTitle + " (" + preset.label() + ")";
                String key = "loot_chest:" + defaultKind.id + ":" + style.id() + ":" + preset.id();
                String blob = displayName + " " + defaultKind.id + " " + style.id() + " " + preset.id()
                        + " " + preset.themeTag() + " chest container loot hoard";
                entries.add(new CatalogEntry(
                        key,
                        EntryKind.LOOT_CHEST,
                        CatalogCategory.LOOT_CHESTS,
                        displayName,
                        preset.id(),
                        NirnCraft.MODID,
                        styleTitle + " / " + preset.themeTag(),
                        blob,
                        icon,
                        defaultKind.stashKg,
                        250 + (LOOT_TABLES.length - ti) * 50,
                        0.0,
                        0.0,
                        defaultKind,
                        style,
                        preset.id(),
                        null,
                        null,
                        null));
            }
        }
        // Also ensure every ContainerKind x ContainerStyle pair is directly selectable
        for (ContainerKind kind : ContainerKind.VALUES) {
            for (ContainerStyle style : ContainerStyle.VALUES) {
                if (!kind.styled && style != ContainerStyle.NORDIC) continue;
                LootTablePreset preset = defaultPresetForKind(kind);
                String key = "loot_chest_kind:" + kind.id + ":" + style.id();
                ItemStack icon = createStyledContainerStack(kind, style);
                String styleTitle = capitalize(style.id());
                String kindTitle = formatIdentifierWords(kind.id);
                String displayName = kind.styled ? styleTitle + " " + kindTitle : kindTitle;
                String blob = displayName + " " + kind.id + " " + style.id() + " " + preset.id() + " container loot chest";
                entries.add(new CatalogEntry(
                        key,
                        EntryKind.LOOT_CHEST,
                        CatalogCategory.LOOT_CHESTS,
                        displayName,
                        NirnCraft.MODID + ":" + kind.id,
                        NirnCraft.MODID,
                        kind.styled ? styleTitle + " Container" : "Loot Container",
                        blob,
                        icon,
                        kind.stashKg,
                        150,
                        0.0,
                        0.0,
                        kind,
                        style,
                        preset.id(),
                        null,
                        null,
                        null));
            }
        }

        // 2. NPCs & Playable/Tamriel Races (All NpcRole.VALUES and NpcNames.Race.VALUES combinations)
        ItemStack npcEggIcon = new ItemStack(NpcEntities.NPC_EGG.get());
        ItemStack fighterEggIcon = new ItemStack(NpcEntities.NPC_FIGHTER_EGG.get());
        RandomSource rng = RandomSource.create(42L);
        for (NpcRole role : NpcRole.VALUES) {
            NpcNames.Race defaultRace = NpcNames.raceFor(role, rng);
            String roleTitle = formatIdentifierWords(role.id);
            String subGroup = roleSubGroup(role);
            ItemStack icon = roleWeaponIcon(role, role.civilian ? npcEggIcon : fighterEggIcon);
            String key = "npc_role:" + role.id;
            String blob = roleTitle + " " + role.id + " " + defaultRace.name() + " " + subGroup
                    + (role.faction != null ? " " + role.faction : "") + " npc society person";
            entries.add(new CatalogEntry(
                    key,
                    EntryKind.NPC,
                    CatalogCategory.NPCS_RACES,
                    roleTitle + " (" + capitalize(defaultRace.name()) + ")",
                    NirnCraft.MODID + ":npc/" + role.id,
                    NirnCraft.MODID,
                    subGroup,
                    blob,
                    icon,
                    0f,
                    role.villain ? 150 : 100,
                    role.combatant ? 8.0 : 1.0,
                    0.0,
                    null,
                    null,
                    null,
                    role,
                    defaultRace,
                    NpcEntities.typeFor(role)));
        }
        for (NpcNames.Race race : NpcNames.Race.VALUES) {
            String raceTitle = formatIdentifierWords(race.name());
            for (NpcRole role : new NpcRole[]{NpcRole.CITIZEN, NpcRole.ADVENTURER, NpcRole.MAGE, NpcRole.MERCHANT, NpcRole.HUNTER}) {
                String roleTitle = formatIdentifierWords(role.id);
                String subGroup = roleSubGroup(role);
                ItemStack icon = roleWeaponIcon(role, npcEggIcon);
                String key = "npc_race:" + race.name().toLowerCase(Locale.ROOT) + ":" + role.id;
                String blob = raceTitle + " " + roleTitle + " " + race.name() + " " + role.id + " " + subGroup + " npc race tamriel";
                entries.add(new CatalogEntry(
                        key,
                        EntryKind.NPC,
                        CatalogCategory.NPCS_RACES,
                        raceTitle + " " + roleTitle,
                        NirnCraft.MODID + ":npc/" + race.name().toLowerCase(Locale.ROOT) + "_" + role.id,
                        NirnCraft.MODID,
                        raceTitle + " / " + subGroup,
                        blob,
                        icon,
                        0f,
                        100,
                        role.combatant ? 6.0 : 1.0,
                        0.0,
                        null,
                        null,
                        null,
                        role,
                        race,
                        NpcEntities.typeFor(role)));
            }
        }

        // 3. Enemies & Creatures from BuiltInRegistries.ENTITY_TYPE
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            if (type == NpcEntities.NPC.get() || type == NpcEntities.NPC_FIGHTER.get()) continue;
            if (!isSpawnableCreatureOrEnemy(type, id)) continue;

            String idStr = id.toString();
            String ns = id.getNamespace();
            String name = type.getDescription().getString();
            if (name == null || name.isBlank() || name.startsWith("entity.")) {
                name = formatIdentifierWords(id.getPath());
            }
            String subGroup = classifyEntitySubGroup(type, id.getPath(), name);
            ItemStack icon = iconForEntityType(type, id.getPath());
            String blob = name + " " + idStr + " @" + ns + " #" + subGroup.toLowerCase(Locale.ROOT) + " creature enemy monster entity";
            entries.add(new CatalogEntry(
                    "entity:" + idStr,
                    EntryKind.ENTITY,
                    CatalogCategory.ENEMIES_CREATURES,
                    name,
                    idStr,
                    ns,
                    subGroup,
                    blob,
                    icon,
                    0f,
                    NirnCraft.MODID.equals(ns) ? 200 : 80,
                    0.0,
                    0.0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    type));
        }

        // 4. All Registered Items & Blocks from BuiltInRegistries.ITEM
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            String idStr = id.toString();
            String ns = id.getNamespace();
            String path = id.getPath();

            ItemStack stack = new ItemStack(item);
            String name = stack.getHoverName().getString();
            if (name == null || name.isBlank()) {
                name = formatIdentifierWords(path);
            }

            CatalogCategory cat = classifyItemCategory(stack, item, path);
            String subTag = classifyItemSubTag(cat, stack, item, path, name);
            float weight = ItemWeights.get(stack);
            int value = ItemValues.get(stack);
            double dmg = computeAttackDamage(stack);
            double arm = EquipmentData.defense(stack);

            StringBuilder tagBlob = new StringBuilder(128);
            tagBlob.append(name).append(' ').append(idStr).append(" @").append(ns)
                    .append(' ').append(path.replace('_', ' '))
                    .append(" #").append(subTag.toLowerCase(Locale.ROOT))
                    .append(" #").append(cat.name().toLowerCase(Locale.ROOT));
            BuiltInRegistries.ITEM.wrapAsHolder(item).tags().forEach(t -> tagBlob.append(" #").append(t.location().getPath()));

            entries.add(new CatalogEntry(
                    "item:" + idStr,
                    EntryKind.ITEM_OR_BLOCK,
                    cat,
                    name,
                    idStr,
                    ns,
                    subTag,
                    tagBlob.toString(),
                    stack,
                    weight,
                    value,
                    dmg,
                    arm,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }

        lazyCatalog = entries;
    }

    private static ItemStack createStyledContainerStack(ContainerKind kind, ContainerStyle style) {
        Item item = ContainerRegistry.ITEM.get(kind).get();
        ItemStack stack = new ItemStack(item, 1);
        if (kind.styled) {
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(LootContainerBlock.STYLE, style));
        }
        return stack;
    }

    private static LootTablePreset defaultPresetForKind(ContainerKind kind) {
        String match = switch (kind) {
            case GRAND_CHEST -> "nirncraft:containers/dungeon/boss";
            case STRONGBOX -> "nirncraft:containers/strongbox/normal";
            case BARREL -> "nirncraft:containers/barrel/food";
            case SACK -> "nirncraft:containers/camp/bandit";
            case URN -> "nirncraft:containers/urn/burial";
            case BOOKSHELF -> "nirncraft:containers/bookshelf/common";
            case REMAINS -> "nirncraft:containers/remains/skeleton";
            case CHEST -> "nirncraft:containers/dungeon/major";
        };
        for (LootTablePreset p : LOOT_TABLES) {
            if (p.id().equals(match)) return p;
        }
        return LOOT_TABLES[0];
    }

    private static String roleSubGroup(NpcRole role) {
        if (role == NpcRole.BLACKSMITH || role == NpcRole.MERCHANT || role == NpcRole.APOTHECARY
                || role == NpcRole.INNKEEPER || role == NpcRole.CARAVAN_TRADER || role == NpcRole.TRAINER) {
            return "Merchants";
        }
        if (role == NpcRole.IMPERIAL_SOLDIER || role == NpcRole.STORMCLOAK_SOLDIER
                || role == NpcRole.THALMOR || role == NpcRole.HOUSECARL || role == NpcRole.JARL || role == NpcRole.STEWARD) {
            return "Soldiers";
        }
        if (role == NpcRole.MAGE || role == NpcRole.PRIEST || role == NpcRole.SCHOLAR) {
            return "Mages";
        }
        if (role.villain || role == NpcRole.THIEF) {
            return "Outlaws";
        }
        return "Townsfolk";
    }

    private static ItemStack roleWeaponIcon(NpcRole role, ItemStack fallback) {
        return switch (role) {
            case HUNTER -> new ItemStack(Items.BOW);
            case MINER -> new ItemStack(Items.IRON_PICKAXE);
            case LUMBERJACK -> new ItemStack(Items.IRON_AXE);
            case BLACKSMITH -> new ItemStack(Items.ANVIL);
            case APOTHECARY -> new ItemStack(Items.POTION);
            case MAGE, NECROMANCER -> new ItemStack(Items.BLAZE_ROD);
            case SCHOLAR, PRIEST -> new ItemStack(Items.BOOK);
            case JARL -> new ItemStack(Items.GOLDEN_SWORD);
            case IMPERIAL_SOLDIER, STORMCLOAK_SOLDIER, HOUSECARL, ADVENTURER -> new ItemStack(Items.IRON_SWORD);
            case THALMOR -> new ItemStack(Items.GOLDEN_HELMET);
            case FORSWORN, ASSASSIN, THUG, VAMPIRE -> new ItemStack(Items.STONE_SWORD);
            default -> fallback;
        };
    }

    private static boolean isSpawnableCreatureOrEnemy(EntityType<?> type, Identifier id) {
        String path = id.getPath();
        if (path.contains("projectile") || path.contains("arrow") || path.contains("bolt")
                || path.contains("ball") || path.contains("item") || path.contains("xp")
                || path.contains("painting") || path.contains("marker") || path.contains("display")
                || path.contains("boat") || path.contains("minecart") || path.contains("TNT")
                || path.contains("tnt") || path.contains("falling") || path.contains("Lightning")
                || path.contains("lightning") || path.contains("area_effect") || path.contains("leash")
                || path.contains("fishing") || path.contains("pearl") || path.contains("eye")
                || path.contains("crystal") || path.contains("stand") || path.contains("corpse")
                || path.contains("seat") || path.contains("chair") || path.contains("effect")
                || path.contains("beam") || path.contains("wave") || path.contains("cloud")
                || path.equals("player")) {
            return false;
        }
        MobCategory cat = type.getCategory();
        if (cat != MobCategory.MISC) return true;
        return NirnCraft.MODID.equals(id.getNamespace());
    }

    private static String classifyEntitySubGroup(EntityType<?> type, String path, String name) {
        String p = (path + " " + name).toLowerCase(Locale.ROOT);
        if (p.contains("draugr") || p.contains("skeleton") || p.contains("zombie") || p.contains("vampire")
                || p.contains("wraith") || p.contains("ghost") || p.contains("shade") || p.contains("lich")
                || p.contains("boneman") || p.contains("undead") || p.contains("phantom") || p.contains("wither")) {
            return "Undead";
        }
        if (p.contains("dragon") || p.contains("alduin") || p.contains("priest") || p.contains("giant")
                || p.contains("lurker") || p.contains("centurion") || p.contains("warden") || p.contains("boss")
                || p.contains("wispmother") || p.contains("hagraven") || p.contains("atronach") || p.contains("dremora")) {
            return "Dragons/Boss";
        }
        if (p.contains("dwemer") || p.contains("dwarven") || p.contains("sphere") || p.contains("spider_worker")
                || p.contains("ballista") || p.contains("automaton") || p.contains("golem")) {
            return "Automatons";
        }
        if (p.contains("bandit") || p.contains("forsworn") || p.contains("falmer") || p.contains("thalmor")
                || p.contains("soldier") || p.contains("guard") || p.contains("mage") || p.contains("necromancer")
                || p.contains("cultist") || p.contains("reaver") || p.contains("pillager") || p.contains("vindicator")
                || p.contains("evoker") || p.contains("witch") || p.contains("villager") || p.contains("ash_spawn")
                || p.contains("riekling")) {
            return "Humanoids";
        }
        return "Beasts";
    }

    private static ItemStack iconForEntityType(EntityType<?> type, String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.contains("dragon")) return new ItemStack(Items.DRAGON_HEAD);
        if (p.contains("draugr") || p.contains("skeleton") || p.contains("boneman")) return new ItemStack(Items.SKELETON_SKULL);
        if (p.contains("dwemer") || p.contains("dwarven") || p.contains("automaton") || p.contains("centurion")) return new ItemStack(Items.COPPER_INGOT);
        if (p.contains("vampire") || p.contains("necromancer") || p.contains("lich")) return new ItemStack(Items.WITHER_SKELETON_SKULL);
        if (p.contains("wolf") || p.contains("bear") || p.contains("sabre") || p.contains("troll") || p.contains("mammoth")) return new ItemStack(Items.LEATHER);
        return new ItemStack(NpcEntities.NPC_FIGHTER_EGG.get());
    }

    private static CatalogCategory classifyItemCategory(ItemStack stack, Item item, String path) {
        for (ContainerKind ck : ContainerKind.VALUES) {
            if (path.equals(ck.id)) return CatalogCategory.LOOT_CHESTS;
        }
        if (item instanceof BlockItem bi) {
            Block b = bi.getBlock();
            if (b instanceof LootContainerBlock) return CatalogCategory.LOOT_CHESTS;
            if (isTerrainOrFloraBlock(path)) return CatalogCategory.TERRAIN_FLORA;
            if (isFurnitureOrDecorBlock(path)) return CatalogCategory.FURNITURE_DECOR;
            return CatalogCategory.BLOCKS_ARCH;
        }
        ItemCategory econCat = ItemCategory.of(stack);
        return switch (econCat) {
            case WEAPONS -> CatalogCategory.WEAPONS;
            case APPAREL -> CatalogCategory.APPAREL;
            case POTIONS, FOOD -> CatalogCategory.POTIONS_FOOD;
            case BOOKS -> CatalogCategory.BOOKS_LORE;
            case INGREDIENTS, MISC, ALL -> CatalogCategory.INGREDIENTS_MISC;
        };
    }

    private static boolean isTerrainOrFloraBlock(String p) {
        return p.contains("dirt") || p.contains("grass") || p.contains("turf") || p.contains("soil")
                || p.contains("mud") || p.contains("sand") || p.contains("gravel") || p.contains("clay")
                || p.contains("snow") || p.contains("ice") || p.contains("ash") || p.contains("peat")
                || p.contains("moss") || p.contains("lichen") || p.contains("flower") || p.contains("plant")
                || p.contains("fern") || p.contains("shrub") || p.contains("bush") || p.contains("ivy")
                || p.contains("vine") || p.contains("leaf") || p.contains("leaves") || p.contains("sapling")
                || p.contains("mushroom") || p.contains("fungus") || p.contains("cap") || p.contains("spore")
                || p.contains("reed") || p.contains("kelp") || p.contains("coral") || p.contains("lily")
                || p.contains("wheat") || p.contains("cabbage") || p.contains("leek") || p.contains("potato")
                || p.contains("carrot") || p.contains("nirnroot") || p.contains("thistle") || p.contains("mountain_flower")
                || p.contains("jazbay") || p.contains("juniper") || p.contains("tundra_cotton")
                || p.endsWith("_ore") || p.contains("_ore_") || p.contains("geode") || p.contains("crystal")
                || p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_bark");
    }

    private static boolean isFurnitureOrDecorBlock(String p) {
        return p.contains("table") || p.contains("chair") || p.contains("stool") || p.contains("bench")
                || p.contains("throne") || p.contains("shelf") || p.contains("cabinet") || p.contains("wardrobe")
                || p.contains("counter") || p.contains("desk") || p.contains("bed") || p.contains("cradle")
                || p.contains("banner") || p.contains("tapestry") || p.contains("rug") || p.contains("carpet")
                || p.contains("drape") || p.contains("curtain") || p.contains("flag") || p.contains("pennant")
                || p.contains("lantern") || p.contains("torch") || p.contains("brazier") || p.contains("sconce")
                || p.contains("chandelier") || p.contains("candle") || p.contains("lamp") || p.contains("campfire")
                || p.contains("firepit") || p.contains("hearth") || p.contains("forge") || p.contains("anvil")
                || p.contains("grindstone") || p.contains("workbench") || p.contains("tanning") || p.contains("smelter")
                || p.contains("alchemy") || p.contains("enchanter") || p.contains("arcane") || p.contains("cauldron")
                || p.contains("cooking") || p.contains("spit") || p.contains("oven") || p.contains("churn")
                || p.contains("loom") || p.contains("spinning") || p.contains("statue") || p.contains("shrine")
                || p.contains("altar") || p.contains("standing_stone") || p.contains("wayshrine") || p.contains("plaque")
                || p.contains("trophy") || p.contains("rack") || p.contains("mannequin") || p.contains("display")
                || p.contains("crate") || p.contains("basket") || p.contains("jug") || p.contains("vase")
                || p.contains("pot") || p.contains("goblet") || p.contains("plate") || p.contains("platter")
                || p.contains("tankard") || p.contains("bottle") || p.contains("cart") || p.contains("wagon")
                || p.contains("stockade") || p.contains("barricade") || p.contains("tent") || p.contains("bedroll")
                || p.contains("sign") || p.contains("bell") || p.contains("chain");
    }

    private static String classifyItemSubTag(CatalogCategory cat, ItemStack stack, Item item, String path, String name) {
        String p = (path + " " + name).toLowerCase(Locale.ROOT);
        return switch (cat) {
            case BLOCKS_ARCH -> {
                if (p.contains("nord") || p.contains("barrow") || p.contains("whiterun") || p.contains("windhelm")
                        || p.contains("solitude") || p.contains("riften") || p.contains("sovngarde") || p.contains("atmor")) yield "Nordic";
                if (p.contains("dwemer") || p.contains("dwarven") || p.contains("brass") || p.contains("aether")) yield "Dwemer";
                if (p.contains("imperial") || p.contains("cyrod") || p.contains("colov") || p.contains("niben")
                        || p.contains("white_gold") || p.contains("breton") || p.contains("wayrest")) yield "Imperial";
                if (p.contains("stair") || p.contains("slab") || p.contains("roof") || p.contains("shingle")
                        || p.contains("gable") || p.contains("arch") || p.contains("pillar") || p.contains("column")
                        || p.contains("wall") || p.contains("fence") || p.contains("door") || p.contains("gate")
                        || p.contains("window") || p.contains("trapdoor")) yield "Roof/Stair";
                if (p.contains("plank") || p.contains("timber") || p.contains("beam") || p.contains("wood")
                        || p.contains("pine") || p.contains("spruce") || p.contains("oak") || p.contains("birch")
                        || p.contains("thatch")) yield "Wood";
                yield "Stone";
            }
            case TERRAIN_FLORA -> {
                if (p.contains("ore") || p.contains("geode") || p.contains("crystal") || p.contains("corundum")
                        || p.contains("orichalcum") || p.contains("moonstone") || p.contains("malachite")
                        || p.contains("quicksilver") || p.contains("ebony")) yield "Ores";
                if (p.contains("mushroom") || p.contains("fungus") || p.contains("cap") || p.contains("spore")
                        || p.contains("namira") || p.contains("imp_stool") || p.contains("blisterwort") || p.contains("mora")) yield "Mushrooms";
                if (p.contains("log") || p.contains("wood") || p.contains("bark") || p.contains("leaves")
                        || p.contains("leaf") || p.contains("sapling") || p.contains("branch")) yield "Trees";
                if (p.contains("flower") || p.contains("fern") || p.contains("plant") || p.contains("shrub")
                        || p.contains("bush") || p.contains("nirnroot") || p.contains("vine") || p.contains("ivy")
                        || p.contains("moss") || p.contains("reed") || p.contains("lily") || p.contains("kelp")
                        || p.contains("thistle") || p.contains("jazbay") || p.contains("juniper")) yield "Flora";
                yield "Terrain";
            }
            case FURNITURE_DECOR -> {
                if (p.contains("lantern") || p.contains("torch") || p.contains("brazier") || p.contains("sconce")
                        || p.contains("chandelier") || p.contains("candle") || p.contains("lamp") || p.contains("fire")
                        || p.contains("hearth")) yield "Lights";
                if (p.contains("table") || p.contains("chair") || p.contains("stool") || p.contains("bench")
                        || p.contains("throne") || p.contains("desk") || p.contains("bed") || p.contains("shelf")
                        || p.contains("cabinet") || p.contains("counter")) yield "Tables/Chairs";
                if (p.contains("banner") || p.contains("tapestry") || p.contains("rug") || p.contains("carpet")
                        || p.contains("flag") || p.contains("drape") || p.contains("curtain")) yield "Banners";
                if (p.contains("forge") || p.contains("anvil") || p.contains("grindstone") || p.contains("workbench")
                        || p.contains("tanning") || p.contains("smelter") || p.contains("alchemy") || p.contains("enchanter")
                        || p.contains("arcane") || p.contains("cauldron") || p.contains("cooking") || p.contains("oven")
                        || p.contains("loom")) yield "Stations";
                yield "Clutter";
            }
            case WEAPONS -> {
                WeaponClass wc = WeaponClass.of(stack);
                if (wc == WeaponClass.BOW || item instanceof CrossbowItem
                        || item instanceof StaffItem || item instanceof EnchantedStaffItem
                        || p.contains("bow") || p.contains("staff") || p.contains("arrow") || p.contains("bolt")) {
                    yield "Ranged/Staff";
                }
                if (p.contains("daedric") || p.contains("dragon") || p.contains("glass") || p.contains("stalhrim")
                        || p.contains("netherite") || p.contains("diamond")) yield "Daedric/Glass";
                if (p.contains("orcish") || p.contains("ebony") || p.contains("nordic_carved") || p.contains("blades")
                        || p.contains("akaviri")) yield "Orcish/Ebony";
                if (p.contains("dwemer") || p.contains("dwarven") || p.contains("elven") || p.contains("falmer")
                        || p.contains("forsworn") || p.contains("chitin") || p.contains("bonemold")) yield "Dwemer/Elven";
                yield "Iron/Steel";
            }
            case APPAREL -> {
                if (ItemCategory.isJewelry(stack)) yield "Jewelry";
                if (item instanceof ShieldItem || p.contains("shield")) yield "Shields";
                if (p.contains("robe") || p.contains("hood") || p.contains("cloak") || p.contains("cape")
                        || p.contains("tunic") || p.contains("clothes")) yield "Robes/Cloaks";
                if (EquipmentData.isArmor(stack)) {
                    yield ArmorClass.isHeavy(stack) ? "Heavy" : "Light";
                }
                yield "Light";
            }
            case POTIONS_FOOD -> {
                if (p.contains("poison") || p.contains("venom") || p.contains("ravage") || p.contains("damage_")) yield "Poisons";
                if (item instanceof PotionItem || p.contains("potion") || p.contains("elixir")
                        || p.contains("philter") || p.contains("draught")) yield "Potions";
                if (p.contains("mead") || p.contains("ale") || p.contains("wine") || p.contains("brandy")
                        || p.contains("skooma") || p.contains("milk") || p.contains("stew") || p.contains("soup")) yield "Drinks";
                yield "Food";
            }
            case BOOKS_LORE -> {
                if (stack.is(ItemCategory.SPELL_TOMES) || p.contains("spell_tome") || p.contains("tome_")) yield "Spell Tomes";
                if (stack.is(ItemCategory.SKILL_BOOKS) || p.contains("skill_book")) yield "Skill Books";
                if (p.contains("note") || p.contains("letter") || p.contains("bounty") || p.contains("journal")
                        || p.contains("map") || p.contains("scroll") || p.contains("recipe")) yield "Notes/Maps";
                yield "Lore Books";
            }
            case INGREDIENTS_MISC -> {
                if (stack.is(ItemCategory.INGREDIENTS_TAG)
                        || com.nirncraft.crafting.arcane.alchemy.Ingredients.isIngredient(stack)) yield "Ingredients";
                if (p.contains("soul_gem") || p.contains("azura")) yield "Soul Gems";
                if (p.contains("ingot") || p.contains("ore") || p.contains("gem") || p.contains("ruby")
                        || p.contains("sapphire") || p.contains("emerald") || p.contains("diamond")
                        || p.contains("amethyst") || p.contains("garnet") || p.contains("leather")
                        || p.contains("pelt") || p.contains("hide") || p.contains("chitin") || p.contains("scale")
                        || p.contains("bone")) yield "Ingots/Gems";
                if (p.contains("pickaxe") || p.contains("shovel") || p.contains("hoe") || p.contains("lockpick")
                        || p.contains("key") || p.contains("shears") || p.contains("flint") || p.contains("fishing")) yield "Tools/Keys";
                yield "Misc";
            }
            default -> "General";
        };
    }

    private static double computeAttackDamage(ItemStack stack) {
        double bonus = ItemValues.attackDamage(stack.getItem());
        return bonus > 0 ? 1.0 + bonus : 0.0;
    }

    // ------------------------------------------------------------------ Filtering, Search & Sorting

    private void rebuildShownList() {
        ensureCatalogBuilt();
        if (lazyCatalog == null) return;

        CatalogEntry prevSelected = selectedEntry;
        for (int i = 0; i < categoryCounts.length; i++) categoryCounts[i] = 0;

        String rawQuery = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        String[] tokens = rawQuery.isEmpty() ? new String[0] : rawQuery.split("\\s+");

        String activeSubFilter = currentSubFilterLabel();
        boolean filterSub = activeSubFilter != null && !"All".equalsIgnoreCase(activeSubFilter);

        List<CatalogEntry> filtered = new ArrayList<>();
        for (CatalogEntry e : lazyCatalog) {
            if (sourceFilter == SOURCE_NIRNCRAFT && !NirnCraft.MODID.equals(e.namespace)) continue;
            if (sourceFilter == SOURCE_VANILLA && !"minecraft".equals(e.namespace)) continue;
            if (!matchesTokens(e, tokens)) continue;

            categoryCounts[CatalogCategory.ALL.ordinal()]++;
            categoryCounts[e.primaryCategory.ordinal()]++;
            if (DevTestState.isFavorite(e.key)) {
                categoryCounts[CatalogCategory.FAVORITES.ordinal()]++;
            }

            if (!matchesCategory(e, category)) continue;
            if (filterSub && !matchesSubFilter(e, category, activeSubFilter)) continue;

            filtered.add(e);
        }

        filtered.sort(entryComparator(rawQuery));
        shown = filtered;

        int nextIdx = -1;
        if (prevSelected != null) {
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).key.equals(prevSelected.key)) {
                    nextIdx = i;
                    break;
                }
            }
        }
        if (nextIdx < 0 && !shown.isEmpty()) {
            nextIdx = Mth.clamp(selected, 0, shown.size() - 1);
        }
        selectIndex(nextIdx, false);
        clampScroll();
    }

    private static boolean matchesCategory(CatalogEntry e, CatalogCategory cat) {
        return switch (cat) {
            case ALL -> true;
            case FAVORITES -> DevTestState.isFavorite(e.key);
            default -> e.primaryCategory == cat;
        };
    }

    private static boolean matchesSubFilter(CatalogEntry e, CatalogCategory cat, String sub) {
        if (cat == CatalogCategory.ALL) {
            String s = sub.toLowerCase(Locale.ROOT);
            if ("nirncraft".equals(s)) return NirnCraft.MODID.equals(e.namespace);
            return e.searchBlob.contains(s);
        }
        if (cat == CatalogCategory.FAVORITES) {
            return switch (sub) {
                case "Blocks" -> e.primaryCategory == CatalogCategory.BLOCKS_ARCH
                        || e.primaryCategory == CatalogCategory.TERRAIN_FLORA
                        || e.primaryCategory == CatalogCategory.FURNITURE_DECOR;
                case "Gear" -> e.primaryCategory == CatalogCategory.WEAPONS
                        || e.primaryCategory == CatalogCategory.APPAREL;
                case "Chests" -> e.kind == EntryKind.LOOT_CHEST;
                case "NPCs" -> e.kind == EntryKind.NPC;
                case "Creatures" -> e.kind == EntryKind.ENTITY;
                default -> true;
            };
        }
        return e.typeLabel.equalsIgnoreCase(sub)
                || e.typeLabel.toLowerCase(Locale.ROOT).contains(sub.toLowerCase(Locale.ROOT))
                || e.searchBlob.contains(sub.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesTokens(CatalogEntry e, String[] tokens) {
        if (tokens.length == 0) return true;
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            if (tok.startsWith("@")) {
                String ns = tok.substring(1);
                if (!ns.isEmpty() && !e.namespace.contains(ns)) return false;
            } else if (tok.startsWith("#")) {
                String tag = tok.substring(1);
                if (!tag.isEmpty() && !e.searchBlob.contains(tag)) return false;
            } else {
                if (!e.nameLower.contains(tok) && !e.registryId.contains(tok) && !e.searchBlob.contains(tok)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Comparator<CatalogEntry> entryComparator(String query) {
        if (sortMode == SORT_NAME && !query.isEmpty()) {
            return (a, b) -> {
                int rankA = searchRelevanceRank(a, query);
                int rankB = searchRelevanceRank(b, query);
                if (rankA != rankB) return Integer.compare(rankA, rankB);
                int cmp = a.name.compareToIgnoreCase(b.name);
                return sortDescending ? -cmp : cmp;
            };
        }
        Comparator<CatalogEntry> base = switch (sortMode) {
            case SORT_TAG -> Comparator.comparing((CatalogEntry e) -> e.typeLabel, String.CASE_INSENSITIVE_ORDER);
            case SORT_WEIGHT -> Comparator.comparingDouble(e -> e.weight);
            case SORT_VALUE -> Comparator.comparingInt(e -> e.value);
            default -> Comparator.comparing(
                    (CatalogEntry e) -> NirnCraft.MODID.equals(e.namespace) ? 0 : 1)
                    .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER);
        };
        if (sortDescending) base = base.reversed();
        return base.thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER);
    }

    private static int searchRelevanceRank(CatalogEntry e, String q) {
        boolean nirn = NirnCraft.MODID.equals(e.namespace);
        if (e.nameLower.equals(q)) return nirn ? 0 : 1;
        if (e.nameLower.startsWith(q)) return nirn ? 2 : 3;
        if (e.registryId.endsWith(":" + q) || e.registryId.startsWith(q)) return nirn ? 4 : 5;
        if (e.nameLower.contains(q)) return nirn ? 6 : 7;
        return nirn ? 8 : 9;
    }

    @Nullable
    private String currentSubFilterLabel() {
        String[] subs = category.subFilters;
        if (subs == null || subs.length == 0) return null;
        int idx = Mth.clamp(subFilterIdx, 0, subs.length - 1);
        return subs[idx];
    }

    private int visibleRows() {
        return Math.max(1, (listBottomY - listTopY - 2) / ROW_H);
    }

    private void clampScroll() {
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, shown.size() - visibleRows())));
    }

    private void ensureVisible() {
        if (selected < 0) return;
        int rows = visibleRows();
        if (selected < listScroll) listScroll = selected;
        else if (selected >= listScroll + rows) listScroll = selected - rows + 1;
        clampScroll();
    }

    private void selectIndex(int idx, boolean scrollIntoView) {
        if (shown.isEmpty() || idx < 0) {
            selected = -1;
            selectedEntry = null;
            return;
        }
        selected = Mth.clamp(idx, 0, shown.size() - 1);
        CatalogEntry entry = shown.get(selected);
        selectedEntry = entry;

        if (entry.kind == EntryKind.LOOT_CHEST) {
            if (entry.containerKind != null) cfgContainerKind = entry.containerKind;
            if (entry.containerStyle != null) cfgContainerStyle = entry.containerStyle;
            if (entry.lootTable != null) {
                for (int i = 0; i < LOOT_TABLES.length; i++) {
                    if (LOOT_TABLES[i].id().equals(entry.lootTable)) {
                        cfgLootTableIdx = i;
                        break;
                    }
                }
            }
        } else if (entry.kind == EntryKind.NPC) {
            if (entry.npcRole != null) cfgNpcRole = entry.npcRole;
            if (entry.npcRace != null) cfgNpcRace = entry.npcRace;
        }

        if (scrollIntoView) {
            ensureVisible();
        }
    }

    private void setCategory(CatalogCategory next) {
        if (next == null || next == category) return;
        category = next;
        lastCategory = next;
        subFilterIdx = 0;
        lastSubFilterIdx = 0;
        selected = 0;
        listScroll = 0;
        rebuildShownList();
    }

    private void cycleCategory(int delta) {
        CatalogCategory[] all = CatalogCategory.VALUES;
        setCategory(all[Math.floorMod(category.ordinal() + delta, all.length)]);
    }

    private void setSort(int mode) {
        if (sortMode == mode) {
            sortDescending = !sortDescending;
        } else {
            sortMode = mode;
            sortDescending = mode == SORT_WEIGHT || mode == SORT_VALUE;
        }
        rebuildShownList();
    }

    // ------------------------------------------------------------------ Dual Actions (Inventory vs World)

    private void executeGiveToInventory() {
        CatalogEntry e = selectedEntry;
        if (e == null && category != CatalogCategory.LOOT_CHESTS) return;
        if (category == CatalogCategory.LOOT_CHESTS || (e != null && e.kind == EntryKind.LOOT_CHEST)) {
            ItemStack chestStack = createStyledContainerStack(cfgContainerKind, cfgContainerStyle);
            DevTestClient.sendGiveItem(chestStack, Math.max(1, selectedQty));
            return;
        }
        if (e.kind == EntryKind.ITEM_OR_BLOCK) {
            DevTestClient.sendGiveItem(e.icon, Math.max(1, selectedQty));
        } else if (e.kind == EntryKind.NPC) {
            boolean hostile = cfgBehaviorMode == 2;
            boolean passive = cfgBehaviorMode == 1;
            DevTestClient.sendSpawnNpcInFront(cfgNpcRole, cfgNpcRace, cfgNpcFemale, hostile, passive, "");
        } else if (e.kind == EntryKind.ENTITY) {
            boolean hostile = cfgBehaviorMode == 2;
            boolean passive = cfgBehaviorMode == 1;
            DevTestClient.sendSpawnEntityInFront(e.registryId, hostile, passive, "");
        }
    }

    private void executePlaceInWorldOneShot() {
        CatalogEntry e = selectedEntry;
        if (category == CatalogCategory.LOOT_CHESTS || (e != null && e.kind == EntryKind.LOOT_CHEST)) {
            LootTablePreset preset = LOOT_TABLES[Mth.clamp(cfgLootTableIdx, 0, LOOT_TABLES.length - 1)];
            String label = capitalize(cfgContainerStyle.id()) + " " + formatIdentifierWords(cfgContainerKind.id)
                    + " (" + preset.label() + ")";
            DevTestClient.armOneShotLootChest(label, cfgContainerKind, cfgContainerStyle, preset.id(), cfgLootLevel);
            return;
        }
        if (e == null) return;
        switch (e.kind) {
            case ITEM_OR_BLOCK -> DevTestClient.armOneShotItemOrBlock(e.name, e.icon);
            case NPC -> {
                boolean hostile = cfgBehaviorMode == 2;
                boolean passive = cfgBehaviorMode == 1;
                String label = formatIdentifierWords(cfgNpcRace.name()) + " " + formatIdentifierWords(cfgNpcRole.id);
                DevTestClient.armOneShotNpc(label, cfgNpcRole, cfgNpcRace, cfgNpcFemale, "", hostile, passive);
            }
            case ENTITY -> {
                boolean hostile = cfgBehaviorMode == 2;
                boolean passive = cfgBehaviorMode == 1;
                DevTestClient.armOneShotEntity(e.name, e.registryId, "", hostile, passive);
            }
            default -> {}
        }
    }

    private void toggleSelectedFavorite() {
        if (selectedEntry == null) return;
        boolean nowFav = DevTestState.toggleFavorite(selectedEntry.key);
        DevTestState.showToast(
                nowFav ? "STARRED FAVORITE" : "REMOVED FAVORITE",
                selectedEntry.name,
                1600L);
        rebuildShownList();
    }

    private void openVanillaCreative() {
        if (minecraft == null || minecraft.player == null) return;
        InventoryClientEvents.openVanillaOnce();
        try {
            if (minecraft.player.connection != null) {
                minecraft.gui.setScreen(new CreativeModeInventoryScreen(
                        minecraft.player,
                        minecraft.player.connection.enabledFeatures(),
                        true));
                return;
            }
        } catch (Throwable ignored) {
        }
        InventoryClientEvents.openVanillaOnce();
        minecraft.gui.setScreen(new InventoryScreen(minecraft.player));
    }

    // ------------------------------------------------------------------ Rendering (extractRenderState)

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        clickZones.clear();
        hoverTooltip = null;
        hoverCat = -1;
        hoverRow = -1;
        hoverSort = -1;
        hoverSearchBox = false;
        hoverClearSearch = false;
        hoverPreviewBox = false;

        if (inspectMode && selectedEntry != null) {
            renderInspectOverlay(g, mouseX, mouseY);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }

        // 1. Atmospheric Skyrim backdrop + subtle drifting smoke
        g.fillGradient(0, 0, width, height, 0xD0060608, 0xEC080706);
        long now = Util.getMillis();
        SkyUi.smoke(g, width, height, (now % 1_000_000L) / 1000f, 0.45f);

        // 2. Top Navigation Switcher Bar
        drawTopSwitcherBar(g, mouseX, mouseY);

        // 3. Left Category Sidebar
        drawCategoriesSidebar(g, mouseX, mouseY);

        // 4. Center Search, Filter Chips & Catalog List
        drawCenterCatalogPanel(g, mouseX, mouseY);

        // 5. Right 3D Preview & Dual-Action Inspector Panel
        drawRightInspectorPanel(g, mouseX, mouseY);

        // 6. Bottom Status & Key Hint Bar
        drawBottomBar(g, mouseX, mouseY);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (hoverTooltip != null) {
            g.setTooltipForNextFrame(font, hoverTooltip, mouseX, mouseY);
        }
    }

    private void drawTopSwitcherBar(GuiGraphicsExtractor g, int mx, int my) {
        g.fill(0, 0, width, TOP_BAR_H, 0xC8050507);
        SkyUi.hline(g, 0, width, TOP_BAR_H - 1, 0xA0);

        boolean compact = width < 580;
        int x = x0;
        int y = 3;
        int h = 14;

        x = drawNavPill(g, mx, my, x, y, h,
                compact ? "CATALOG" : "CREATIVE CATALOG",
                true,
                null,
                Component.literal("NirnCraft Creative & Dev Spawner Catalog"));
        x += 3;
        x = drawNavPill(g, mx, my, x, y, h,
                compact ? "INVENTORY" : "SKYRIM INVENTORY",
                false,
                () -> { if (minecraft != null) minecraft.gui.setScreen(new SkyrimInventoryScreen()); },
                Component.literal("Switch to your carried NirnCraft Skyrim Inventory"));
        x += 3;
        x = drawNavPill(g, mx, my, x, y, h,
                compact ? "HUB" : "HUB MENU",
                false,
                () -> { if (minecraft != null) minecraft.gui.setScreen(new HubScreen()); },
                Component.literal("Open the four-way Skyrim Hub Menu (Skills / Items / Magic / Map)"));
        x += 3;
        drawNavPill(g, mx, my, x, y, h,
                compact ? "VANILLA" : "VANILLA CREATIVE",
                false,
                this::openVanillaCreative,
                Component.literal("Open the standard Vanilla Creative Inventory tabs"));

        int rx = x0 + contentW;
        rx = drawNavPillRight(g, mx, my, rx, y, h,
                compact ? "CAP (=)" : "CAPTURE (=)",
                () -> DevTestClient.triggerSessionCapture(this),
                Component.literal("Capture screenshot + telemetry & open Issue Reporter (=)"));
        rx -= 3;
        rx = drawNavPillRight(g, mx, my, rx, y, h,
                "MAP (M)",
                () -> { if (minecraft != null) minecraft.gui.setScreen(new MapScreen()); },
                Component.literal("Open Tamriel World Map with click-to-teleport (M)"));
        rx -= 3;
        drawNavPillRight(g, mx, my, rx, y, h,
                compact ? "DEV (-)" : "DEV SETTINGS (-)",
                () -> { if (minecraft != null) minecraft.gui.setScreen(new DevSettingsScreen(this)); },
                Component.literal("Open Hardware Quality Ceiling, Lighting, Time & HUD Dev Menu (-)"));
    }

    private int drawNavPill(
            GuiGraphicsExtractor g, int mx, int my, int x, int y, int h,
            String label, boolean active, @Nullable Runnable onClick, @Nullable Component tooltip) {
        int w = font.width(label) + (active ? 16 : 10);
        boolean hov = SkyUi.inside(mx, my, x, y, x + w, y + h);
        int bg = active ? 0x44E8C060 : (hov ? 0x28FFFFFF : 0x14FFFFFF);
        g.fill(x, y, x + w, y + h, bg);
        SkyUi.hline(g, x, x + w, y, active ? 0xD0 : (hov ? 0x90 : 0x45));
        SkyUi.hline(g, x, x + w, y + h - 1, active ? 0xD0 : (hov ? 0x90 : 0x45));
        if (active) {
            SkyUi.diamond(g, x + 5, y + h / 2, 2, SkyUi.GOLD);
            g.text(font, label, x + 10, y + 3, SkyUi.GOLD, true);
        } else {
            g.text(font, label, x + 5, y + 3, hov ? SkyUi.BRIGHT : SkyUi.TEXT, false);
        }
        if (onClick != null) {
            clickZones.add(new ClickRect(x, y, w, h, onClick, tooltip));
        }
        if (hov && tooltip != null) hoverTooltip = tooltip;
        return x + w;
    }

    private int drawNavPillRight(
            GuiGraphicsExtractor g, int mx, int my, int rightX, int y, int h,
            String label, Runnable onClick, @Nullable Component tooltip) {
        int w = font.width(label) + 10;
        int x = rightX - w;
        drawNavPill(g, mx, my, x, y, h, label, false, onClick, tooltip);
        return x;
    }

    private void drawCategoriesSidebar(GuiGraphicsExtractor g, int mx, int my) {
        SkyUi.panel(g, catX, topY, catX + catW, bottomY);

        String header = SkyUi.ellipsize(font, "CATEGORIES", catW - 10);
        g.text(font, header, catX + 6, topY + 4, SkyUi.HEADER, false);
        SkyUi.hline(g, catX + 4, catX + catW - 4, topY + 14, 0x80);

        int startY = topY + 16;
        int availH = bottomY - startY - 3;
        int maxVisibleCats = Math.max(1, availH / CAT_ROW_H);
        CatalogCategory[] cats = CatalogCategory.VALUES;
        catScroll = Mth.clamp(catScroll, 0, Math.max(0, cats.length - maxVisibleCats));

        int y = startY;
        for (int i = catScroll; i < cats.length && i < catScroll + maxVisibleCats; i++) {
            CatalogCategory c = cats[i];
            int count = categoryCounts[c.ordinal()];
            boolean sel = c == category;
            boolean hov = SkyUi.inside(mx, my, catX + 1, y, catX + catW - 1, y + CAT_ROW_H);
            if (hov) hoverCat = c.ordinal();

            if (sel) {
                g.fill(catX + 1, y, catX + catW - 1, y + CAT_ROW_H, 0x32FFFFFF);
                g.fill(catX + 1, y, catX + 3, y + CAT_ROW_H, SkyUi.GOLD);
            } else if (hov) {
                g.fill(catX + 1, y, catX + catW - 1, y + CAT_ROW_H, 0x18FFFFFF);
            }

            String countStr = String.valueOf(count);
            int countW = font.width(countStr);
            int maxLabelW = catW - 13 - countW - (c == CatalogCategory.FAVORITES ? 7 : 0);
            String shownTitle = SkyUi.ellipsize(font, c.title, Math.max(20, maxLabelW));

            int textColor = sel ? SkyUi.BRIGHT : (count == 0 ? 0xFF5E5A52 : (hov ? SkyUi.TEXT : 0xFFB8B0A0));
            g.text(font, shownTitle, catX + 6, y + 3, textColor, sel);
            if (c == CatalogCategory.FAVORITES && catW >= 96) {
                SkyUi.star(g, catX + 6 + font.width(shownTitle) + 2, y + 4, sel ? SkyUi.GOLD : SkyUi.TRIM);
            }
            g.text(font, countStr, catX + catW - 5 - countW, y + 3, sel ? SkyUi.GOLD : 0xFF6E6A60, false);

            y += CAT_ROW_H;
        }

        if (cats.length > maxVisibleCats) {
            int trackH = availH;
            int barH = Math.max(10, trackH * maxVisibleCats / cats.length);
            int barY = startY + (trackH - barH) * catScroll / Math.max(1, cats.length - maxVisibleCats);
            g.fill(catX + catW - 2, startY, catX + catW - 1, startY + trackH, 0x28FFFFFF);
            g.fill(catX + catW - 2, barY, catX + catW - 1, barY + barH, 0xA0E8C060);
        }
    }

    private void drawCenterCatalogPanel(GuiGraphicsExtractor g, int mx, int my) {
        SkyUi.panel(g, listX, topY, listX + listW, bottomY);

        // 1. Search Box + Source Filter Pills
        int sBoxX = listX + 4;
        int sBoxY = topY + 3;
        int sourcePillsW = 0;
        for (String sl : SOURCE_LABELS) sourcePillsW += font.width(sl) + 8;
        boolean showSourcePills = listW - sourcePillsW - 14 >= 72;
        int sBoxW = showSourcePills ? listW - sourcePillsW - 12 : listW - 8;
        int sBoxH = 13;

        hoverSearchBox = SkyUi.inside(mx, my, sBoxX, sBoxY, sBoxX + sBoxW, sBoxY + sBoxH);
        g.fill(sBoxX, sBoxY, sBoxX + sBoxW, sBoxY + sBoxH, searchFocused ? 0xCC121110 : 0x9909090B);
        SkyUi.hline(g, sBoxX, sBoxX + sBoxW, sBoxY, searchFocused ? 0xE0 : 0x70);
        SkyUi.hline(g, sBoxX, sBoxX + sBoxW, sBoxY + sBoxH - 1, searchFocused ? 0xE0 : 0x70);

        String cursor = (searchFocused && (Util.getMillis() / 450L) % 2L == 0L) ? "_" : "";
        if (searchQuery.isEmpty() && !searchFocused) {
            String placeholder = SkyUi.ellipsize(font, "Search @mod, #tag, name... (/)", sBoxW - 8);
            g.text(font, placeholder, sBoxX + 4, sBoxY + 3, SkyUi.DIM, false);
        } else {
            String renderedQuery = SkyUi.ellipsize(font, searchQuery + cursor, sBoxW - 18);
            g.text(font, renderedQuery, sBoxX + 4, sBoxY + 3, SkyUi.BRIGHT, false);
        }

        if (!searchQuery.isEmpty()) {
            int cx1 = sBoxX + sBoxW - 12;
            hoverClearSearch = SkyUi.inside(mx, my, cx1, sBoxY, sBoxX + sBoxW, sBoxY + sBoxH);
            g.text(font, "X", cx1 + 3, sBoxY + 3, hoverClearSearch ? SkyUi.BAD : SkyUi.HEADER, false);
        }

        if (showSourcePills) {
            int spX = sBoxX + sBoxW + 4;
            for (int i = 0; i < SOURCE_LABELS.length; i++) {
                final int filterVal = i;
                String lbl = SOURCE_LABELS[i];
                int pw = font.width(lbl) + 6;
                if (spX + pw <= listX + listW - 2) {
                    boolean active = sourceFilter == i;
                    boolean hov = SkyUi.inside(mx, my, spX, sBoxY, spX + pw, sBoxY + sBoxH);
                    g.fill(spX, sBoxY, spX + pw, sBoxY + sBoxH, active ? 0x40E8C060 : (hov ? 0x24FFFFFF : 0x12FFFFFF));
                    SkyUi.hline(g, spX, spX + pw, sBoxY + sBoxH - 1, active ? 0xC0 : 0x40);
                    g.text(font, lbl, spX + 3, sBoxY + 3, active ? SkyUi.GOLD : (hov ? SkyUi.BRIGHT : SkyUi.DIM), false);
                    clickZones.add(new ClickRect(spX, sBoxY, pw, sBoxH, () -> {
                        sourceFilter = filterVal;
                        lastSourceFilter = filterVal;
                        listScroll = 0;
                        rebuildShownList();
                    }, Component.literal("Filter source: " + lbl)));
                    spX += pw + 2;
                }
            }
        }

        // 2. Category-specific Sub-Filter Chips Row
        int chipY = topY + 18;
        int chipH = 13;
        int chipX = listX + 4;
        String[] subs = category.subFilters;
        for (int i = 0; i < subs.length; i++) {
            final int chipIdx = i;
            String chip = subs[i];
            int cw = font.width(chip) + 6;
            if (chipX + cw > listX + listW - 3) break;
            boolean active = subFilterIdx == i;
            boolean hov = SkyUi.inside(mx, my, chipX, chipY, chipX + cw, chipY + chipH);
            g.fill(chipX, chipY, chipX + cw, chipY + chipH, active ? 0x38C8BC9A : (hov ? 0x20FFFFFF : 0x0EFFFFFF));
            if (active) {
                SkyUi.hline(g, chipX, chipX + cw, chipY, 0xA0);
                SkyUi.hline(g, chipX, chipX + cw, chipY + chipH - 1, 0xA0);
            }
            g.text(font, chip, chipX + 3, chipY + 3, active ? SkyUi.GOLD : (hov ? SkyUi.TEXT : SkyUi.DIM), false);
            clickZones.add(new ClickRect(chipX, chipY, cw, chipH, () -> {
                subFilterIdx = chipIdx;
                lastSubFilterIdx = chipIdx;
                listScroll = 0;
                rebuildShownList();
            }, null));
            chipX += cw + 2;
        }

        SkyUi.hline(g, listX + 3, listX + listW - 3, topY + FILTER_BAR_H - 1, 0x70);

        // 3. Sort Column Headers: NAME | TYPE | WT | VAL (responsive on compact widths)
        int colY = topY + FILTER_BAR_H + 2;
        boolean showTagCol = listW >= 210;
        boolean showWeightCol = listW >= 165;
        int valRight = listX + listW - 6;
        int wgtRight = showWeightCol ? valRight - 30 : valRight;
        int tagLeft = showTagCol ? Math.max(listX + 94, wgtRight - 78) : wgtRight;
        int nameX = listX + 28;

        // NAME header
        drawSortHeader(g, mx, my, SORT_NAME, "NAME", nameX, listX + 4, (showTagCol ? tagLeft : wgtRight) - 6, colY);
        if (showTagCol) {
            drawSortHeader(g, mx, my, SORT_TAG, "TYPE", tagLeft, tagLeft - 4, wgtRight - 22, colY);
        }
        if (showWeightCol) {
            int wx = wgtRight - font.width("WT");
            drawSortHeader(g, mx, my, SORT_WEIGHT, "WT", wx, wx - 6, wgtRight + 2, colY);
        }
        int vx = valRight - font.width("VAL");
        drawSortHeader(g, mx, my, SORT_VALUE, "VAL", vx, vx - 6, valRight + 2, colY);

        SkyUi.hline(g, listX + 2, listX + listW - 2, listTopY - 2, 0x90);

        // 4. Scrollable Catalog Rows
        if (shown.isEmpty()) {
            g.centeredText(font, Component.literal("No matching entries"), listX + listW / 2, listTopY + 14, SkyUi.DIM);
            return;
        }

        int rows = visibleRows();
        for (int i = 0; i < rows && listScroll + i < shown.size(); i++) {
            int idx = listScroll + i;
            CatalogEntry e = shown.get(idx);
            int ry = listTopY + i * ROW_H;
            boolean sel = idx == selected;
            boolean hov = SkyUi.inside(mx, my, listX + 1, ry, listX + listW - 4, ry + ROW_H);
            if (hov) hoverRow = idx;

            if (sel) {
                g.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, 0x34FFFFFF);
                SkyUi.hline(g, listX + 1, listX + listW - 1, ry, 0x88);
                SkyUi.hline(g, listX + 1, listX + listW - 1, ry + ROW_H - 1, 0x88);
                SkyUi.diamond(g, listX + 5, ry + ROW_H / 2, 2, SkyUi.GOLD);
            } else if (hov) {
                g.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, 0x16FFFFFF);
            }

            g.item(e.icon, listX + 10, ry);

            boolean fav = DevTestState.isFavorite(e.key);
            int nameLimitRight = showTagCol ? tagLeft - 4 : (showWeightCol ? wgtRight - 22 : valRight - 24);
            int maxNameW = Math.max(32, nameLimitRight - nameX - (fav ? 9 : 2));
            String dispName = SkyUi.ellipsize(font, e.name, maxNameW);
            int nameCol = sel ? SkyUi.BRIGHT : (NirnCraft.MODID.equals(e.namespace) ? SkyUi.TEXT : 0xFFB8B2A6);
            g.text(font, dispName, nameX, ry + 4, nameCol, false);
            if (fav) {
                SkyUi.star(g, nameX + font.width(dispName) + 2, ry + 5, SkyUi.GOLD);
            }

            if (showTagCol) {
                String tagStr = SkyUi.ellipsize(font, e.typeLabel, Math.max(20, wgtRight - tagLeft - 22));
                g.text(font, tagStr, tagLeft, ry + 4, sel ? SkyUi.HEADER : SkyUi.DIM, false);
            }
            if (showWeightCol) {
                String wStr = e.weight > 0 ? ItemWeights.format(e.weight) : "-";
                g.text(font, wStr, wgtRight - font.width(wStr), ry + 4, sel ? SkyUi.TEXT : 0xFFA09888, false);
            }
            String vStr = String.valueOf(e.value);
            g.text(font, vStr, valRight - font.width(vStr), ry + 4, sel ? SkyUi.GOLD : 0xFFC0A050, false);
        }

        if (shown.size() > rows) {
            int trackTop = listTopY;
            int trackBottom = listTopY + rows * ROW_H;
            int trackH = trackBottom - trackTop;
            int barH = Math.max(10, trackH * rows / shown.size());
            int barY = trackTop + (trackH - barH) * listScroll / Math.max(1, shown.size() - rows);
            g.fill(listX + listW - 3, trackTop, listX + listW - 1, trackBottom, 0x30FFFFFF);
            g.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, 0xC0E8C060);
        }
    }

    private void drawSortHeader(
            GuiGraphicsExtractor g, int mx, int my, int mode, String label, int textX, int hitX1, int hitX2, int y) {
        boolean hov = SkyUi.inside(mx, my, hitX1, y - 2, hitX2, y + 10);
        if (hov) hoverSort = mode;
        boolean active = sortMode == mode;
        g.text(font, label, textX, y, active ? SkyUi.GOLD : (hov ? SkyUi.BRIGHT : SkyUi.HEADER), false);
        if (active) {
            SkyUi.triangle(g, textX - 5, y + 2, !sortDescending, SkyUi.GOLD);
        }
    }

    private void drawRightInspectorPanel(GuiGraphicsExtractor g, int mx, int my) {
        SkyUi.panel(g, detX, topY, detX + detW, bottomY);
        if (detW < 70) return;

        if (category == CatalogCategory.LOOT_CHESTS
                || (selectedEntry != null && selectedEntry.kind == EntryKind.LOOT_CHEST)) {
            drawLootChestConfigurator(g, mx, my);
            return;
        }
        if (selectedEntry != null && (selectedEntry.kind == EntryKind.NPC || selectedEntry.kind == EntryKind.ENTITY)) {
            drawEntityOrNpcConfigurator(g, mx, my, selectedEntry);
            return;
        }
        drawItemOrBlockInspector(g, mx, my, selectedEntry);
    }

    private void drawItemOrBlockInspector(GuiGraphicsExtractor g, int mx, int my, @Nullable CatalogEntry e) {
        if (e == null) {
            g.centeredText(font, Component.literal("Select an entry"), detX + detW / 2, topY + 36, SkyUi.DIM);
            return;
        }

        int panelH = bottomY - topY;
        int cx = detX + detW / 2;
        int previewBoxH = Mth.clamp(panelH / 3 - 4, 38, 88);
        int py = topY + 6 + previewBoxH / 2;

        drawPreviewGlow(g, cx, py, previewBoxH / 2 + 3);
        float t = (Util.getMillis() % 1_000_000L) / 1000f;
        float spinAngle = previewYaw + (float) Math.sin(t * 0.85) * 28f;
        float scale = (e.icon.getItem() instanceof BlockItem ? 0.90f : 0.82f) * (previewBoxH / 16f) * 14f;
        SkyUi.item3d(g, e.icon, cx, py, scale, 12f, spinAngle);

        hoverPreviewBox = SkyUi.inside(mx, my, detX + 6, topY + 4, detX + detW - 6, topY + 6 + previewBoxH);
        if (hoverPreviewBox) {
            hoverTooltip = Component.literal("Click or press [X] for Full-Screen 3D Inspect  |  Drag to rotate");
        }

        // Favorite star button in top-right of inspector
        boolean isFav = DevTestState.isFavorite(e.key);
        String favLabel = detW < 155 ? (isFav ? "★" : "☆") : (isFav ? "★ FAV" : "☆ FAV (F)");
        int favW = font.width(favLabel) + 8;
        int favX = detX + detW - favW - 4;
        int favY = topY + 4;
        boolean favHov = SkyUi.inside(mx, my, favX, favY, favX + favW, favY + 12);
        g.fill(favX, favY, favX + favW, favY + 12, isFav ? 0x38E8C060 : (favHov ? 0x28FFFFFF : 0x14FFFFFF));
        g.text(font, favLabel, favX + 4, favY + 2, isFav ? SkyUi.GOLD : (favHov ? SkyUi.BRIGHT : SkyUi.HEADER), false);
        clickZones.add(new ClickRect(favX, favY, favW, 12, this::toggleSelectedFavorite,
                Component.literal("Toggle Favorite (Key: F)")));

        int y = topY + 8 + previewBoxH;
        int textW = detW - 10;

        String title = SkyUi.ellipsize(font, SkyUi.caps(Component.literal(e.name)), textW);
        g.text(font, title, cx - font.width(title) / 2, y, SkyUi.BRIGHT, true);
        y += 10;

        String idLine = SkyUi.ellipsize(font, e.registryId + " • " + e.typeLabel, textW);
        g.text(font, idLine, cx - font.width(idLine) / 2, y, SkyUi.DIM, false);
        y += 10;

        SkyUi.divider(g, cx, y, detW / 2 - 8);
        y += 4;

        // Stat cells: Damage / Armor | Weight | Value
        List<String> statHeaders = new ArrayList<>();
        List<String> statValues = new ArrayList<>();
        List<Integer> statColors = new ArrayList<>();
        if (e.armor > 0) {
            statHeaders.add("ARMOR");
            statValues.add(fmtNum(e.armor));
            statColors.add(SkyUi.BRIGHT);
        } else if (e.damage > 1) {
            statHeaders.add("DMG");
            statValues.add(fmtNum(e.damage));
            statColors.add(SkyUi.BRIGHT);
        } else {
            statHeaders.add("TYPE");
            statValues.add(e.icon.getItem() instanceof BlockItem ? "Block" : "Item");
            statColors.add(SkyUi.TEXT);
        }
        statHeaders.add("WT");
        statValues.add(ItemWeights.format(e.weight));
        statColors.add(SkyUi.BRIGHT);
        statHeaders.add("VAL");
        statValues.add(String.valueOf(e.value));
        statColors.add(SkyUi.GOLD);

        int cellW = (detW - 8) / statHeaders.size();
        for (int i = 0; i < statHeaders.size(); i++) {
            int ccx = detX + 4 + cellW * i + cellW / 2;
            g.text(font, statHeaders.get(i), ccx - font.width(statHeaders.get(i)) / 2, y + 1, SkyUi.HEADER, false);
            g.text(font, statValues.get(i), ccx - font.width(statValues.get(i)) / 2, y + 10, statColors.get(i), false);
            if (i > 0) {
                g.fill(detX + 4 + cellW * i, y + 1, detX + 5 + cellW * i, y + 18, 0x40C8BC9A);
            }
        }
        y += 20;
        SkyUi.hline(g, detX + 5, detX + detW - 5, y, 0x60);
        y += 4;

        // Bottom Dual-Action Controls
        int actionBlockTop = bottomY - 50;
        if (y < actionBlockTop - 9 && minecraft != null) {
            List<Component> tip = Screen.getTooltipFromItem(minecraft, e.icon);
            int maxLines = Math.max(0, (actionBlockTop - y) / 9);
            int drawn = 0;
            for (int i = 1; i < tip.size() && drawn < maxLines; i++) {
                String lineStr = tip.get(i).getString().trim();
                if (lineStr.isEmpty()) continue;
                for (FormattedCharSequence seq : font.split(tip.get(i), textW)) {
                    if (drawn >= maxLines) break;
                    g.text(font, seq, detX + 5, y + drawn * 9, 0xFFCFC7B2, false);
                    drawn++;
                }
            }
        }

        drawQuantitySelectorRow(g, mx, my, detX + 5, actionBlockTop, detW - 10);
        drawActionBtn(
                g, mx, my,
                detX + 5, actionBlockTop + 16, detW - 10, 14,
                "ADD " + selectedQty + "x TO INVENTORY [Enter/I]",
                true,
                this::executeGiveToInventory,
                Component.literal("Places " + selectedQty + "x " + e.name + " into your NirnCraft Inventory"));
        drawActionBtn(
                g, mx, my,
                detX + 5, actionBlockTop + 32, detW - 10, 14,
                "PLACE IN WORLD (ONE-SHOT) [P]",
                false,
                this::executePlaceInWorldOneShot,
                Component.literal("Puts " + e.name + " in hand, closes menu, and clears hand as soon as you place it in the world"));
    }

    private void drawLootChestConfigurator(GuiGraphicsExtractor g, int mx, int my) {
        int panelH = bottomY - topY;
        int cx = detX + detW / 2;
        int previewH = Mth.clamp(panelH / 4 - 4, 34, 66);
        int py = topY + 5 + previewH / 2;

        ItemStack previewStack = createStyledContainerStack(cfgContainerKind, cfgContainerStyle);
        drawPreviewGlow(g, cx, py, previewH / 2 + 3);
        float t = (Util.getMillis() % 1_000_000L) / 1000f;
        float spinAngle = previewYaw + (float) Math.sin(t * 0.85) * 28f;
        SkyUi.item3d(g, previewStack, cx, py, (previewH / 16f) * 13.5f, 14f, spinAngle);

        int y = topY + 7 + previewH;
        LootTablePreset preset = LOOT_TABLES[Mth.clamp(cfgLootTableIdx, 0, LOOT_TABLES.length - 1)];
        String title = SkyUi.caps(Component.literal(
                capitalize(cfgContainerStyle.id()) + " " + formatIdentifierWords(cfgContainerKind.id)));
        String fitTitle = SkyUi.ellipsize(font, title, detW - 10);
        g.text(font, fitTitle, cx - font.width(fitTitle) / 2, y, SkyUi.GOLD, true);
        y += 10;
        String sub = SkyUi.ellipsize(font, preset.id(), detW - 10);
        g.text(font, sub, cx - font.width(sub) / 2, y, SkyUi.DIM, false);
        y += 10;
        SkyUi.divider(g, cx, y, detW / 2 - 8);
        y += 4;

        y = drawCycleSelectorRow(
                g, mx, my, y,
                "Container",
                formatIdentifierWords(cfgContainerKind.id),
                () -> {
                    int idx = Math.floorMod(cfgContainerKind.ordinal() - 1, ContainerKind.VALUES.length);
                    cfgContainerKind = ContainerKind.VALUES[idx];
                },
                () -> {
                    int idx = Math.floorMod(cfgContainerKind.ordinal() + 1, ContainerKind.VALUES.length);
                    cfgContainerKind = ContainerKind.VALUES[idx];
                });

        y = drawCycleSelectorRow(
                g, mx, my, y,
                "Culture",
                cfgContainerKind.styled ? capitalize(cfgContainerStyle.id()) : "Fixed",
                () -> {
                    int idx = Math.floorMod(cfgContainerStyle.ordinal() - 1, ContainerStyle.VALUES.length);
                    cfgContainerStyle = ContainerStyle.VALUES[idx];
                },
                () -> {
                    int idx = Math.floorMod(cfgContainerStyle.ordinal() + 1, ContainerStyle.VALUES.length);
                    cfgContainerStyle = ContainerStyle.VALUES[idx];
                });

        y = drawCycleSelectorRow(
                g, mx, my, y,
                "Loot Table",
                preset.label(),
                () -> cfgLootTableIdx = Math.floorMod(cfgLootTableIdx - 1, LOOT_TABLES.length),
                () -> cfgLootTableIdx = Math.floorMod(cfgLootTableIdx + 1, LOOT_TABLES.length));

        int actionBlockTop = bottomY - 50;
        if (y + 10 < actionBlockTop) {
            String capInfo = SkyUi.ellipsize(font, "Stash: " + (int) cfgContainerKind.stashKg + "kg • " + preset.themeTag(), detW - 12);
            g.text(font, capInfo, detX + 6, y + 1, SkyUi.HEADER, false);
        }

        drawQuantitySelectorRow(g, mx, my, detX + 5, actionBlockTop, detW - 10);
        drawActionBtn(
                g, mx, my,
                detX + 5, actionBlockTop + 16, detW - 10, 14,
                "PLACE LOOT CHEST IN WORLD [P]",
                true,
                this::executePlaceInWorldOneShot,
                Component.literal("Arms one-shot placement for this configured Loot Chest with table " + preset.id()));
        drawActionBtn(
                g, mx, my,
                detX + 5, actionBlockTop + 32, detW - 10, 14,
                "ADD CONTAINER TO INVENTORY [Enter]",
                false,
                this::executeGiveToInventory,
                Component.literal("Adds " + selectedQty + "x container block to your NirnCraft Inventory"));
    }

    private void drawEntityOrNpcConfigurator(GuiGraphicsExtractor g, int mx, int my, CatalogEntry e) {
        int panelH = bottomY - topY;
        int cx = detX + detW / 2;
        int previewH = Mth.clamp(panelH / 3 - 8, 42, 86);
        int daisY = topY + 5 + previewH - 4;

        int daisR = Math.min(38, detW / 3);
        g.fill(cx - daisR, daisY - 3, cx + daisR, daisY + 2, 0x50000000);
        g.fill(cx - daisR + 4, daisY - 2, cx + daisR - 4, daisY + 1, 0x80353028);
        g.fill(cx - daisR + 2, daisY - 1, cx + daisR - 2, daisY, 0x60E8C060);

        LivingEntity previewEntity = getOrCreatePreviewEntity(e);
        float t = (Util.getMillis() % 1_000_000L) / 1000f;
        float yaw = draggingPreview ? previewYaw : (previewYaw + (float) Math.sin(t * 0.65) * 22f);
        boolean renderedEntity = false;
        if (previewEntity != null) {
            try {
                int entityScale = Mth.clamp(previewH / 2 - 3, 16, 36);
                float bbMax = Math.max(previewEntity.getBbWidth(), previewEntity.getBbHeight());
                if (bbMax > 2.2f) {
                    entityScale = Math.max(9, Math.round(entityScale * (1.9f / bbMax)));
                }
                drawLivingEntityPreview(g, cx, daisY, entityScale, yaw, previewEntity);
                renderedEntity = true;
            } catch (Throwable ignored) {
                renderedEntity = false;
            }
        }
        if (!renderedEntity) {
            SkyUi.item3d(g, e.icon, cx, topY + 5 + previewH / 2, (previewH / 16f) * 13f, 10f, yaw);
        }

        hoverPreviewBox = SkyUi.inside(mx, my, detX + 6, topY + 4, detX + detW - 6, topY + 6 + previewH);

        int y = topY + 8 + previewH;
        String title = e.kind == EntryKind.NPC
                ? formatIdentifierWords(cfgNpcRace.name()) + " " + formatIdentifierWords(cfgNpcRole.id)
                : e.name;
        String shownTitle = SkyUi.ellipsize(font, SkyUi.caps(Component.literal(title)), detW - 10);
        g.text(font, shownTitle, cx - font.width(shownTitle) / 2, y, SkyUi.GOLD, true);
        y += 10;

        String idSub = SkyUi.ellipsize(font, e.registryId + " • " + e.typeLabel, detW - 10);
        g.text(font, idSub, cx - font.width(idSub) / 2, y, SkyUi.DIM, false);
        y += 10;
        SkyUi.divider(g, cx, y, detW / 2 - 8);
        y += 4;

        if (e.kind == EntryKind.NPC) {
            y = drawCycleSelectorRow(
                    g, mx, my, y,
                    "Race",
                    formatIdentifierWords(cfgNpcRace.name()),
                    () -> cfgNpcRace = NpcNames.Race.VALUES[Math.floorMod(cfgNpcRace.ordinal() - 1, NpcNames.Race.VALUES.length)],
                    () -> cfgNpcRace = NpcNames.Race.VALUES[Math.floorMod(cfgNpcRace.ordinal() + 1, NpcNames.Race.VALUES.length)]);

            y = drawCycleSelectorRow(
                    g, mx, my, y,
                    "Role",
                    formatIdentifierWords(cfgNpcRole.id),
                    () -> cfgNpcRole = NpcRole.VALUES[Math.floorMod(cfgNpcRole.ordinal() - 1, NpcRole.VALUES.length)],
                    () -> cfgNpcRole = NpcRole.VALUES[Math.floorMod(cfgNpcRole.ordinal() + 1, NpcRole.VALUES.length)]);

            y = drawCycleSelectorRow(
                    g, mx, my, y,
                    "Sex",
                    cfgNpcFemale ? "Female" : "Male",
                    () -> cfgNpcFemale = !cfgNpcFemale,
                    () -> cfgNpcFemale = !cfgNpcFemale);
        }

        String behaviorLabel = switch (cfgBehaviorMode) {
            case 1 -> "Passive";
            case 2 -> "Hostile";
            default -> "Default AI";
        };
        drawCycleSelectorRow(
                g, mx, my, y,
                "Behavior",
                behaviorLabel,
                () -> cfgBehaviorMode = Math.floorMod(cfgBehaviorMode - 1, 3),
                () -> cfgBehaviorMode = Math.floorMod(cfgBehaviorMode + 1, 3));

        int actionBlockTop = bottomY - 34;
        drawActionBtn(
                g, mx, my,
                detX + 5, actionBlockTop, detW - 10, 14,
                "PLACE IN WORLD (ONE-SHOT) [P/RMB]",
                true,
                this::executePlaceInWorldOneShot,
                Component.literal("Arms one-shot hand spawner: Right-Click in world to spawn and clear hand"));
        drawActionBtn(
                g, mx, my,
                detX + 5, actionBlockTop + 16, detW - 10, 14,
                "SPAWN IN FRONT OF ME [Enter/I]",
                false,
                this::executeGiveToInventory,
                Component.literal("Immediately spawns this NPC/Creature in front of you"));
    }

    @Nullable
    private LivingEntity getOrCreatePreviewEntity(CatalogEntry e) {
        if (minecraft == null || minecraft.level == null) return null;
        String key = e.kind == EntryKind.NPC
                ? "npc:" + cfgNpcRole.id + ":" + cfgNpcRace.name() + ":" + cfgNpcFemale
                : "ent:" + e.registryId;
        if (key.equals(cachedPreviewEntityKey) && cachedPreviewEntity != null) {
            return cachedPreviewEntity;
        }
        cachedPreviewEntityKey = key;
        cachedPreviewEntity = null;
        try {
            if (e.kind == EntryKind.NPC) {
                NpcEntity npc = NpcEntities.typeFor(cfgNpcRole).create(minecraft.level, EntitySpawnReason.LOAD);
                if (npc != null) {
                    npc.setRole(cfgNpcRole);
                    RandomSource r = RandomSource.create(cfgNpcRace.ordinal() * 31L + cfgNpcRole.ordinal());
                    Outfit outfit = Cultures.outfitFor(cfgNpcRole, cfgNpcRace, r);
                    int look = NpcLook.random(cfgNpcRace, cfgNpcFemale, cfgNpcRole == NpcRole.CHILD, outfit, r);
                    npc.setLook(look);
                    Npcs.outfit(npc);
                    cachedPreviewEntity = npc;
                }
            } else if (e.entityType != null) {
                Entity created = e.entityType.create(minecraft.level, EntitySpawnReason.LOAD);
                if (created instanceof LivingEntity le) {
                    cachedPreviewEntity = le;
                }
            }
        } catch (Throwable ignored) {
            cachedPreviewEntity = null;
        }
        return cachedPreviewEntity;
    }

    /** Reuses RaceScreen's exact 26.3 entity render state extraction pattern. */
    private static void drawLivingEntityPreview(
            GuiGraphicsExtractor g, int x, int y, int scale, float yaw, LivingEntity e) {
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf().rotateX(8f * Mth.DEG_TO_RAD);
        pose.mul(camera);
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
        var renderState = renderer.createRenderState(e, 1.0f);
        renderState.shadowPieces.clear();
        renderState.outlineColor = 0;
        renderState.isInvisible = false;
        if (renderState instanceof AvatarRenderState avatar) {
            avatar.isSpectator = false;
        }
        if (renderState instanceof LivingEntityRenderState living) {
            living.isInvisibleToPlayer = false;
            living.bodyRot = 180f + yaw;
            living.yRot = 0f;
            living.xRot = 0f;
        }
        int halfWidth = scale * 2;
        int previewHeight = scale * 3;
        float feetOffset = (previewHeight - 8) / (2f * scale);
        g.entity(renderState, scale, new Vector3f(0, feetOffset, 0),
                pose, camera, x - halfWidth, y - previewHeight, x + halfWidth, y + 8);
    }

    private int drawCycleSelectorRow(
            GuiGraphicsExtractor g, int mx, int my, int y,
            String label, String value, Runnable onPrev, Runnable onNext) {
        String fitLabel = SkyUi.ellipsize(font, label, Math.max(28, detW * 36 / 100));
        g.text(font, fitLabel, detX + 6, y + 2, SkyUi.HEADER, false);
        int vx = detX + Math.max(44, detW * 38 / 100);
        int vw = detX + detW - 5 - vx;
        boolean hl = SkyUi.inside(mx, my, vx, y, vx + 12, y + 12);
        boolean hr = SkyUi.inside(mx, my, vx + vw - 12, y, vx + vw, y + 12);
        g.fill(vx, y, vx + 12, y + 12, hl ? 0x38FFFFFF : 0x16FFFFFF);
        g.fill(vx + vw - 12, y, vx + vw, y + 12, hr ? 0x38FFFFFF : 0x16FFFFFF);
        g.text(font, "<", vx + 3, y + 2, hl ? SkyUi.BRIGHT : SkyUi.GOLD, false);
        g.text(font, ">", vx + vw - 8, y + 2, hr ? SkyUi.BRIGHT : SkyUi.GOLD, false);

        String trimmed = SkyUi.ellipsize(font, value, Math.max(16, vw - 26));
        g.text(font, trimmed, vx + (vw - font.width(trimmed)) / 2, y + 2, SkyUi.TEXT, false);
        SkyUi.hline(g, detX + 5, detX + detW - 5, y + 12, 0x35);

        clickZones.add(new ClickRect(vx, y, 12, 12, onPrev, null));
        clickZones.add(new ClickRect(vx + vw - 12, y, 12, 12, onNext, null));
        return y + 14;
    }

    private void drawQuantitySelectorRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        String lbl = w < 140 ? "Q:" : "QTY:";
        g.text(font, lbl, x, y + 2, SkyUi.HEADER, false);
        int bx = x + font.width(lbl) + 3;
        int availW = w - (bx - x);
        int btnCount = QTY_PRESETS.length + 2; // [-] [1] [5] [10] [32] [64] [+]
        int gap = 2;
        int bw = Math.max(12, (availW - gap * (btnCount - 1)) / btnCount);
        int bh = 13;

        drawQtyMiniBtn(g, mx, my, bx, y, bw, bh, "-", false, () -> selectedQty = Math.max(1, selectedQty - 1));
        bx += bw + gap;

        for (int preset : QTY_PRESETS) {
            final int q = preset;
            drawQtyMiniBtn(g, mx, my, bx, y, bw, bh, String.valueOf(q), selectedQty == q, () -> selectedQty = q);
            bx += bw + gap;
        }

        drawQtyMiniBtn(g, mx, my, bx, y, bw, bh, "+", false, () -> selectedQty = Math.min(640, selectedQty + 1));
    }

    private void drawQtyMiniBtn(
            GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h,
            String text, boolean active, Runnable action) {
        boolean hov = SkyUi.inside(mx, my, x, y, x + w, y + h);
        g.fill(x, y, x + w, y + h, active ? 0x48E8C060 : (hov ? 0x30FFFFFF : 0x18FFFFFF));
        SkyUi.hline(g, x, x + w, y, active ? 0xD0 : 0x50);
        SkyUi.hline(g, x, x + w, y + h - 1, active ? 0xD0 : 0x50);
        g.text(font, text, x + (w - font.width(text)) / 2, y + 2, active ? SkyUi.GOLD : (hov ? SkyUi.BRIGHT : SkyUi.TEXT), false);
        clickZones.add(new ClickRect(x, y, w, h, action, null));
    }

    private void drawActionBtn(
            GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h,
            String text, boolean primaryGold, Runnable action, @Nullable Component tooltip) {
        boolean hov = SkyUi.inside(mx, my, x, y, x + w, y + h);
        int bg = primaryGold
                ? (hov ? 0x58E8C060 : 0x30E8C060)
                : (hov ? 0x44FFFFFF : 0x20FFFFFF);
        g.fill(x, y, x + w, y + h, bg);
        SkyUi.hline(g, x, x + w, y, hov ? 0xE0 : 0x80);
        SkyUi.hline(g, x, x + w, y + h - 1, hov ? 0xE0 : 0x80);
        String shown = SkyUi.ellipsize(font, text, w - 12);
        int tx = x + (w - font.width(shown)) / 2;
        if (hov) {
            SkyUi.diamond(g, tx - 5, y + h / 2, 2, SkyUi.GOLD);
            SkyUi.diamond(g, tx + font.width(shown) + 4, y + h / 2, 2, SkyUi.GOLD);
        }
        int col = hov ? SkyUi.BRIGHT : (primaryGold ? SkyUi.GOLD : SkyUi.TEXT);
        g.text(font, shown, tx, y + (h - 8) / 2, col, hov);
        clickZones.add(new ClickRect(x, y, w, h, action, tooltip));
        if (hov && tooltip != null) hoverTooltip = tooltip;
    }

    private void drawBottomBar(GuiGraphicsExtractor g, int mx, int my) {
        int by = height - BOTTOM_BAR_H;
        g.fill(0, by, width, height, 0xB8050507);
        SkyUi.hline(g, 0, width, by, 0x90);

        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player != null) {
            float curW = ClientInventoryHandlers.liveCurrent();
            float capW = ClientInventoryHandlers.liveCapacity();
            String leftStatus = String.format(
                    Locale.ROOT,
                    "CARRY: %s/%s  •  GOLD: %d  •  SHOWN: %d  •  [%s]",
                    ItemWeights.format(curW),
                    ItemWeights.format(capW),
                    Currency.balance(player),
                    shown.size(),
                    DevTestState.qualityCeiling.shortTag);
            g.text(font, SkyUi.ellipsize(font, leftStatus, contentW - 8), x0 + 4, by + 3, SkyUi.HEADER, false);
        }

        String hints = "[/] Search  [Enter/I] Add to Inventory  [P/RMB] Place in World (One-Shot)  [F] Fav  [X] Inspect  [-] Dev  [=] Capture";
        String fitHints = SkyUi.ellipsize(font, hints, contentW - 8);
        g.centeredText(font, Component.literal(fitHints), width / 2, by + 13, SkyUi.DIM);
    }

    private void renderInspectOverlay(GuiGraphicsExtractor g, int mx, int my) {
        CatalogEntry e = selectedEntry;
        if (e == null) {
            inspectMode = false;
            return;
        }
        g.fill(0, 0, width, height, 0xEE050505);
        long now = Util.getMillis();
        SkyUi.smoke(g, width, height, (now % 1_000_000L) / 1000f, 0.8f);
        int cx = width / 2;
        int cy = height / 2 + 6;

        float dt = Mth.clamp((now - inspectLastFrame) / 1000f, 0f, 0.1f);
        inspectLastFrame = now;
        if (now - inspectLastDrag > 1500L) inspectRotY += dt * 20f;

        float scale = Math.min(width, height) * 0.52f * inspectZoom;
        drawPreviewGlow(g, cx, cy, (int) (Math.min(width, height) * 0.26f));
        SkyUi.item3d(g, e.icon, cx, cy, scale, inspectRotX, inspectRotY);

        String title = SkyUi.caps(Component.literal(e.name));
        SkyUi.scaledCentered(g, font, title, cx, 16, 1.3f, SkyUi.BRIGHT, true);
        String sub = e.registryId + "  •  " + e.typeLabel;
        g.text(font, sub, cx - font.width(sub) / 2, 32, SkyUi.DIM, false);
        SkyUi.ornament(g, cx, 46, Math.min(100, width / 4), 0xC0C8BC9A);

        String hint = SkyUi.ellipsize(
                font,
                "[Drag] Rotate   [Scroll] Zoom   [Enter] Add to Inventory   [P] Place in World (One-Shot)   [X/Esc] Exit",
                width - 16);
        g.text(font, hint, cx - font.width(hint) / 2, height - 18, SkyUi.HEADER, false);
    }

    private static void drawPreviewGlow(GuiGraphicsExtractor g, int cx, int cy, int r) {
        for (int dy = -r; dy <= r; dy += 2) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            float t = 1f - Math.abs(dy) / (float) r;
            int a = (int) (20 * t);
            if (a <= 0 || half <= 0) continue;
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 2, a << 24 | 0xFFF0D8);
        }
    }

    // ------------------------------------------------------------------ Input Handling (26.3 Event Signatures)

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = UiInput.actionButton(event.button());

        if (inspectMode) {
            if (button == 1) {
                inspectMode = false;
                return true;
            }
            return true;
        }

        if (hoverClearSearch && button == 0) {
            searchQuery = "";
            lastSearchQuery = "";
            searchFocused = true;
            listScroll = 0;
            rebuildShownList();
            return true;
        }
        if (hoverSearchBox && button == 0) {
            searchFocused = true;
            return true;
        } else if (button == 0) {
            searchFocused = false;
        }

        if (button == 0) {
            for (ClickRect zone : clickZones) {
                if (zone.contains(mx, my)) {
                    zone.action().run();
                    return true;
                }
            }
        }

        if (hoverCat >= 0 && hoverCat < CatalogCategory.VALUES.length && button == 0) {
            setCategory(CatalogCategory.VALUES[hoverCat]);
            return true;
        }

        if (hoverSort >= 0 && button == 0) {
            setSort(hoverSort);
            return true;
        }

        if (hoverPreviewBox && button == 0) {
            draggingPreview = true;
            lastDragX = mx;
            if (doubleClick && selectedEntry != null) {
                inspectMode = true;
                inspectRotX = 15f;
                inspectRotY = 0f;
                inspectZoom = 1.0f;
                inspectLastFrame = Util.getMillis();
            }
            return true;
        }

        if (hoverRow >= 0 && hoverRow < shown.size()) {
            long now = Util.getMillis();
            boolean sameRowDouble = (button == 0) && (hoverRow == lastClickedRow) && (now - lastRowClickMs < 320L || doubleClick);
            selectIndex(hoverRow, false);
            lastClickedRow = hoverRow;
            lastRowClickMs = now;
            if (button == 1) {
                executePlaceInWorldOneShot();
            } else if (sameRowDouble) {
                executeGiveToInventory();
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (inspectMode) {
            inspectRotY += (float) dx * 1.5f;
            inspectRotX = Mth.clamp(inspectRotX - (float) dy * 1.5f, -80f, 80f);
            inspectLastDrag = Util.getMillis();
            return true;
        }
        if (draggingPreview) {
            previewYaw -= (float) (event.x() - lastDragX) * 2.0f;
            lastDragX = event.x();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingPreview = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (inspectMode) {
            inspectZoom = Mth.clamp(inspectZoom + (float) scrollY * 0.15f, 0.45f, 3.0f);
            return true;
        }
        int step = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
        if (step == 0) return false;

        if (SkyUi.inside(mx, my, catX, topY, catX + catW, bottomY)) {
            cycleCategory(step);
            return true;
        }
        if (SkyUi.inside(mx, my, detX, topY, detX + detW, bottomY)) {
            previewYaw += (float) scrollY * 16f;
            return true;
        }
        listScroll = Mth.clamp(listScroll + step * 3, 0, Math.max(0, shown.size() - visibleRows()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean shift = UiInput.shiftDown();
        boolean ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);

        if (inspectMode) {
            if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_X || key == InputConstants.KEY_TAB) {
                inspectMode = false;
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                inspectMode = false;
                executeGiveToInventory();
                return true;
            }
            if (key == InputConstants.KEY_P) {
                inspectMode = false;
                executePlaceInWorldOneShot();
                return true;
            }
            return true;
        }

        // Ctrl+F or '/' focuses search bar immediately
        if ((ctrl && key == InputConstants.KEY_F) || (!searchFocused && key == InputConstants.KEY_SLASH)) {
            searchFocused = true;
            return true;
        }

        // When search bar is focused, handle direct key-to-text input
        if (searchFocused) {
            if (key == InputConstants.KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                searchFocused = false;
                if (selectedEntry != null) executeGiveToInventory();
                return true;
            }
            if (key == InputConstants.KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = ctrl ? "" : searchQuery.substring(0, searchQuery.length() - 1);
                    lastSearchQuery = searchQuery;
                    listScroll = 0;
                    rebuildShownList();
                }
                return true;
            }
            if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
                selectIndex(selected + (key == InputConstants.KEY_UP ? -1 : 1), true);
                return true;
            }
            char typed = mapKeyToSearchChar(key, shift);
            if (typed != 0) {
                searchQuery += typed;
                lastSearchQuery = searchQuery;
                listScroll = 0;
                rebuildShownList();
                return true;
            }
            return true;
        }

        // Navigation & Hotkeys when search bar is not actively capturing letters
        switch (key) {
            case InputConstants.KEY_UP, InputConstants.KEY_W -> {
                selectIndex(selected - 1, true);
                return true;
            }
            case InputConstants.KEY_DOWN, InputConstants.KEY_S -> {
                selectIndex(selected + 1, true);
                return true;
            }
            case InputConstants.KEY_PAGEUP -> {
                selectIndex(selected - visibleRows(), true);
                return true;
            }
            case InputConstants.KEY_PAGEDOWN -> {
                selectIndex(selected + visibleRows(), true);
                return true;
            }
            case InputConstants.KEY_HOME -> {
                selectIndex(0, true);
                return true;
            }
            case InputConstants.KEY_END -> {
                selectIndex(shown.size() - 1, true);
                return true;
            }
            case InputConstants.KEY_LEFT, InputConstants.KEY_A -> {
                cycleCategory(-1);
                return true;
            }
            case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> {
                cycleCategory(1);
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_I -> {
                executeGiveToInventory();
                return true;
            }
            case InputConstants.KEY_P -> {
                executePlaceInWorldOneShot();
                return true;
            }
            case InputConstants.KEY_F -> {
                toggleSelectedFavorite();
                return true;
            }
            case InputConstants.KEY_R -> {
                setSort((sortMode + 1) % 4);
                return true;
            }
            case InputConstants.KEY_X -> {
                if (selectedEntry != null) {
                    inspectMode = true;
                    inspectRotX = 15f;
                    inspectRotY = 0f;
                    inspectZoom = 1.0f;
                    inspectLastFrame = Util.getMillis();
                    return true;
                }
            }
            case InputConstants.KEY_BACKSPACE -> {
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    lastSearchQuery = searchQuery;
                    listScroll = 0;
                    rebuildShownList();
                    return true;
                }
            }
            case InputConstants.KEY_MINUS -> {
                if (minecraft != null) {
                    minecraft.gui.setScreen(new DevSettingsScreen(this));
                    return true;
                }
            }
            case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> {
                DevTestClient.triggerSessionCapture(this);
                return true;
            }
            case InputConstants.KEY_M -> {
                if (minecraft != null) {
                    minecraft.gui.setScreen(new MapScreen());
                    return true;
                }
            }
            case InputConstants.KEY_TAB -> {
                onClose();
                return true;
            }
            default -> {}
        }

        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (parent != null && minecraft != null) {
            minecraft.gui.setScreen(parent);
            return;
        }
        super.onClose();
    }

    private static char mapKeyToSearchChar(int key, boolean shift) {
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
            char base = (char) ('a' + (key - InputConstants.KEY_A));
            return shift ? Character.toUpperCase(base) : base;
        }
        if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) {
            int digit = key - InputConstants.KEY_0;
            if (shift) {
                return switch (digit) {
                    case 2 -> '@';
                    case 3 -> '#';
                    default -> (char) ('0' + digit);
                };
            }
            return (char) ('0' + digit);
        }
        return switch (key) {
            case InputConstants.KEY_SPACE -> ' ';
            case InputConstants.KEY_MINUS -> shift ? '_' : '-';
            case InputConstants.KEY_SEMICOLON -> shift ? ':' : ';';
            case InputConstants.KEY_SLASH -> '/';
            case InputConstants.KEY_PERIOD -> '.';
            case InputConstants.KEY_COMMA -> ',';
            case InputConstants.KEY_APOSTROPHE -> '\'';
            case InputConstants.KEY_LBRACKET -> '[';
            case InputConstants.KEY_RBRACKET -> ']';
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ Formatting Utilities

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String formatIdentifierWords(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.toLowerCase(Locale.ROOT).split("[_/:]+");
        StringBuilder sb = new StringBuilder(raw.length() + 4);
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String fmtNum(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (Math.abs(r - Math.rint(r)) < 0.001) return String.valueOf((long) Math.rint(r));
        return String.valueOf(r);
    }
}
