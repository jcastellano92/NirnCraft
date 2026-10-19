package com.nirncraft.homestead;

import com.nirncraft.quest.party.Parties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a claimed homestead plot founded by placing a Drafting Table.
 *
 * <p>Boundaries:
 * <ul>
 *     <li>Horizontal radius: 28 blocks around center</li>
 *     <li>Vertical floor: 10 blocks below drafting table (depth limit)</li>
 *     <li>Vertical ceiling: 24 blocks above drafting table</li>
 * </ul>
 */
public class HomesteadClaim {
    public static final int DEFAULT_RADIUS = 28;
    public static final int DEFAULT_MIN_DEPTH = 10;
    public static final int DEFAULT_MAX_HEIGHT = 24;

    public static final int PERM_OWNER = 0;
    public static final int PERM_PARTY = 1;
    public static final int PERM_PUBLIC = 2;

    private final UUID ownerId;
    private String ownerName;
    private final BlockPos center;
    private final String dimension;
    private int radius;
    private final int minDepth;
    private final int maxHeight;

    private int doorPerm;
    private int containerPerm;
    private int buildPerm;
    /** Crafting stations (forge, anvil, alchemy lab...) and beds: {@link #PERM_OWNER}, {@link #PERM_PARTY}, {@link #PERM_PUBLIC}. */
    private int stationPerm = PERM_PARTY;
    private int bedPerm = PERM_PARTY;
    /** Per-block access overrides set in build mode (sneak-use): block position (long) to level 0..2; absent = the claim's default. */
    private final Map<Long, Integer> overrides = new HashMap<>();
    /** The name the owner gave the homestead; empty = the default name. Shown on the map, compass and discovery banner. */
    private String name = "";

    /** What a block is used for, for access control. */
    public enum Access { DOOR, CONTAINER, STATION, BED }

    // Hearthfire: which plot (if any), what was built, the steward, the beds of the house
    @Nullable
    private String plot;
    private boolean purchased;
    /** The way the house grows from the drafting table (the side away from whoever placed it). */
    private Direction facing = Direction.NORTH;
    private final Set<String> built = new LinkedHashSet<>();
    @Nullable
    private UUID steward;
    private String stewardName = "";
    /** Beds the builder made: adults' first, then children's (see {@link #childBeds}). */
    private final List<BlockPos> beds = new ArrayList<>();
    private int childBeds;

    public HomesteadClaim(UUID ownerId, String ownerName, BlockPos center, String dimension) {
        this(ownerId, ownerName, center, dimension, DEFAULT_RADIUS, DEFAULT_MIN_DEPTH, DEFAULT_MAX_HEIGHT,
                PERM_PARTY, PERM_PARTY, PERM_PARTY);
    }

    public HomesteadClaim(UUID ownerId, String ownerName, BlockPos center, String dimension,
                          int radius, int minDepth, int maxHeight,
                          int doorPerm, int containerPerm, int buildPerm) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.center = center;
        this.dimension = dimension;
        this.radius = radius;
        this.minDepth = minDepth;
        this.maxHeight = maxHeight;
        this.doorPerm = doorPerm;
        this.containerPerm = containerPerm;
        this.buildPerm = buildPerm;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String name) {
        this.ownerName = name;
    }

    public BlockPos getCenter() {
        return center;
    }

    public String getDimension() {
        return dimension;
    }

    public int getRadius() {
        return radius;
    }

    /** Expands the grounds (the caller has checked the new area is free). */
    public void setRadius(int radius) {
        this.radius = Math.max(this.radius, radius);
    }

    public int getMinDepth() {
        return minDepth;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public int getDoorPerm() {
        return doorPerm;
    }

    public void setDoorPerm(int doorPerm) {
        this.doorPerm = doorPerm;
    }

    public int getContainerPerm() {
        return containerPerm;
    }

    public void setContainerPerm(int containerPerm) {
        this.containerPerm = containerPerm;
    }

    public int getBuildPerm() {
        return buildPerm;
    }

    public void setBuildPerm(int buildPerm) {
        this.buildPerm = buildPerm;
    }

    public boolean isInside(Level level, BlockPos pos) {
        if (!level.dimension().identifier().toString().equals(dimension)) return false;
        int dx = pos.getX() - center.getX();
        int dz = pos.getZ() - center.getZ();
        if (dx * dx + dz * dz > radius * radius) return false;
        return pos.getY() >= center.getY() - minDepth && pos.getY() <= center.getY() + maxHeight;
    }

    public boolean isHorizontallyInside(Level level, BlockPos pos) {
        if (!level.dimension().identifier().toString().equals(dimension)) return false;
        int dx = pos.getX() - center.getX();
        int dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    public boolean isOwner(ServerPlayer player) {
        return player.getUUID().equals(ownerId) || player.isCreative();
    }

    public boolean isPartyMember(ServerPlayer player) {
        if (isOwner(player)) return true;
        var party = Parties.partyOf(player);
        return party != null && party.has(ownerId);
    }

    public boolean canBuild(ServerPlayer player) {
        if (isOwner(player)) return true;
        if (buildPerm == PERM_PARTY && isPartyMember(player)) return true;
        return false;
    }

    public boolean canAccessDoor(ServerPlayer player) {
        return allows(doorPerm, player);
    }

    public boolean canAccessContainer(ServerPlayer player) {
        return allows(containerPerm, player);
    }

    public boolean canAccessStation(ServerPlayer player) {
        return allows(stationPerm, player);
    }

    public boolean canAccessBed(ServerPlayer player) {
        return allows(bedPerm, player);
    }

    /** Owner only (0), the owner's party (1) or everyone (2). */
    private boolean allows(int perm, ServerPlayer player) {
        if (perm >= PERM_PUBLIC) return true;
        if (isOwner(player)) return true;
        return perm == PERM_PARTY && isPartyMember(player);
    }

    /** The claim-wide level of an access kind. */
    public int permOf(Access kind) {
        return switch (kind) {
            case DOOR -> doorPerm;
            case CONTAINER -> containerPerm;
            case STATION -> stationPerm;
            case BED -> bedPerm;
        };
    }

    /** May {@code player} use the block at {@code pos} (its own override, else the claim's level for this kind)? */
    public boolean mayUse(ServerPlayer player, Access kind, BlockPos pos) {
        Integer o = overrides.get(pos.asLong());
        return allows(o != null ? o : permOf(kind), player);
    }

    /** The level set for this one block, or -1 when it follows the claim. */
    public int overrideAt(BlockPos pos) {
        Integer o = overrides.get(pos.asLong());
        return o == null ? -1 : o;
    }

    /** Cycles a block's own level: follows the claim, owner only, party, everyone, follows the claim. Returns the new level (-1 = claim). */
    public int cycleOverride(BlockPos pos) {
        int next = overrideAt(pos) + 1;
        if (next > PERM_PUBLIC) {
            overrides.remove(pos.asLong());
            return -1;
        }
        overrides.put(pos.asLong(), next);
        return next;
    }

    /** Forgets overrides of blocks that are gone (the caller passes a test). */
    public void pruneOverrides(java.util.function.Predicate<BlockPos> stillThere) {
        overrides.keySet().removeIf(l -> !stillThere.test(BlockPos.of(l)));
    }

    public int getStationPerm() {
        return stationPerm;
    }

    public void setStationPerm(int v) {
        stationPerm = Math.floorMod(v, 3);
    }

    public int getBedPerm() {
        return bedPerm;
    }

    public void setBedPerm(int v) {
        bedPerm = Math.floorMod(v, 3);
    }

    public String getName() {
        return name;
    }

    /** What a client may name a homestead: letters, digits and a little punctuation, one space at a time, 32 characters at most. */
    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || " '-.,:!&".indexOf(c) >= 0) sb.append(c);
        }
        String name = sb.toString().trim().replaceAll("\\s{2,}", " ");
        if (name.length() > 32) name = name.substring(0, 32).trim();
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /** The name as plain text (server side: no language files): the owner's choice, the plot's name or "&lt;owner&gt;'s Homestead". */
    public String plainName() {
        if (!name.isEmpty()) return name;
        if (plot != null) {
            StringBuilder sb = new StringBuilder();
            boolean up = true;
            for (char c : plot.toCharArray()) {
                if (c == '_') {
                    sb.append(' ');
                    up = true;
                } else {
                    sb.append(up ? Character.toUpperCase(c) : c);
                    up = false;
                }
            }
            return sb.toString();
        }
        return ownerName + "'s Homestead";
    }

    // ------------------------------------------------------------------ Hearthfire

    /** The Hearthfire plot's place id ({@code lakeview_manor}, ...) or null for a homestead anywhere else. */
    @Nullable
    public String getPlot() {
        return plot;
    }

    public void setPlot(@Nullable String plot) {
        this.plot = plot;
    }

    /** A Hearthfire plot must be bought before building; other claims never need to be. */
    public boolean isPurchased() {
        return plot == null || purchased;
    }

    public void setPurchased(boolean purchased) {
        this.purchased = purchased;
    }

    public Direction getFacing() {
        return facing;
    }

    public void setFacing(Direction facing) {
        if (facing.getAxis().isHorizontal()) this.facing = facing;
    }

    public Set<String> getBuilt() {
        return built;
    }

    @Nullable
    public UUID getSteward() {
        return steward;
    }

    public String getStewardName() {
        return stewardName;
    }

    public void setSteward(@Nullable UUID steward, String name) {
        this.steward = steward;
        this.stewardName = name == null ? "" : name;
    }

    public List<BlockPos> getBeds() {
        return beds;
    }

    public int getChildBeds() {
        return childBeds;
    }

    public void addBed(BlockPos head, boolean child) {
        if (beds.contains(head)) return;
        if (child) {
            beds.add(head);
            childBeds++;
        } else {
            beds.add(beds.size() - childBeds, head);
        }
    }

    /** The homestead's display name: the plot's name, or "<owner>'s Homestead". */
    public Component displayName() {
        if (!name.isEmpty()) return Component.literal(name);
        if (plot != null) return Component.translatable("place.nirncraft." + plot);
        return Component.translatable("homestead.nirncraft.name", ownerName);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("owner", net.minecraft.core.UUIDUtil.CODEC,  ownerId);
        tag.putString("name", ownerName);
        tag.put("center", com.nirncraft.platform.NbtPositions.write(center));
        tag.putString("dim", dimension);
        tag.putInt("radius", radius);
        tag.putInt("minDepth", minDepth);
        tag.putInt("maxHeight", maxHeight);
        tag.putInt("doorPerm", doorPerm);
        tag.putInt("contPerm", containerPerm);
        tag.putInt("buildPerm", buildPerm);
        if (plot != null) tag.putString("plot", plot);
        tag.putBoolean("purchased", purchased);
        tag.putInt("facing", facing.get2DDataValue());
        ListTag builtList = new ListTag();
        for (String b : built) builtList.add(StringTag.valueOf(b));
        tag.put("built", builtList);
        if (steward != null) tag.store("steward", net.minecraft.core.UUIDUtil.CODEC,  steward);
        tag.putString("stewardName", stewardName);
        ListTag bedList = new ListTag();
        for (BlockPos b : beds) bedList.add(com.nirncraft.platform.NbtPositions.write(b));
        tag.put("beds", bedList);
        tag.putInt("childBeds", childBeds);
        tag.putInt("stationPerm", stationPerm);
        tag.putInt("bedPerm", bedPerm);
        if (!name.isEmpty()) tag.putString("homeName", name);
        if (!overrides.isEmpty()) {
            ListTag list = new ListTag();
            for (Map.Entry<Long, Integer> e : overrides.entrySet()) {
                CompoundTag o = new CompoundTag();
                o.putLong("p", e.getKey());
                o.putInt("l", e.getValue());
                list.add(o);
            }
            tag.put("overrides", list);
        }
        return tag;
    }

    public static HomesteadClaim load(CompoundTag tag) {
        UUID owner = tag.read("owner", net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
        String name = tag.getStringOr("name","");
        BlockPos center = com.nirncraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("center"));
        String dim = tag.getStringOr("dim","");
        int rad = tag.contains("radius") ? tag.getIntOr("radius",0) : DEFAULT_RADIUS;
        int depth = tag.contains("minDepth") ? tag.getIntOr("minDepth",0) : DEFAULT_MIN_DEPTH;
        int height = tag.contains("maxHeight") ? tag.getIntOr("maxHeight",0) : DEFAULT_MAX_HEIGHT;
        int door = tag.getIntOr("doorPerm",0);
        int cont = tag.getIntOr("contPerm",0);
        int build = tag.getIntOr("buildPerm",0);
        HomesteadClaim claim = new HomesteadClaim(owner, name, center, dim, rad, depth, height, door, cont, build);
        if (tag.contains("plot")) claim.plot = tag.getStringOr("plot","");
        claim.purchased = tag.getBooleanOr("purchased",false);
        if (tag.contains("facing")) claim.setFacing(Direction.from2DDataValue(tag.getIntOr("facing",0)));
        ListTag builtList = tag.getListOrEmpty("built");
        for (int i = 0; i < builtList.size(); i++) claim.built.add(builtList.getStringOr(i, ""));
        if (tag.read("steward", net.minecraft.core.UUIDUtil.CODEC).isPresent()) claim.steward = tag.read("steward", net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
        claim.stewardName = tag.getStringOr("stewardName","");
        ListTag bedList = tag.getListOrEmpty("beds");
        for (int i = 0; i < bedList.size(); i++) claim.beds.add(com.nirncraft.platform.NbtPositions.read(bedList.getCompoundOrEmpty(i)));
        claim.childBeds = Math.min(claim.beds.size(), tag.getIntOr("childBeds",0));
        if (tag.contains("stationPerm")) claim.setStationPerm(tag.getIntOr("stationPerm",0));
        if (tag.contains("bedPerm")) claim.setBedPerm(tag.getIntOr("bedPerm",0));
        claim.name = tag.getStringOr("homeName","");
        ListTag ov = tag.getListOrEmpty("overrides");
        for (int i = 0; i < ov.size(); i++) {
            CompoundTag o = ov.getCompoundOrEmpty(i);
            claim.overrides.put(o.getLongOr("p",0L), Math.max(0, Math.min(PERM_PUBLIC, o.getIntOr("l",0))));
        }
        return claim;
    }
}
