package dev.grxm.teams.integration;

import dev.grxm.teams.*;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.util.RandomSource;
import java.nio.file.*;
import java.util.*;

/** Disposable full-pack regression. Invoked only behind IntegrationDriver's two safety guards. */
public final class RandomTicksIntegration {
    private static final UUID OWNER=IntegrationDriver.OWNER;
    private static final TeamState.Chunk CENTER=new TeamState.Chunk("minecraft:the_end",10000,10000);
    private static final ChunkPos OTHER=new ChunkPos(9999,10000);
    private static final TicketType<ChunkPos> TEST=TicketType.create("grxm-random-regression",Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<Long,Integer> samples=new HashMap<>();
    private static final Block PROBE=new Block(BlockBehaviour.Properties.of().randomTicks()) {
        @Override public void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random) {
            samples.merge(new ChunkPos(pos).toLong(),1,Integer::sum);
        }
    };
    private static int ticks, baseline, fills;
    private static long offset;
    private static boolean done;
    private static ServerLevel level;
    private static final List<BlockPos> cauldrons=new ArrayList<>(), crops=new ArrayList<>();
    private static int count(ChunkPos pos){return samples.getOrDefault(pos.toLong(),0);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void install() {
        Registry.register(BuiltInRegistries.BLOCK,new ResourceLocation("grxmteams_integration","random_probe"),PROBE);
        TeamsMod.installTimeSource(()->System.currentTimeMillis()+offset);
        ServerTickEvents.END_SERVER_TICK.register(server->{if(done)return;try{step(server);}catch(Throwable e){
            done=true;TeamsMod.LOGGER.error("RANDOM TICK REGRESSION FAIL",e);
            try{Files.writeString(Path.of("grxmteams-integration-FAIL.txt"),e.toString());}catch(Exception ignored){}
            server.halt(false);
        }});
    }
    private static void speed(MinecraftServer server,int value){level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(value,server);}
    private static void step(MinecraftServer server) throws Exception {
        ticks++;var service=TeamsMod.service();
        check(server.getPlayerList().getPlayerCount()==0,"Real player-free fixture");
        if(ticks<=920)check(service.failure()==null,"Service healthy: "+service.failure());
        ChunkPos center=new ChunkPos(CENTER.x(),CENTER.z());
        if(ticks==1){
            level=server.getLevel(Level.END);
            check(!service.configuration().billing.enabled,"Production economy defaults remain disabled");
            var cfg=service.configuration();cfg.billing.enabled=true;cfg.billing.scalingFactor="1";cfg.billing.teamMemberScaling="1";cfg.billing.periodSeconds=3600;
            cfg.limits.allowedDimensions=new ArrayList<>(List.of("minecraft:overworld","minecraft:the_end"));service.reload(cfg);
            Numismatics.BANK.getOrCreateAccount(OWNER,BankAccount.Type.PLAYER).setBalance(10);
            service.create(OWNER,"Random Tick Regression");service.deposit(OWNER,1);service.addChunk(OWNER,CENTER);
            check(service.balance(OWNER)==0,"Lease genuinely prepaid, no renewal funds");
            // Independent test tickets keep the control and revoked center ticking, so unloaded chunks cannot hide a leak.
            for(var pos:List.of(center,OTHER))level.getChunkSource().addRegionTicket(TEST,pos,2,pos);
            speed(server,0);
        }
        if(ticks==60){
            check(service.activeTickets().contains(CENTER),"Selected paid center installed");
            for(var chunk:List.of(center,OTHER))for(int x=0;x<16;x++)for(int y=96;y<112;y++)for(int z=0;z<16;z++)
                level.setBlock(new BlockPos(chunk.getMinBlockX()+x,y,chunk.getMinBlockZ()+z),PROBE.defaultBlockState(),2);
            for(int x=2;x<10;x++)for(int z=2;z<10;z++){
                var p=new BlockPos(center.getMinBlockX()+x,81,center.getMinBlockZ()+z);crops.add(p);
                level.setBlockAndUpdate(p.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
                level.setBlockAndUpdate(p.above(2),Blocks.GLOWSTONE.defaultBlockState());
            }
            for(int x=2;x<10;x++){
                var p=new BlockPos(center.getMinBlockX()+x,65,center.getMinBlockZ()+12);cauldrons.add(p);
                level.setBlockAndUpdate(p,Blocks.CAULDRON.defaultBlockState());
                level.setBlockAndUpdate(p.above(5),Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION,Direction.DOWN).setValue(PointedDripstoneBlock.THICKNESS,DripstoneThickness.TIP));
                level.setBlockAndUpdate(p.above(6),Blocks.DRIPSTONE_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(p.above(7),Blocks.LAVA.defaultBlockState());
                for(var d:Direction.Plane.HORIZONTAL)level.setBlockAndUpdate(p.above(7).relative(d),Blocks.STONE.defaultBlockState());
            }
        }
        if(ticks==80)for(var p:crops)level.setBlockAndUpdate(p,Blocks.WHEAT.defaultBlockState());
        if(ticks==400){
            check(count(center)==0 && count(OTHER)==0,"randomTickSpeed=0 blocks samples beyond empty-dimension tick 300");
            speed(server,4096);
        }
        if(ticks==401){check(count(center)==4096,"Exactly gamerule samples per randomly ticking section, not zero/double");check(count(OTHER)==0,"Unselected independently ticketed center gets no random samples");}
        if(ticks==600 || ticks==800){
            long full=cauldrons.stream().filter(p->level.getBlockState(p).is(Blocks.LAVA_CAULDRON)).count();
            check(full>0,"Player-free dripstone fills cauldrons at tick "+ticks);fills+=full;
            if(ticks==600)for(var p:cauldrons)level.setBlockAndUpdate(p,Blocks.CAULDRON.defaultBlockState());
            if(ticks==800){
                check(crops.stream().anyMatch(p->level.getBlockState(p).is(Blocks.WHEAT) && level.getBlockState(p).getValue(CropBlock.AGE)==7),"Player-free crops mature");
                check(count(OTHER)==0,"Unselected center still receives no extra simulation");
                // The exact vanilla call used for nearby players marks the chunk; extension must not sample again.
                var chunk=level.getChunkSource().getChunkNow(center.x,center.z);int before=count(center);
                level.tickChunk(chunk,7);check(count(center)==before+7,"Actual vanilla tick samples seven times");
                SelectedChunkRandomTicks.tick(level,chunk);check(count(center)==before+7,"No duplicate random ticks after actual vanilla tickChunk");
                baseline=count(center);speed(server,0);
            }
        }
        if(ticks==820){check(count(center)==baseline,"Live randomTickSpeed=0 stops sampling");speed(server,4096);var cfg=service.configuration();cfg.billing.enabled=false;service.reload(cfg);baseline=count(center);check(service.activeTickets().isEmpty(),"Disable removes paid tickets immediately");}
        if(ticks==840){check(count(center)==baseline,"Billing disabled stops random simulation despite test keepalive");var cfg=service.configuration();cfg.billing.enabled=true;service.reload(cfg);}
        if(ticks==860){check(count(center)>baseline,"Still-paid re-enable resumes");service.removeChunk(OWNER,CENTER);baseline=count(center);}
        if(ticks==880){check(count(center)==baseline,"Selection removal stops simulation");service.addChunk(OWNER,CENTER);}
        if(ticks==900){check(count(center)>baseline,"Re-added existing paid capacity resumes");offset=service.teamOf(OWNER).leaseEnd-System.currentTimeMillis()+1;baseline=count(center);}
        if(ticks==920){
            check(count(center)==baseline,"Expiry stops without waiting for renewal billing cadence");check(service.activeTickets().isEmpty(),"Expired lease removes tickets");
            service.reconcile(service.nowMillis());check(service.teamOf(OWNER).suspended,"Unfunded renewal suspends");
            service.deposit(OWNER,1);
            check(service.activeTickets().contains(CENTER),"New funded lease reinstalls selected ticket");
        }
        if(ticks==940){
            check(count(center)>baseline,"Newly prepaid lease resumes random simulation");
            var fail=TeamService.class.getDeclaredMethod("fail",RuntimeException.class);fail.setAccessible(true);
            fail.invoke(service,new IllegalStateException("Injected isolated random-tick fail-closed regression"));baseline=count(center);
            check(service.activeTickets().isEmpty(),"Service failure immediately removes tickets");
        }
        if(ticks==960){
            check(service.failure()!=null && count(center)==baseline,"Fail-closed stops simulation despite independent ticking ticket");
            check(level.getForcedChunks().isEmpty(),"No vanilla force-loaded chunks");
            Files.writeString(Path.of("grxmteams-integration-random-PASS.txt"),"PASS full-pack Java17: no players; >300 empty ticks; randomTickSpeed zero/live changes; exact 4096 samples; recurring dripstone lava fills="+fills+"; mature wheat; unselected ticketed control=0; actual vanilla tickChunk suppresses duplicate; disable/remove/expiry/failure stop despite independent ticking tickets");
            for(var pos:List.of(center,OTHER))level.getChunkSource().removeRegionTicket(TEST,pos,2,pos);
            done=true;server.halt(false);
        }
    }
}
