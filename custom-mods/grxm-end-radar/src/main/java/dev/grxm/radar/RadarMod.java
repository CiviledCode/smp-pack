package dev.grxm.radar;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.nbt.visitors.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.*;
import net.minecraft.world.level.storage.LevelResource;
import com.mojang.datafixers.util.Either;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.slf4j.*;

public final class RadarMod implements ModInitializer {
    public static final Logger LOG=LoggerFactory.getLogger("grxm-end-radar");
    public static final int RADIUS=1024, DISK_BATCH=16, MAX_GENERATED=512, TIMEOUT_TICKS=2400;
    public static final String ID_TAG="grxmRadarId";
    public static final Item RADAR=Registry.register(BuiltInRegistries.ITEM,new ResourceLocation("grxm_end_radar","radar"),new Item(new Item.Properties().stacksTo(1)) {
        @Override public InteractionResultHolder<ItemStack> use(Level world,Player player,InteractionHand hand) {
            ItemStack stack=player.getItemInHand(hand);
            if(!world.isClientSide && player instanceof ServerPlayer p) begin(p,hand);
            return InteractionResultHolder.sidedSuccess(stack,world.isClientSide);
        }
    });
    private static History history;
    private static String failure;
    private static Job active;
    private static int arrivalCursor;
    // No new job may enter while cancelled I/O/chunk work is still draining.
    private static List<CompletableFuture<?>> draining=List.of();
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            active=null;draining=List.of();failure=null;
            try {history=new History(server.getWorldPath(LevelResource.ROOT).resolve("data/grxm-end-radar.history"));}
            catch(Exception e){failure="History unavailable";LOG.error("Radar disabled; history must not be deleted/reset",e);}
        });
        ServerTickEvents.END_SERVER_TICK.register(RadarMod::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            active=null;draining=List.of();
            if(history!=null)try{history.close();}catch(Exception e){LOG.error("Closing history",e);}
            history=null;
        });
    }
    private static void say(ServerPlayer p,String text){try{p.displayClientMessage(Component.literal(text),false);}catch(Exception e){LOG.warn("Could not deliver radar message",e);}}
    public static UUID identity(ItemStack stack){return stack.hasTag()&&stack.getTag().hasUUID(ID_TAG)?stack.getTag().getUUID(ID_TAG):null;}
    private static double distance(History.Binding b,ServerPlayer p){double dx=b.x*16.0+8-p.getX(),dz=b.z*16.0+8-p.getZ();return dx*dx+dz*dz;}
    private static boolean arrived(History.Binding b,ServerPlayer p){return b.contains(p.getX(),p.getY(),p.getZ());}
    private static void read(ServerPlayer p,History.Binding b)throws Exception {
        if(b.completed){say(p,"End Radar completed. This memento cannot bind another city.");return;}
        if(arrived(b,p)){history.complete(b.radar);say(p,"End City reached. End Radar completed; this memento cannot rebind.");return;}
        double d=distance(b,p);String change=d<b.previous?"Warmer":d>b.previous?"Colder":"Unchanged";
        history.reading(b.radar,d);say(p,DistanceOrder.message(d)+" "+change+".");
    }
    private static void begin(ServerPlayer p,InteractionHand hand) {
        if(p.level().dimension()!=Level.END){say(p,"The End Radar only works in the End.");return;}
        if(failure!=null || history==null){say(p,"End Radar unavailable; radar unchanged.");return;}
        if(!p.isAlive() || p.isSpectator() || p.containerMenu!=p.inventoryMenu || !p.getItemInHand(hand).is(RADAR))return;
        UUID id=identity(p.getItemInHand(hand));
        History.Binding binding=id==null?null:history.binding(id);
        if(binding!=null){try{read(p,binding);}catch(Exception e){failure="History write failed";LOG.error("Reading failed closed",e);say(p,"End Radar unavailable; reading not recorded.");}return;}
        if(history.size()>=History.LIMIT){say(p,"End Radar history full; radar remains unbound.");return;}
        if(active!=null || draining.stream().anyMatch(f->!f.isDone())){say(p,"End Radar busy. Try again shortly; nothing consumed.");return;}
        try {
            if(id==null)p.getItemInHand(hand).getOrCreateTag().putUUID(ID_TAG,UUID.randomUUID());
            active=new Job(p,hand);
            say(p,"Searching within "+RADIUS+" horizontal blocks; keep this radar in hand and stay nearby.");
        } catch(Exception e){LOG.error("Radar admission failed",e);say(p,"End Radar search failed; nothing consumed.");}
    }
    private static CompletableFuture<Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>> request(ServerLevel world,int x,int z,boolean create) {
        // 1.20.1 getChunkFuture MANAGED-BLOCKS if called on the server thread!
        // Its off-thread branch marshals admission to mainThreadProcessor without waiting.
        // Only that explicitly thread-aware API is called here; no world state is read/mutated off-thread.
        return CompletableFuture.supplyAsync(()->world.getChunkSource().getChunkFuture(x,z,ChunkStatus.STRUCTURE_STARTS,create)).thenCompose(f->f);
    }
    private static void tick(MinecraftServer server) {
        // Automatic arrival for carried radars, at most 64 inventory slots per server tick.
        if(history!=null && failure==null && !server.getPlayerList().getPlayers().isEmpty()) {
            var players=server.getPlayerList().getPlayers();int total=players.size()*41;
            for(int n=0;n<Math.min(64,total);n++) {
                int at=Math.floorMod(arrivalCursor++,total);ServerPlayer p=players.get(at/41);int slot=at%41;
                if(!p.isAlive()||p.serverLevel().dimension()!=Level.END)continue;
                ItemStack item=p.getInventory().getItem(slot);if(!item.is(RADAR))continue;
                UUID id=identity(item);History.Binding b=id==null?null:history.binding(id);
                if(b!=null&&!b.completed&&arrived(b,p)) {
                    try{history.complete(id);say(p,"End City reached. End Radar completed; this memento cannot rebind.");}
                    catch(Exception e){failure="History write failed";LOG.error("Arrival failed closed",e);}
                    break; // at most one durable completion write per tick
                }
            }
        }
        if(active==null)return;
        Job job=active;
        try {
            if(!job.valid(server)){finish("End Radar cancelled; nothing consumed.");return;}
            if(++job.ticks>TIMEOUT_TICKS || System.nanoTime()-job.started>120_000_000_000L){finish("End Radar timed out; nothing consumed.");return;}
            if(job.chunkFuture!=null) {
                if(!job.chunkFuture.isDone())return;
                var result=job.chunkFuture.join();job.chunkFuture=null;
                ChunkAccess chunk=result.left().orElseThrow(()->new IllegalStateException("Chunk discovery failed"));
                StructureStart start=chunk.getStartForStructure(job.city);
                if(start!=null && start.isValid() && !history.contains(job.current.x(),job.current.z())) {
                    // Main thread: persist delivered identity before the durable global claim.
                    if(!job.valid(server)){finish("End Radar cancelled; nothing consumed.");return;}
                    PlayerIdentity.persist(job.player);
                    var box=start.getBoundingBox();
                    history.reserve(new History.Binding(job.id,job.current.x(),job.current.z(),box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ(),job.current.squared()));
                    finish(DistanceOrder.message(job.current.squared()));return;
                }
            }
            // A batch is always processed in exact distance order; never accept an out-of-order I/O completion.
            if(!job.batch.isEmpty()) {
                if(job.batch.stream().anyMatch(s->!s.future.isDone()))return;
                while(job.batchIndex<job.batch.size()) {
                    Scan scan=job.batch.get(job.batchIndex++);scan.future.join();
                    var point=scan.point;
                    if(history.contains(point.x(),point.z()))continue;
                    ChunkPos pos=new ChunkPos(point.x(),point.z());
                    var loaded=scan.live.join().left().orElse(null);
                    StructureStart loadedStart=loaded==null?null:loaded.getStartForStructure(job.city);
                    boolean exists=loadedStart!=null && loadedStart.isValid();
                    Tag nbt=scan.visitor.getResult();
                    if(nbt instanceof CompoundTag tag) {
                        var saved=tag.getCompound("structures").getCompound("starts").getCompound("minecraft:end_city");
                        exists |= saved.getString("id").equals("minecraft:end_city");
                    }
                    boolean candidate=exists;
                    if(!candidate) for(StructurePlacement placement:job.placements) {
                        if(placement.isStructureChunk(job.state,point.x(),point.z())) {candidate=true;break;}
                    }
                    if(!candidate)continue;
                    if(++job.generated>MAX_GENERATED){finish("End Radar work limit reached; nothing consumed. Try elsewhere.");return;}
                    job.current=point;
                    // Does not synchronously wait/pump the server. Poll the future on later ticks.
                    job.chunkFuture=request(job.world,point.x(),point.z(),true);
                    return;
                }
                job.batch.clear();job.batchIndex=0;
            }
            if(job.index>=job.points.size()){finish("No never-radar-located End City within "+RADIUS+" horizontal blocks; nothing consumed.");return;}
            for(int i=0;i<DISK_BATCH && job.index<job.points.size();i++) {
                var point=job.points.get(job.index++);
                if(history.contains(point.x(),point.z()))continue;
                var visitor=new CollectFields(new FieldSelector(List.of("structures","starts","minecraft:end_city"),StringTag.TYPE,"id"));
                var disk=job.world.getChunkSource().chunkScanner().scanChunk(new ChunkPos(point.x(),point.z()),visitor);
                var live=request(job.world,point.x(),point.z(),false);
                job.batch.add(new Scan(point,visitor,CompletableFuture.allOf(disk,live),live));
            }
        } catch(Exception e) {
            failure="Search/storage error (restart after investigation)";
            LOG.error("End Radar disabled after uncertain I/O; preserve history for recovery",e);
            finish("End Radar error; nothing consumed. Ask the server operator.");
        }
    }
    private static void finish(String text) {
        Job job=active;if(job==null)return;
        List<CompletableFuture<?>> futures=new ArrayList<>();
        job.batch.forEach(s->futures.add(s.future));if(job.chunkFuture!=null)futures.add(job.chunkFuture);
        draining=futures;active=null;
        if(!job.player.hasDisconnected())say(job.player,text);
    }
    private record Scan(DistanceOrder.Point point,CollectFields visitor,CompletableFuture<Void> future,
        CompletableFuture<Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>> live) {}
    private static final class Job {
        final ServerPlayer player;final ServerLevel world;final InteractionHand hand;final ItemStack stack,copy;
        final int slot;final double x,z;final long started=System.nanoTime();final Structure city;final UUID id;
        final ChunkGeneratorStructureState state;final List<StructurePlacement> placements;
        final List<DistanceOrder.Point> points;final List<Scan> batch=new ArrayList<>();
        int ticks,index,batchIndex,generated;DistanceOrder.Point current;
        CompletableFuture<Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>> chunkFuture;
        Job(ServerPlayer p,InteractionHand hand) {
            player=p;world=p.serverLevel();this.hand=hand;stack=p.getItemInHand(hand);copy=stack.copy();slot=p.getInventory().selected;x=p.getX();z=p.getZ();id=identity(stack);
            var holder=world.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolderOrThrow(BuiltinStructures.END_CITY);
            city=holder.value();state=world.getChunkSource().getGeneratorState();placements=state.getPlacementsForStructure(holder);
            if(placements.size()>8 || placements.stream().anyMatch(v->!(v instanceof RandomSpreadStructurePlacement)))throw new IllegalStateException("Unsupported End City placement");
            points=DistanceOrder.within(x,z,RADIUS);
        }
        boolean valid(MinecraftServer server) {
            return server.getPlayerList().getPlayer(player.getUUID())==player && !player.hasDisconnected() && player.isAlive() && !player.isRemoved()
                && !player.isSpectator() && player.serverLevel()==world && world.dimension()==Level.END
                && player.containerMenu==player.inventoryMenu && player.getInventory().selected==slot
                && player.getItemInHand(hand)==stack && ItemStack.matches(stack,copy)
                && Math.abs(player.getX()-x)<=16 && Math.abs(player.getZ()-z)<=16;
        }
    }
}
