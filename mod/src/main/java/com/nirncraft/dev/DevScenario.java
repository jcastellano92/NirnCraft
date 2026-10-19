package com.nirncraft.dev;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Disposable, named gameplay actors; real server entities and normal spell setup.
 * Never kills by a broad entity selector and never directly dispatches visual FX. */
final class DevScenario {
    private record Actor(LivingEntity entity,float initial,long started,BlockPos goal) {}
    private static final Map<ServerLevel,Map<String,Actor>> ACTORS=new WeakHashMap<>();

    static void clear() {
        for(var actors:ACTORS.values()) for(var actor:actors.values()) actor.entity.discard();
        ACTORS.clear();
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("scenario").then(Commands.argument("arguments",StringArgumentType.greedyString())
                .executes(c->run(c.getSource(),StringArgumentType.getString(c,"arguments"))));
    }

    private static int run(CommandSourceStack src,String arguments) {
        String[] a=arguments.trim().split("\\s+");
        try {
            if(a.length==2 && a[0].equals("difficulty")) {
                var difficulty=net.minecraft.world.Difficulty.byName(a[1]);
                if(difficulty==null) throw new IllegalArgumentException("Unknown difficulty");
                src.getServer().setDifficulty(difficulty,true);
                src.sendSuccess(()->Component.literal("SCENARIO DIFFICULTY requested="+difficulty.getSerializedName()
                        +" world="+src.getServer().getWorldData().getDifficulty().getSerializedName()
                        +" level="+src.getLevel().getDifficulty().getSerializedName()),false);return 1;
            }
            if(a.length==2 && a[0].equals("equip")) {
                var player=src.getPlayerOrException();
                if(com.nirncraft.magic.spell.Spells.byId(a[1])==null) throw new IllegalArgumentException("Unknown spell");
                com.nirncraft.magic.MagicData.learn(player,a[1]);
                if(!com.nirncraft.magic.Hands.equipRightSpell(player,a[1])) throw new IllegalArgumentException("Could not equip spell");
                com.nirncraft.combat.Sheathe.setSheathed(player,false);
                src.sendSuccess(()->Component.literal("SCENARIO EQUIPPED "+a[1]+"; cast through the normal client packet"),false);return 1;
            }
            if(a.length<2 || !a[1].matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Safe actor tag required");
            var actors=ACTORS.computeIfAbsent(src.getLevel(),k->new LinkedHashMap<>());
            if(a[0].equals("spawn") && (a.length==6 || a.length==7)) {
                if(actors.containsKey(a[1]) || actors.size()>=32) throw new IllegalArgumentException("Actor exists or32-actor limit reached");
                var type=BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(a[2])).orElseThrow();
                double x=Double.parseDouble(a[3]),y=Double.parseDouble(a[4]),z=Double.parseDouble(a[5]);
                var position=BlockPos.containing(x,y,z);
                if(!Double.isFinite(x+y+z) || !src.getLevel().isInWorldBounds(position) || !src.getLevel().hasChunkAt(position))
                    throw new IllegalArgumentException("Actor location must be finite and loaded");
                if(type==net.minecraft.world.entity.EntityTypes.VILLAGER || type==net.minecraft.world.entity.EntityTypes.WANDERING_TRADER)
                    throw new IllegalArgumentException("Use nirncraft:npc; vanilla villagers are converted and cannot remain tracked fixtures");
                var entity=type.create(src.getLevel(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
                if(!(entity instanceof LivingEntity living)) throw new IllegalArgumentException("Living entity required");
                living.setPos(x,y,z);
                living.addTag("nirncraft_qa_fixture");
                living.addTag("nirncraft_qa_"+a[1]);
                if(!src.getLevel().noCollision(living,living.getBoundingBox())) throw new IllegalArgumentException("Actor location collides");
                if(living instanceof Mob mob) { mob.setPersistenceRequired();mob.setNoAi(a.length<7 || !a[6].equals("active")); }
                if(!src.getLevel().addFreshEntity(living)) throw new IllegalStateException("Actor spawn rejected");
                actors.put(a[1],new Actor(living,living.getHealth(),src.getLevel().getGameTime(),null));
                src.sendSuccess(()->Component.literal("SCENARIO SPAWNED "+a[1]+" uuid="+living.getUUID()+" health="+living.getHealth()),false);return 1;
            }
            Actor actor=actors.get(a[1]);
            if(actor==null) throw new IllegalArgumentException("No tracked actor: "+a[1]);
            if(a[0].equals("move") && a.length==5) {
                if(!(actor.entity instanceof Mob mob)) throw new IllegalArgumentException("Mob navigation required");
                BlockPos goal=new BlockPos(Integer.parseInt(a[2]),Integer.parseInt(a[3]),Integer.parseInt(a[4]));
                if(goal.distSqr(mob.blockPosition())>64*64 || !src.getLevel().hasChunkAt(goal))
                    throw new IllegalArgumentException("Goal must be loaded and within64 blocks");
                mob.setNoAi(false);
                if(!mob.getNavigation().moveTo(goal.getX()+0.5,goal.getY(),goal.getZ()+0.5,0.8))
                    throw new IllegalArgumentException("Navigation rejected goal");
                actors.put(a[1],new Actor(mob,actor.initial,actor.started,goal));
                src.sendSuccess(()->Component.literal("SCENARIO MOVING "+a[1]+" toward "+goal.toShortString()),false);return 1;
            }
            if(a[0].equals("cleanup") && a.length==2) {
                actor.entity.discard();actors.remove(a[1]);src.sendSuccess(()->Component.literal("SCENARIO CLEANED "+a[1]),false);return 1;
            }
            if(a[0].equals("observe") && a.length==2) {
                var e=actor.entity;Map<String,Object> data=new LinkedHashMap<>();
                data.put("tag",a[1]);data.put("uuid",e.getUUID().toString());data.put("entity",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                data.put("initialHealth",actor.initial);data.put("health",e.getHealth());data.put("healthLost",actor.initial-e.getHealth());
                data.put("alive",e.isAlive());data.put("removed",e.isRemoved());data.put("position",java.util.List.of(e.getX(),e.getY(),e.getZ()));
                data.put("ticks",src.getLevel().getGameTime()-actor.started);data.put("dimension",src.getLevel().dimension().identifier().toString());
                data.put("noAI",e instanceof Mob mob && mob.isNoAi());
                data.put("onGround",e.onGround());
                if(actor.goal!=null) {
                    double distance=e.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(actor.goal));
                    data.put("goalDistance",distance);data.put("goalReached",distance<1.0);
                    data.put("navigationDone",e instanceof Mob mob && mob.getNavigation().isDone());
                }
                var attacker=e.getLastHurtByMob();
                data.put("lastAttacker",attacker==null?"":attacker.getUUID().toString());
                data.put("damageFromObserver",attacker!=null && attacker==src.getEntity());
                data.put("difficulty",src.getLevel().getDifficulty().getSerializedName());
                if(src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                    var ray=com.nirncraft.magic.Targeting.ray(player,12,other->com.nirncraft.magic.Targeting.canHarm(player,other));
                    data.put("rayTarget",ray.entity()==null?"":ray.entity().getUUID().toString());
                    data.put("rayHitBlock",ray.hitBlock());data.put("rayEnd",java.util.List.of(ray.end().x,ray.end().y,ray.end().z));
                    data.put("observerPosition",java.util.List.of(player.getX(),player.getY(),player.getZ()));
                    data.put("observerSpell",com.nirncraft.magic.MagicData.selectedSpell(player));
                    data.put("observerSpectator",player.isSpectator());data.put("observerSheathed",com.nirncraft.combat.Sheathe.isSheathed(player));
                    data.put("observerMainItem",player.getMainHandItem().toString());
                }
                Path folder=Path.of(DevServer.DIR).resolve("scenarios");Files.createDirectories(folder);
                Files.writeString(folder.resolve(a[1]+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(data));
                String message="SCENARIO "+(e.getHealth()<actor.initial?"HIT ":"NO_HIT ")+a[1]+" health="+e.getHealth()+" lost="+(actor.initial-e.getHealth());
                src.sendSuccess(()->Component.literal(message),false);return 1;
            }
            throw new IllegalArgumentException("Use equip SPELL | spawn TAG ENTITY X Y Z [active] | observe TAG | cleanup TAG");
        } catch(Exception e) {
            // logged too: the harness reads a test's log for failures it can name (E-CMD-FAILED)
            com.nirncraft.NirnCraft.LOGGER.warn("Scenario failed: {} ({})",arguments,e.toString());
            src.sendFailure(Component.literal("Scenario failed: "+e));return 0;
        }
    }
}
