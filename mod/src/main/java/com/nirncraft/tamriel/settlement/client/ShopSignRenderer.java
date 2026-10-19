package com.nirncraft.tamriel.settlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.client.ShopSignModels.ShopSignType;
import com.nirncraft.tamriel.settlement.client.ShopSignModels.SignModelPair;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.*;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.StandingSignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Locale;

/**
 * Custom 3D renderer for settlement shop and civic building signs.
 *
 * <ul>
 *   <li>Replaces vanilla text hanging signs on main shops/buildings with regional 3D brackets,
 *       carved/forged plaques, and double-sided 3D sculpted trade symbols.</li>
 *   <li>Suppresses exterior signs on residential homes (whose owners are shown on door hover UI).</li>
 *   <li>Preserves vanilla rendering for road signposts, notice boards, homestead for-sale signs,
 *       and player-placed signs.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class ShopSignRenderer {
    public static final Identifier ATLAS =
            Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/entity/settlement/shop_signs.png");

    public enum Kind {
        VANILLA,
        HIDE_HOME_SIGN,
        SHOP_3D
    }

    public record Classification(Kind kind, ShopSignType type, Culture culture, String title) {
        public static final Classification VANILLA = new Classification(Kind.VANILLA, null, Culture.NORD, "");
        public static final Classification HIDE = new Classification(Kind.HIDE_HOME_SIGN, null, Culture.NORD, "");
    }

    private ShopSignRenderer() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BlockEntityTypes.HANGING_SIGN, Hanging::new);
        event.registerBlockEntityRenderer(BlockEntityTypes.SIGN, Wall::new);
    }

    private record Extra(Classification classification,Direction outward,SignModelPair pair,float sway) {}
    private static Extra extractExtra(SignBlockEntity be,float partial,boolean standing) {
        Classification c=standing?Classification.VANILLA:classify(be);
        if(c.kind()!=Kind.SHOP_3D||c.type()==null)return new Extra(c,Direction.SOUTH,null,0);
        float time=be.getLevel()==null?partial:be.getLevel().getGameTime()+partial;
        float phase=(be.getBlockPos().asLong()&0xffffL)*.017f;
        return new Extra(c,resolveOutwardDirection(be),ShopSignModels.get(c.culture(),c.type()),(float)Math.sin(time*.075f+phase)*2.2f);
    }
    public static class HangingState extends HangingSignRenderState { Extra extra; }
    public static class WallState extends StandingSignRenderState { Extra extra; }
    public static final class Hanging extends HangingSignRenderer {
        public Hanging(BlockEntityRendererProvider.Context ctx){super(ctx);}
        @Override public HangingState createRenderState(){return new HangingState();}
        @Override public void extractRenderState(SignBlockEntity be,HangingSignRenderState state,float partial,Vec3 camera,ModelFeatureRenderer.CrumblingOverlay breaking){
            super.extractRenderState(be,state,partial,camera,breaking);((HangingState)state).extra=extractExtra(be,partial,false);
        }
        @Override public void submit(HangingSignRenderState state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
            Extra extra=((HangingState)state).extra;
            if(extra.classification().kind()==Kind.VANILLA)super.submit(state,pose,collector,camera);
            else if(extra.classification().kind()==Kind.SHOP_3D)submitSign(extra,state.lightCoords,pose,collector);
        }
    }
    public static final class Wall extends StandingSignRenderer {
        public Wall(BlockEntityRendererProvider.Context ctx){super(ctx);}
        @Override public WallState createRenderState(){return new WallState();}
        @Override public void extractRenderState(SignBlockEntity be,StandingSignRenderState state,float partial,Vec3 camera,ModelFeatureRenderer.CrumblingOverlay breaking){
            super.extractRenderState(be,state,partial,camera,breaking);((WallState)state).extra=extractExtra(be,partial,be.getBlockState().getBlock() instanceof StandingSignBlock);
        }
        @Override public void submit(StandingSignRenderState state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
            Extra extra=((WallState)state).extra;
            if(extra.classification().kind()==Kind.VANILLA)super.submit(state,pose,collector,camera);
            else if(extra.classification().kind()==Kind.SHOP_3D)submitSign(extra,state.lightCoords,pose,collector);
        }
    }
    private static void submitSign(Extra extra,int light,PoseStack pose,SubmitNodeCollector collector){
        var type=net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(ATLAS);
        pose.pushPose();pose.translate(.5,.5,.5);pose.rotateDegrees(Axis.YP,-extra.outward().toYRot());
        collector.submitCustomGeometry(pose,type,(p,v)->extra.pair().bracket().render(p,v,light));
        pose.translate(0,5.4f/16,0);pose.rotateDegrees(Axis.ZP,extra.sway());pose.translate(0,-5.4f/16,0);
        collector.submitCustomGeometry(pose,type,(p,v)->extra.pair().swingingPlaque().render(p,v,light));
        pose.popPose();
    }

    private static Direction resolveOutwardDirection(SignBlockEntity be) {
        BlockState state = be.getBlockState();
        Direction hint = Direction.SOUTH;
        if (state.hasProperty(WallHangingSignBlock.FACING)) {
            hint = state.getValue(WallHangingSignBlock.FACING);
        } else if (state.hasProperty(WallSignBlock.FACING)) {
            hint = state.getValue(WallSignBlock.FACING);
        } else if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            hint = state.getValue(HorizontalDirectionalBlock.FACING);
        } else if (state.hasProperty(CeilingHangingSignBlock.ROTATION)) {
            hint = RotationSegment.convertToDirection(state.getValue(CeilingHangingSignBlock.ROTATION)).orElse(Direction.SOUTH);
        }

        Level level = be.getLevel();
        if (level == null) {
            return hint;
        }
        BlockPos pos = be.getBlockPos();
        Direction bestDir = hint;
        int bestScore = Integer.MIN_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int score = 0;
            BlockPos behind = pos.relative(dir.getOpposite());
            BlockPos front = pos.relative(dir);
            BlockState behindState = level.getBlockState(behind);
            BlockState behindAbove = level.getBlockState(behind.above());
            BlockState frontState = level.getBlockState(front);

            if (isWallSupport(behindState)) score += 6;
            if (isWallSupport(behindAbove)) score += 3;
            if (frontState.isAir()) score += 4;
            if (level.getBlockState(pos.relative(dir, 2)).isAir()) score += 2;
            if (dir == hint) score += 2;
            if (dir == hint.getClockWise() || dir == hint.getCounterClockWise()) score += 1;

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }
        return bestDir;
    }

    private static boolean isWallSupport(BlockState state) {
        if (state.isAir()) return false;
        if (state.getBlock() instanceof WallHangingSignBlock
                || state.getBlock() instanceof CeilingHangingSignBlock
                || state.getBlock() instanceof WallSignBlock
                || state.getBlock() instanceof StandingSignBlock) {
            return false;
        }
        return true;
    }

    /** Returns a formatted HUD label when hovering over a settlement shop sign, or null if not a shop sign. */
    public static Component describeSignForHud(SignBlockEntity be) {
        Classification c = classify(be);
        if (c.kind() != Kind.SHOP_3D || c.type() == null) {
            return null;
        }
        String title = c.title();
        if (title == null || title.isBlank()) {
            return Component.literal(c.type().displayTitle);
        }
        if (title.toLowerCase(Locale.ROOT).contains(c.type().displayTitle.toLowerCase(Locale.ROOT).split(" ")[0])) {
            return Component.literal(title);
        }
        return Component.literal(title + " (" + c.type().displayTitle + ")");
    }

    public static Classification classify(SignBlockEntity be) {
        if (be == null) return Classification.VANILLA;
        SignText text = be.getText(SignTextSlot.FRONT);
        String l0 = clean(text.getMessages(false).get(0));
        String l1 = clean(text.getMessages(false).get(1));
        String l2 = clean(text.getMessages(false).get(2));
        String l3 = clean(text.getMessages(false).get(3));
        if (l0.isEmpty() && l1.isEmpty() && l2.isEmpty() && l3.isEmpty()) {
            SignText back = be.getText(SignTextSlot.BACK);
            l0 = clean(back.getMessages(false).get(0));
            l1 = clean(back.getMessages(false).get(1));
            l2 = clean(back.getMessages(false).get(2));
            l3 = clean(back.getMessages(false).get(3));
        }

        String title = (l1 + " " + l2).trim().replaceAll("\\s+", " ");
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        String lowerAll = (l0 + " " + l1 + " " + l2 + " " + l3).toLowerCase(Locale.ROOT);

        // 1. Keep vanilla rendering for notice boards, road signposts, homestead sale signs, jail evidence
        if (lowerAll.contains("notice board") || lowerAll.contains("bounty") || lowerAll.contains("wanted")
                || lowerAll.contains("for sale") || lowerAll.contains("septims") || lowerAll.contains("evidence")
                || lowerAll.contains("confiscated") || lowerAll.contains("belongings")
                || lowerAll.contains("bl)") || l0.contains("←") || l0.contains("→") || l0.contains("↑")) {
            return Classification.VANILLA;
        }

        // 2. Explicit metadata tag on line 3: "nirncraft:<useId>:<palette>"
        if (l3.startsWith("nirncraft:")) {
            String[] parts = l3.split(":");
            String useId = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";
            String paletteId = parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "";
            if (useId.equals("hide") || useId.equals("home") || isResidentialUseId(useId) || isResidentialTitle(lowerTitle)) {
                return Classification.HIDE;
            }
            Culture culture = resolveCulture(be.getBlockPos(), paletteId, lowerTitle);
            ShopSignType byUse = mapUseIdToShopType(useId, lowerTitle);
            if (byUse != null) {
                return new Classification(Kind.SHOP_3D, byUse, culture, title);
            }
        }

        // 3. Detect settlement signs by header icon on line 0 (from Props.shopSign or Builders.signIcon)
        boolean hasSettlementHeader = isSettlementHeader(l0);
        if (!hasSettlementHeader && !(be.getBlockState().getBlock() instanceof WallHangingSignBlock
                || be.getBlockState().getBlock() instanceof CeilingHangingSignBlock)) {
            return Classification.VANILLA;
        }

        // 4. Suppress residential home signs even in already-generated chunks
        if (l0.contains("🏠") || l0.equals("/^\\") || l0.equals("[=]") || isResidentialTitle(lowerTitle)) {
            return Classification.HIDE;
        }

        // 5. Match shop/building type by icon or title keywords
        ShopSignType type = matchTypeFromHeaderOrTitle(l0, lowerTitle);
        if (type != null) {
            Culture culture = resolveCulture(be.getBlockPos(), "", lowerTitle);
            return new Classification(Kind.SHOP_3D, type, culture, title);
        }

        return Classification.VANILLA;
    }

    private static String clean(Component c) {
        return c == null ? "" : c.getString().trim();
    }

    private static boolean isSettlementHeader(String l0) {
        if (l0.isEmpty()) return false;
        return l0.contains("⚒") || l0.contains("⚗") || l0.contains("🍺") || l0.contains("⚖")
                || l0.contains("🏹") || l0.contains("✦") || l0.contains("💎") || l0.contains("✂")
                || l0.contains("🥩") || l0.contains("🍞") || l0.contains("🐟") || l0.contains("🐎")
                || l0.contains("☀") || l0.contains("🛡") || l0.contains("👑") || l0.contains("⚔")
                || l0.contains("🏠") || l0.contains("❖") || l0.contains("⚜") || l0.contains("☥")
                || l0.equals("===") || l0.equals("~ * ~") || l0.equals("( Y )")
                || l0.equals("[ $ ]") || l0.equals("}->{") || l0.equals("* + *") || l0.equals("< * >")
                || l0.equals("8< >8") || l0.equals("[===]") || l0.equals("( # )") || l0.equals("<><")
                || l0.equals("( U )") || l0.equals("+ * +") || l0.equals("[ + ]") || l0.equals("|^|")
                || l0.equals("[ * ]") || l0.equals("\\o/") || l0.equals("[=]") || l0.equals("(O)")
                || l0.equals("/^\\");
    }

    private static boolean isResidentialUseId(String useId) {
        return switch (useId) {
            case "hovel", "cottage", "house", "townhouse", "manor", "farmhouse", "shack",
                 "barn", "warehouse", "sawmill", "smelter", "fence_den", "watchtower" -> true;
            default -> false;
        };
    }

    private static boolean isResidentialTitle(String lowerTitle) {
        if (lowerTitle.isEmpty()) return false;
        if (lowerTitle.contains("house") && !lowerTitle.contains("longhouse") && !lowerTitle.contains("guardhouse")
                && !lowerTitle.contains("warehouse") && !lowerTitle.contains("customs house")) {
            return true;
        }
        if (lowerTitle.contains("cottage") || lowerTitle.contains("manor") || lowerTitle.contains("hovel")
                || lowerTitle.contains("farmhouse") || lowerTitle.contains("townhouse") || lowerTitle.contains("shack")
                || lowerTitle.equals("breezehome") || lowerTitle.equals("honeyside")
                || lowerTitle.equals("hjerim") || lowerTitle.equals("vlindrel hall")) {
            return true;
        }
        return lowerTitle.endsWith(" farm") || lowerTitle.endsWith(" residence") || lowerTitle.endsWith(" home");
    }

    private static ShopSignType mapUseIdToShopType(String useId, String lowerTitle) {
        return switch (useId) {
            case "smithy" -> ShopSignType.SMITHY;
            case "apothecary", "alchemist" -> ShopSignType.APOTHECARY;
            case "inn", "mead_hall" -> ShopSignType.INN;
            case "general", "general_store", "store", "market_shop" -> ShopSignType.GENERAL;
            case "fletcher" -> ShopSignType.FLETCHER;
            case "mage", "enchanter", "bookseller", "mage_tower" -> ShopSignType.MAGE;
            case "jeweler", "jeweller" -> ShopSignType.JEWELER;
            case "tailor", "tannery" -> ShopSignType.TAILOR;
            case "butcher" -> ShopSignType.BUTCHER;
            case "bakery", "mill" -> ShopSignType.BAKERY;
            case "fishery" -> ShopSignType.FISHERY;
            case "stables" -> ShopSignType.STABLES;
            case "temple", "hall_of_dead", "shrine" -> ShopSignType.TEMPLE;
            case "guardhouse", "barracks", "jail" -> ShopSignType.BARRACKS;
            case "keep", "palace", "longhouse" -> ShopSignType.KEEP;
            case "guild_hall", "guild" -> ShopSignType.GUILD;
            default -> matchTypeFromHeaderOrTitle("", lowerTitle);
        };
    }

    private static ShopSignType matchTypeFromHeaderOrTitle(String l0, String lowerTitle) {
        // 1. Check specific title keywords first so named buildings resolve accurately
        if (lowerTitle.contains("barracks") || lowerTitle.contains("guard") || lowerTitle.contains("watch")
                || lowerTitle.contains("jail") || lowerTitle.contains("prison") || lowerTitle.contains("legion")) {
            return ShopSignType.BARRACKS;
        }
        if (lowerTitle.contains("smith") || lowerTitle.contains("forge") || lowerTitle.contains("anvil")
                || lowerTitle.contains("warmaiden") || lowerTitle.contains("iron") || lowerTitle.contains("steel")) {
            return ShopSignType.SMITHY;
        }
        if (lowerTitle.contains("apothecary") || lowerTitle.contains("alchem") || lowerTitle.contains("cure")
                || lowerTitle.contains("pestle") || lowerTitle.contains("phial") || lowerTitle.contains("cauldron")
                || lowerTitle.contains("elgrim") || lowerTitle.contains("arcadia")) {
            return ShopSignType.APOTHECARY;
        }
        if (lowerTitle.contains("inn") || lowerTitle.contains("tavern") || lowerTitle.contains("mead")
                || lowerTitle.contains("mare") || lowerTitle.contains("giant") || lowerTitle.contains("skeever")
                || lowerTitle.contains("barb") || lowerTitle.contains("hearth") || lowerTitle.contains("drink")
                || lowerTitle.contains("netch") || lowerTitle.contains("flagon") || lowerTitle.contains("hroldan")
                || lowerTitle.contains("hadvar")) {
            return ShopSignType.INN;
        }
        if (lowerTitle.contains("fletcher") || lowerTitle.contains("bow") || lowerTitle.contains("huntsman")
                || lowerTitle.contains("arrow")) {
            return ShopSignType.FLETCHER;
        }
        if (lowerTitle.contains("arcane") || lowerTitle.contains("enchant") || lowerTitle.contains("book")
                || lowerTitle.contains("magic") || lowerTitle.contains("spell") || lowerTitle.contains("college")) {
            return ShopSignType.MAGE;
        }
        if (lowerTitle.contains("jewel") || lowerTitle.contains("gem") || lowerTitle.contains("gold")
                || lowerTitle.contains("silver")) {
            return ShopSignType.JEWELER;
        }
        if (lowerTitle.contains("tailor") || lowerTitle.contains("raiment") || lowerTitle.contains("cloth")
                || lowerTitle.contains("fine")) {
            return ShopSignType.TAILOR;
        }
        if (lowerTitle.contains("butcher") || lowerTitle.contains("meat")) {
            return ShopSignType.BUTCHER;
        }
        if (lowerTitle.contains("bakery") || lowerTitle.contains("bread") || lowerTitle.contains("mill")) {
            return ShopSignType.BAKERY;
        }
        if (lowerTitle.contains("fish") || lowerTitle.contains("dock") || lowerTitle.contains("fishery")) {
            return ShopSignType.FISHERY;
        }
        if (lowerTitle.contains("stable")) {
            return ShopSignType.STABLES;
        }
        if (lowerTitle.contains("temple") || lowerTitle.contains("chapel") || lowerTitle.contains("dead")
                || lowerTitle.contains("kynareth") || lowerTitle.contains("mara") || lowerTitle.contains("dibella")
                || lowerTitle.contains("talos") || lowerTitle.contains("arkay") || lowerTitle.contains("shrine")) {
            return ShopSignType.TEMPLE;
        }
        if (lowerTitle.contains("keep") || lowerTitle.contains("palace") || lowerTitle.contains("dragonsreach")
                || lowerTitle.contains("longhouse") || lowerTitle.contains("castle") || lowerTitle.contains("hrothgar")) {
            return ShopSignType.KEEP;
        }
        if (lowerTitle.contains("guild") || lowerTitle.contains("jorrvaskr") || lowerTitle.contains("companion")
                || lowerTitle.contains("bards") || lowerTitle.contains("fighter") || lowerTitle.contains("mages")) {
            return ShopSignType.GUILD;
        }
        if (lowerTitle.contains("general") || lowerTitle.contains("goods") || lowerTitle.contains("trader")
                || lowerTitle.contains("pawn") || lowerTitle.contains("belethor") || lowerTitle.contains("market")
                || lowerTitle.contains("wares") || lowerTitle.contains("pieces") || lowerTitle.contains("company")) {
            return ShopSignType.GENERAL;
        }

        // 2. Fallback to line 0 symbol header
        if (l0.contains("⚒") || l0.equals("===")) return ShopSignType.SMITHY;
        if (l0.contains("⚗") || l0.equals("~ * ~")) return ShopSignType.APOTHECARY;
        if (l0.contains("🍺") || l0.equals("( Y )")) return ShopSignType.INN;
        if (l0.contains("⚖") || l0.equals("[ $ ]")) return ShopSignType.GENERAL;
        if (l0.contains("🏹") || l0.equals("}->{")) return ShopSignType.FLETCHER;
        if (l0.contains("✦") || l0.equals("* + *")) return ShopSignType.MAGE;
        if (l0.contains("💎") || l0.equals("< * >")) return ShopSignType.JEWELER;
        if (l0.contains("✂") || l0.equals("8< >8")) return ShopSignType.TAILOR;
        if (l0.contains("🥩") || l0.equals("[===]")) return ShopSignType.BUTCHER;
        if (l0.contains("🍞") || l0.equals("( # )") || l0.equals("\\o/")) return ShopSignType.BAKERY;
        if (l0.contains("🐟") || l0.equals("<><")) return ShopSignType.FISHERY;
        if (l0.contains("🐎") || l0.equals("( U )")) return ShopSignType.STABLES;
        if (l0.contains("☀") || l0.equals("+ * +")) return ShopSignType.TEMPLE;
        if (l0.contains("🛡") || l0.equals("[ + ]")) return ShopSignType.BARRACKS;
        if (l0.contains("👑") || l0.equals("|^|")) return ShopSignType.KEEP;
        if (l0.contains("⚔") || l0.equals("[ * ]")) return ShopSignType.GUILD;
        return null;
    }

    private static Culture resolveCulture(BlockPos pos, String paletteId, String lowerTitle) {
        if (lowerTitle.contains("legion") || lowerTitle.contains("imperial")) {
            return Culture.IMPERIAL;
        }
        if (paletteId != null && !paletteId.isEmpty()) {
            switch (paletteId) {
                case "nord", "whiterun", "windhelm", "markarth", "riften", "solitude",
                     "winterhold", "marsh", "forest", "stone", "nord_timber", "skaal", "atmoran" -> {
                    return Culture.NORD;
                }
                case "imperial", "imperial_city", "colovian", "nibenese",
                     "colovian_timber", "nibenese_plaster" -> {
                    return Culture.IMPERIAL;
                }
                case "breton", "breton_half_timber", "reachman" -> { return Culture.BRETON; }
                case "redguard", "redguard_stucco" -> { return Culture.REDGUARD; }
                case "dunmer", "hlaalu", "redoran", "telvanni", "velothi", "dunmer_ash" -> { return Culture.DUNMER; }
                case "altmer", "altmer_crystal" -> { return Culture.ALTMER; }
                case "bosmer", "bosmer_pod" -> { return Culture.BOSMER; }
                case "khajiit", "khajiit_adobe" -> { return Culture.KHAJIIT; }
                case "argonian", "argonian_reed" -> { return Culture.ARGONIAN; }
                case "orc", "orc_stronghold" -> { return Culture.ORC; }
            }
        }
        if (pos != null) {
            try {
                Region r = Regions.at(pos.getX(), pos.getZ());
                if (r != null && r.province() != null) {
                    return switch (r.province()) {
                        case "skyrim" -> Culture.NORD;
                        case "cyrodiil" -> Culture.IMPERIAL;
                        case "high_rock" -> Culture.BRETON;
                        case "hammerfell" -> Culture.REDGUARD;
                        case "morrowind" -> Culture.DUNMER;
                        case "summerset" -> Culture.ALTMER;
                        case "valenwood" -> Culture.BOSMER;
                        case "elsweyr" -> Culture.KHAJIIT;
                        case "black_marsh" -> Culture.ARGONIAN;
                        case "orsinium" -> Culture.ORC;
                        default -> Culture.NORD;
                    };
                }
            } catch (Throwable ignored) {
            }
        }
        return Culture.NORD;
    }
}
