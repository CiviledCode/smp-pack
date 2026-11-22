package dev.grxm.radar.integration;

import dev.grxm.radar.*;
import dev.grxm.radar.integration.mixin.PlayerListAccess;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.mojang.datafixers.util.Either;
import com.mojang.authlib.GameProfile;

/** Opt-in actual Fabric/server test driver. Not included in the production JAR. */
public final class RadarIntegration implements ModInitializer {
    private MinecraftServer server;private ServerLevel end;private Structure city;
    private Fixture a,b;private int step,ticks,index;private String phase;
    private List<DistanceOrder.Point> candidates,valid=new ArrayList<>();
    private CompletableFuture<Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>> pending;
    private DistanceOrder.Point generating;
    private boolean done;
    private final List<String> evidence=new ArrayList<>();
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static Object field(String name)throws Exception {Field f=RadarMod.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static History history()throws Exception{return (History)field("history");}
    private static boolean idle()throws Exception{return field("active")==null;}
    private void mark(String text){evidence.add(text);RadarMod.LOG.info("RADAR TEST PASS: {}",text);}
    @Override public void onInitialize() {
        if(!Boolean.getBoolean("grxm.radar.integration") || !Files.isRegularFile(Path.of("RADAR-TEST-ALLOW")))throw new IllegalStateException("Disposable fixture only: JVM flag AND marker required");
        phase=System.getProperty("grxm.radar.phase","seed");
        if(!Set.of("seed","restart","completed","combined").contains(phase))throw new IllegalArgumentException("Unknown isolated test phase");
        ServerTickEvents.END_SERVER_TICK.register(s->{
            if(done)return;
            try {
                server=s;if(++ticks>6000)throw new AssertionError("Test timeout at step "+step);
                if(ticks==1)setup();else run();
            }catch(Throwable e){done=true;RadarMod.LOG.error("RADAR INTEGRATION FAIL step="+step,e);try{Files.writeString(Path.of("radar-"+phase+"-FAIL.txt"),e.toString());}catch(Exception ignored){}s.halt(false);}
        });
    }
    private void setup()throws Exception {
        check(server.getPlayerList().getPlayerCount()==0,"No real players allowed");
        end=server.getLevel(Level.END);city=end.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY);
        var request=RadarMod.class.getDeclaredMethod("request",ServerLevel.class,int.class,int.class,boolean.class);request.setAccessible(true);
        var asynchronous=(CompletableFuture<?>)request.invoke(null,end,-313,-313,false);
        check(!asynchronous.isDone(),"Chunk API admission must return before main-thread processing, never managedBlock");
        mark("Real chunk-future admission returns immediately without pumping/blocking the server thread");
        a=new Fixture(server,end,"RadarAlice");b=new Fixture(server,end,"RadarBob");
        ((PlayerListAccess)server.getPlayerList()).radarPlayers().put(a.getUUID(),a);
        ((PlayerListAccess)server.getPlayerList()).radarPlayers().put(b.getUUID(),b);
        a.moveTo(-5000,90,-5000);b.moveTo(-5000,90,-5000);
        a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(RadarMod.RADAR));b.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(RadarMod.RADAR));
        check(RadarMod.RADAR.getMaxStackSize()==1,"One physical radar per stack");
        check(Items.ENDER_EYE instanceof EnderEyeItem && Items.ENDER_EYE!=RadarMod.RADAR,"Ordinary eye remains vanilla item");
        var crafting=new TransientCraftingContainer(a.inventoryMenu,3,3);
        for(int i=0;i<9;i++)crafting.setItem(i,new ItemStack(i==4?Items.ENDER_EYE:Items.DIAMOND));
        var recipe=server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,crafting,end).orElseThrow();
        var result=recipe.assemble(crafting,end.registryAccess());check(result.is(RadarMod.RADAR)&&result.getCount()==1,"Actual loaded recipe produces one radar");
        crafting.setItem(4,new ItemStack(Items.ENDER_PEARL));check(!recipe.matches(crafting,end),"Incorrect center rejected");
        mark("Real RecipeManager: 8 diamonds + central Eye -> 1 registered radar; wrong center rejected; vanilla Eye class unchanged");
        if(phase.equals("combined")){setupCombined();return;}
        if(phase.equals("restart")||phase.equals("completed")) {
            check(history().size()==2,"History count survived actual JVM restart");
            valid=new ArrayList<>();for(String line:Files.readAllLines(Path.of("natural-cities.csv"))){String[] p=line.split(",");valid.add(new DistanceOrder.Point(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Double.parseDouble(p[2])));}
            check(history().contains(valid.get(0).x(),valid.get(0).z())&&history().contains(valid.get(1).x(),valid.get(1).z()),"Exact global claims survived restart");
            var saved=net.minecraft.nbt.NbtIo.readCompressed(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(a.getUUID()+".dat").toFile());
            var inventory=saved.getList("Inventory",10);ItemStack restored=ItemStack.EMPTY;
            for(int i=0;i<inventory.size();i++){var item=ItemStack.of(inventory.getCompound(i));UUID id=RadarMod.identity(item);if(item.is(RadarMod.RADAR)&&id!=null&&history().binding(id)!=null){restored=item;break;}}
            check(!restored.isEmpty()&&history().binding(RadarMod.identity(restored))!=null,"Binding identity recovered from actual durable vanilla player inventory");
            a.setItemInHand(InteractionHand.MAIN_HAND,restored);
            if(phase.equals("completed")) {
                check(history().binding(RadarMod.identity(restored)).completed,"Completion survived second actual restart");
                use(a);check(a.last().contains("memento")&&idle()&&history().size()==2,"Completed identity cannot rebind after restart");
                a.getInventory().setItem(2,restored);a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(RadarMod.RADAR));use(a);step=50;return;
            }
            mark("Actual restart: exact global claims and delivered item identity retained outside DIM1");use(a);step=40;return;
        }
        check(history().size()==0,"Fresh world history");
        var holder=end.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolderOrThrow(BuiltinStructures.END_CITY);
        var state=end.getChunkSource().getGeneratorState();var placements=state.getPlacementsForStructure(holder);
        candidates=DistanceOrder.within(-5000,-5000,1024).stream().filter(p->placements.stream().anyMatch(v->v.isStructureChunk(state,p.x(),p.z()))).toList();
        check(candidates.size()<512,"Bounded independent structure enumeration");step=1;
    }
    private void use(Fixture p){RadarMod.RADAR.use(p.level(),p,InteractionHand.MAIN_HAND);}
    private boolean start(Fixture p)throws Exception {use(p);return !idle();}
    private void run()throws Exception {
        if(phase.equals("combined")){runCombined();return;}
        if(step==1){
            if(pending!=null){if(!pending.isDone())return;var chunk=pending.join().left().orElseThrow();var start=chunk.getStartForStructure(city);if(start!=null&&start.isValid())valid.add(generating);pending=null;}
            if(index<candidates.size()){generating=candidates.get(index++);pending=end.getChunkSource().getChunkFuture(generating.x(),generating.z(),ChunkStatus.STRUCTURE_STARTS,true);return;}
            check(valid.size()>=2,"At least two real naturally generated cities in negative-coordinate search circle, got "+valid.size());
            // Give the closest city a vanilla exploration reference: radar MUST NOT treat that as its own history.
            var first=end.getChunkSource().getChunk(valid.get(0).x(),valid.get(0).z(),ChunkStatus.STRUCTURE_STARTS,true).getStartForStructure(city);first.addReference();
            StringBuilder csv=new StringBuilder();for(var p:valid)csv.append(p.x()).append(',').append(p.z()).append(',').append(p.squared()).append('\n');Files.writeString(Path.of("natural-cities.csv"),csv);
            mark("Exhaustive current-placement oracle: "+valid.size()+" natural valid starts; closest has vanilla reference, not radar history");
            a.setServerLevel(server.overworld());use(a);check(a.getMainHandItem().getCount()==1&&idle(),"Outside End never consumes");a.setServerLevel(end);
            var eye=new ItemStack(Items.ENDER_EYE,2);a.setItemInHand(InteractionHand.MAIN_HAND,eye);Items.ENDER_EYE.use(end,a,InteractionHand.MAIN_HAND);check(eye.getCount()==2&&idle(),"Ordinary eye does not start radar");a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(RadarMod.RADAR));
            use(a);use(a);use(b);check(b.last().contains("busy"),"Overlapping second player rejected with no queue");step=2;return;
        }
        if(step==2&&idle()){
            check(a.getMainHandItem().getCount()==1 && b.getMainHandItem().getCount()==1,"Successful binding and overlapping use consume nothing");
            check(history().size()==1&&history().contains(valid.get(0).x(),valid.get(0).z()),"Closest eligible city globally reserved");distance(a,valid.get(0));mark("Real use binds nearest valid negative-coordinate start, ignores vanilla skipKnown reference, distance-only message, no consumption; repeated/overlapping use rejected");
            use(b);step=3;return;
        }
        if(step==3&&idle()){
            check(b.getMainHandItem().getCount()==1&&history().size()==2&&history().contains(valid.get(1).x(),valid.get(1).z()),"Next radar receives next nearest, never duplicate");distance(b,valid.get(1));mark("Second radar: unique next-closest allocation, no consumption");
            UUID id=RadarMod.identity(a.getMainHandItem());var binding=history().binding(id);use(a);check(a.last().endsWith("Unchanged.")&&idle()&&history().size()==2,"Bound reading does not search or allocate");
            double tx=binding.x*16.0+8,tz=binding.z*16.0+8;
            a.moveTo((a.getX()+tx)/2,200,(a.getZ()+tz)/2);use(a);check(a.last().endsWith("Warmer."),"Move closer -> warmer");
            a.moveTo(-5000,200,-5000);use(a);check(a.last().endsWith("Colder."),"Move away -> colder");
            ItemStack transferred=a.getMainHandItem();a.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);ItemStack bRadar=b.getMainHandItem();b.setItemInHand(InteractionHand.MAIN_HAND,transferred);use(b);check(b.last().endsWith("Unchanged.")&&history().binding(RadarMod.identity(b.getMainHandItem())).radar.equals(id),"Transfer retains target and prior reading");b.setItemInHand(InteractionHand.MAIN_HAND,bRadar);
            mark("Reusable same-target readings: unchanged/warmer/colder and transfer; no new city allocation");
            a.getInventory().setItem(2,transferred);
            a.moveTo(-5000,90,-5000);a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(RadarMod.RADAR));
            check(start(a),"Cancellation fixture actually admitted");a.getInventory().selected=1;step=4;return;
        }
        if(step==4&&idle()){check(a.getInventory().getItem(0).getCount()==1&&history().size()==2,"Slot swap cancels with no loss");a.getInventory().selected=0;if(!start(a))return;a.setItemInHand(InteractionHand.MAIN_HAND,a.getMainHandItem().copy());step=5;return;}
        if(step==5&&idle()){check(a.getMainHandItem().getCount()==1&&history().size()==2,"Replacement stack cancels");if(!start(a))return;a.setHealth(0);step=6;return;}
        if(step==6&&idle()){check(a.getMainHandItem().getCount()==1&&history().size()==2,"Death cancels");a.setHealth(20);if(!start(a))return;((PlayerListAccess)server.getPlayerList()).radarPlayers().remove(a.getUUID());step=7;return;}
        if(step==7&&idle()){check(a.getMainHandItem().getCount()==1&&history().size()==2,"Disconnect cancels");((PlayerListAccess)server.getPlayerList()).radarPlayers().put(a.getUUID(),a);if(!start(a))return;a.setServerLevel(server.overworld());step=8;return;}
        if(step==8&&idle()){check(a.getMainHandItem().getCount()==1&&history().size()==2,"Dimension departure cancels");a.setServerLevel(end);if(!start(a))return;a.moveTo(-4900,90,-5000);step=9;return;}
        if(step==9&&idle()){check(a.getMainHandItem().getCount()==1&&history().size()==2,"Movement cancels");a.moveTo(-5000,90,-5000);use(a);Object job=field("active");if(job==null)return;Field t=job.getClass().getDeclaredField("ticks");t.setAccessible(true);t.setInt(job,RadarMod.TIMEOUT_TICKS);step=10;return;}
        if(step==10&&idle()){
            check(a.last().contains("timed out")&&a.getMainHandItem().getCount()==1&&history().size()==2,"Timeout no consume");mark("Cancellation: slot/stack replacement, death, disconnect, End departure, movement, timeout all preserve item and history");
            // Simulate an old-layout persisted valid start at a currently non-candidate chunk.
            var oldPoint=DistanceOrder.within(-5000,-5000,1024).stream().filter(p->!candidates.contains(p)&&!historyUnchecked(p)).findFirst().orElseThrow();
            var template=end.getChunkSource().getChunk(valid.get(0).x(),valid.get(0).z(),ChunkStatus.STRUCTURE_STARTS,true).getStartForStructure(city);
            ChunkAccess extra=end.getChunkSource().getChunk(oldPoint.x(),oldPoint.z(),ChunkStatus.FULL,true);
            if(extra instanceof ImposterProtoChunk imposter)extra=imposter.getWrapped();
            extra.setStartForStructure(city,new StructureStart(city,new ChunkPos(oldPoint.x(),oldPoint.z()),0,new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(template.getPieces())));extra.setUnsaved(true);end.getChunkSource().save(true);
            check(extra.getStartForStructure(city)!=null&&extra.getStartForStructure(city).isValid(),"Valid old-layout fixture really installed");
            RadarMod.LOG.info("TEST old-layout start point={} chunk={} status={}",oldPoint,extra.getClass().getName(),extra.getStatus());
            check(start(a),"Work-budget probe admitted");Object j=field("active");Field g=j.getClass().getDeclaredField("generated");g.setAccessible(true);g.setInt(j,RadarMod.MAX_GENERATED);step=12;return;
        }
        if(step==12&&idle()){check(a.last().contains("work limit")&&a.getMainHandItem().getCount()==1&&history().size()==2,"Generation budget error never reserves or consumes");mark("Generation request cap returns explicit work-limit error, no binding or item loss");history().close();check(start(a),"Persistence-failure probe admitted");step=11;return;}
        if(step==11&&idle()){check(a.last().contains("error")&&a.getMainHandItem().getCount()==1&&history().size()==2,"Real closed-channel persistence failure does not consume or publish claim; actual="+a.last()+" count="+a.getMainHandItem().getCount()+" history="+history().size());mark("Reservation fsync/write failure: real closed history channel, no item consumption and no third claim; service fails closed");finish();return;}
        if(step==40&&idle()){
            check(a.getMainHandItem().getCount()==1&&history().size()==2&&a.last().endsWith("Unchanged."),"Post-restart same bound item and previous reading");mark("Post-restart bound radar reads its original target with persisted previous distance; no new allocation");
            var binding=history().binding(RadarMod.identity(a.getMainHandItem()));a.moveTo(binding.minX+0.5,binding.minY+0.5,binding.minZ+0.5);server.getPlayerList().getPlayers().add(a);step=42;return;
        }
        if(step==42&&idle()) {var binding=history().binding(RadarMod.identity(a.getMainHandItem()));if(!binding.completed)return;use(a);check(a.last().contains("memento")&&history().size()==2,"Automatic actual-bounds arrival creates permanent non-rebinding memento");server.getPlayerList().getPlayers().clear();mark("Automatic arrival in persisted real StructureStart bounding box completes radar; city remains reserved and memento cannot rebind");a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(RadarMod.RADAR));a.moveTo(0,90,0);use(a);step=41;return;}
        if(step==41&&idle()) {check(a.last().contains("No never-radar-located")&&a.last().contains("1024")&&a.getMainHandItem().getCount()==1&&history().size()==2,"Bounded no-result does not consume or bind");mark("Complete bounded central-island search: explicit no-result within 1024; radar remains unbound");finish();}
        if(step==50&&idle()) {
            var binding=history().binding(RadarMod.identity(a.getMainHandItem()));
            check(binding!=null&&binding.x==-313&&binding.z==-313&&history().size()==3,"New radar selects persisted old-layout start, never a previously claimed/completed city");
            check(a.last().equals(DistanceOrder.message(0))&&a.getMainHandItem().getCount()==1,"Old-layout closest start yields distance only, not consumption");
            mark("Second actual restart: completed memento stays completed; new radar binds nearest saved old-layout city, not either previous reservation");finish();
        }
    }
    /** Fresh seed, no injected starts; oracle does not call production distance enumeration. */
    private void setupCombined()throws Exception {
        check(end.getSeed()==8675309L && history().size()==0,"Fresh combined seed/history");
        check(end.getChunkSource().getGenerator().getClass().getName().equals("org.betterx.bclib.api.v2.generator.BCLChunkGenerator"),"Actual BetterEnd BCL generator");
        var state=end.getChunkSource().getGeneratorState();
        var holder=end.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolderOrThrow(BuiltinStructures.END_CITY);
        var placements=state.getPlacementsForStructure(holder);
        check(placements.size()==1,"Single active placement");
        var placement=(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)placements.get(0);
        check(placement.spacing()==14 && placement.separation()==7,"Density active placement 14/7");
        a.moveTo(2000,200,2000);b.moveTo(2000,200,2000);
        candidates=new ArrayList<>();int circle=0;
        // Deliberately broad independent grid; exact distance, circle, and tie ordering.
        for(int x=50;x<=200;x++)for(int z=50;z<=200;z++){
            double dx=x*16.0+8-2000,dz=z*16.0+8-2000,d=dx*dx+dz*dz;
            if(d>1024.0*1024)continue;circle++;
            if(placement.isStructureChunk(state,x,z))candidates.add(new DistanceOrder.Point(x,z,d));
        }
        candidates.sort(Comparator.comparingDouble(DistanceOrder.Point::squared).thenComparingInt(DistanceOrder.Point::x).thenComparingInt(DistanceOrder.Point::z));
        mark("Combined fresh seed 8675309; BCLChunkGenerator; active density 14/7; independent exhaustive circle="+circle+" placement candidates="+candidates.size());step=100;
    }
    private void runCombined()throws Exception {
        if(step==100){
            if(pending!=null){if(!pending.isDone())return;var chunk=pending.join().left().orElseThrow();var start=chunk.getStartForStructure(city);if(start!=null&&start.isValid())valid.add(generating);pending=null;}
            if(index<candidates.size()){generating=candidates.get(index++);pending=end.getChunkSource().getChunkFuture(generating.x(),generating.z(),ChunkStatus.STRUCTURE_STARTS,true);return;}
            check(valid.size()>=3,"At least three natural valid cities, got "+valid.size());
            StringBuilder csv=new StringBuilder("chunk_x,chunk_z,squared_distance,pieces,bounds\n");
            for(var p:valid){var start=end.getChunkSource().getChunk(p.x(),p.z(),ChunkStatus.STRUCTURE_STARTS,true).getStartForStructure(city);csv.append(p.x()).append(',').append(p.z()).append(',').append(p.squared()).append(',').append(start.getPieces().size()).append(',').append(start.getBoundingBox()).append('\n');}
            Files.writeString(Path.of("combined-natural-cities.csv"),csv);
            mark("Independent exhaustive oracle: "+valid.size()+" natural valid starts, sorted by exact squared distance and chunk tie break");
            check(start(a),"First search admitted");step=101;return;
        }
        if(step==101&&idle()){
            check(history().size()==1&&history().contains(valid.get(0).x(),valid.get(0).z()),"Nearest oracle city bound");distance(a,valid.get(0));check(a.getMainHandItem().getCount()==1,"No consumption");
            var binding=history().binding(RadarMod.identity(a.getMainHandItem()));check(binding!=null&&!binding.completed,"Active permanent binding");
            use(a);check(a.last().endsWith("Unchanged."),"Same-position reading");
            a.moveTo((2000+binding.x*16.0+8)/2,200,(2000+binding.z*16.0+8)/2);use(a);check(a.last().endsWith("Warmer."),"Same target warmer");
            a.moveTo(2000,200,2000);use(a);check(a.last().endsWith("Colder.")&&history().size()==1,"Same target colder, no new claim");
            mark("Nearest natural oracle target; max-stack-one retained; same-target unchanged/warmer/colder without new allocation");check(start(b),"Second search admitted");step=102;return;
        }
        if(step==102&&idle()){
            check(history().size()==2&&history().contains(valid.get(1).x(),valid.get(1).z()),"Second radar excludes reserved nearest");distance(b,valid.get(1));check(b.getMainHandItem().getCount()==1,"Second radar retained");
            var binding=history().binding(RadarMod.identity(a.getMainHandItem()));
            var natural=end.getChunkSource().getChunk(binding.x,binding.z,ChunkStatus.STRUCTURE_STARTS,true).getStartForStructure(city).getBoundingBox();
            check(binding.minX==natural.minX()&&binding.minY==natural.minY()&&binding.minZ==natural.minZ()&&binding.maxX==natural.maxX()&&binding.maxY==natural.maxY()&&binding.maxZ==natural.maxZ(),"Persisted bounds equal actual natural city");
            a.moveTo(binding.minX+0.5,binding.maxY+1.5,binding.minZ+0.5);use(a);check(!binding.completed,"Above actual city vertical bound does not complete");
            server.getPlayerList().getPlayers().add(a);step=103;return;
        }
        if(step==103){
            var binding=history().binding(RadarMod.identity(a.getMainHandItem()));check(!binding.completed,"Automatic scan outside vertical bounds does not complete");
            a.moveTo(binding.minX+0.5,binding.minY+0.5,binding.minZ+0.5);step=104;return;
        }
        if(step==104){
            var binding=history().binding(RadarMod.identity(a.getMainHandItem()));if(!binding.completed)return;
            use(a);check(a.last().contains("memento")&&a.getMainHandItem().getCount()==1&&history().size()==2,"Completed memento cannot rebind");
            server.getPlayerList().getPlayers().clear();
            mark("Second radar excludes reserved city; natural bounds exactly retained; outside vertical bounds rejected; inside automatic completion and permanent memento");
            a.getInventory().setItem(2,a.getMainHandItem());a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(RadarMod.RADAR));a.moveTo(2000,200,2000);check(start(a),"Later fresh radar admitted");step=105;return;
        }
        if(step==105&&idle()){
            check(history().size()==3&&history().contains(valid.get(2).x(),valid.get(2).z()),"Later fresh radar excludes completed AND reserved cities");check(a.last().equals(DistanceOrder.message(valid.get(2).squared())),"Third oracle exact distance-only reading");check(a.getMainHandItem().getCount()==1,"Later radar retained");
            mark("Later new radar binds third nearest natural oracle city: both completed and still-active claims excluded permanently");finish();
        }
    }
    private static boolean historyUnchecked(DistanceOrder.Point p){try{return history().contains(p.x(),p.z());}catch(Exception e){throw new RuntimeException(e);}}
    private void distance(Fixture p,DistanceOrder.Point point){check(p.last().equals(DistanceOrder.message(point.squared())),"Exact rounded distance only");check(p.messages.stream().filter(m->m.startsWith("End City:")).allMatch(m->m.matches("End City: [0-9]+ horizontal blocks away\\.")),"No coordinates, direction, hover/click components");}
    private void finish()throws Exception {
        Files.write(Path.of("radar-"+phase+"-PASS.txt"),evidence);done=true;
        ((PlayerListAccess)server.getPlayerList()).radarPlayers().clear();server.getPlayerList().getPlayers().clear();server.saveEverything(true,true,true);server.halt(false);
    }
    private static final class Capture extends Connection {
        Capture(){super(PacketFlow.SERVERBOUND);}
        @Override public boolean isConnected(){return true;}
        @Override public void send(net.minecraft.network.protocol.Packet<?> p){}
        @Override public void send(net.minecraft.network.protocol.Packet<?> p,net.minecraft.network.PacketSendListener l){}
    }
    private static final class Fixture extends ServerPlayer {
        final List<String> messages=new ArrayList<>();
        Fixture(MinecraftServer s,ServerLevel w,String name){super(s,w,new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name));connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(s,new Capture(),this);}
        @Override public void displayClientMessage(Component c,boolean actionBar){check(c.getStyle().getHoverEvent()==null&&c.getStyle().getClickEvent()==null&&c.getSiblings().isEmpty(),"Plain literal component only");messages.add(c.getString());}
        String last(){return messages.isEmpty()?"":messages.get(messages.size()-1);}
    }
}
