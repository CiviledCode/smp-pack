package dev.grxm.teams.integration;

import dev.grxm.teams.*;
import dev.grxm.teams.TeamState.*;
import dev.grxm.teams.mixin.GlobalBankManagerAccessor;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import java.nio.file.*;
import java.util.*;

/** NEVER distribute with the pack. Requires BOTH a JVM opt-in and a filesystem safety marker. */
public final class IntegrationDriver implements ModInitializer {
    public static final UUID OWNER=UUID.fromString("7c1f79b8-a7a6-43b0-bb88-f2a8124c0001");
    public static final UUID MEMBER=UUID.fromString("7c1f79b8-a7a6-43b0-bb88-f2a8124c0002");
    public static final UUID OUTSIDER=UUID.fromString("7c1f79b8-a7a6-43b0-bb88-f2a8124c0003");
    private static final Chunk FIRST=new Chunk("minecraft:the_end",10000,10000);
    private static final Chunk SECOND=new Chunk("minecraft:the_end",10001,10000);
    private static final BlockPos FURNACE=new BlockPos(160008,80,160008);
    private static final Path RESULT=Path.of("grxmteams-integration-result.json");
    private net.minecraft.world.entity.Entity tickingEntity;
    private double lateAngle;
    private final BlockPos MOTOR=FURNACE.offset(3,0,0), BEARING=FURNACE.offset(4,0,0);
    private net.minecraft.server.level.ServerLevel remote(MinecraftServer server){return server.getLevel(net.minecraft.world.level.Level.END);}
    private String phase;
    private int ticks;
    private boolean done;
    private long firstTick;
    private long timeOffset;
    private static void check(boolean valid,String message) { if(!valid)throw new AssertionError(message); }
    private static void denied(Runnable call,String message) { boolean denied=false; try { call.run(); } catch(IllegalArgumentException e) { denied=true; } check(denied,message); }
    @Override public void onInitialize() {
        if(!Boolean.getBoolean("grxmteams.integration") || !Files.isRegularFile(Path.of("grxmteams-integration-ALLOW")))throw new IllegalStateException("ISOLATED TEST MOD: requires -Dgrxmteams.integration=true AND grxmteams-integration-ALLOW file; REMOVE THIS JAR FROM PRODUCTION");
        phase=System.getProperty("grxmteams.integration.phase","seed");
        TeamsMod.installTimeSource(()->Math.addExact(System.currentTimeMillis(),timeOffset));
        if(!Set.of("seed","verify","expire","corrupt","bazaar","bazaar-verify","cut-prepared","cut-player","cut-committed","cut-failed-commit","damage-native","recover-cut").contains(phase))throw new IllegalArgumentException("Unknown integration phase");
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(done)return;
            try {
                check(server.getPlayerList().getPlayerCount()==0,"Integration fixture requires no players");
                if(++ticks==1) { firstTick=System.currentTimeMillis(); start(server); }
                if(phase.equals("seed")) {
                    if(ticks==60) {
                        check(TeamsMod.service().activeTickets().contains(FIRST),"Custom ticket admitted");
                        remote(server).setBlockAndUpdate(FURNACE,Blocks.FURNACE.defaultBlockState());
                        var be=(AbstractFurnaceBlockEntity)remote(server).getBlockEntity(FURNACE);
                        check(be!=null,"Furnace placed in remote selected chunk");
                        be.setItem(0,new ItemStack(Items.IRON_ORE)); be.setItem(1,new ItemStack(Items.COAL)); be.setChanged();
                        String p=MOTOR.getX()+" "+MOTOR.getY()+" "+MOTOR.getZ();String b=BEARING.getX()+" "+BEARING.getY()+" "+BEARING.getZ();
                        var source=server.createCommandSourceStack().withLevel(remote(server)).withSuppressedOutput();
                        check(server.getCommands().performPrefixedCommand(source,"setblock "+p+" create:creative_motor[facing=east]")>0,"Place actual Create motor");
                        check(server.getCommands().performPrefixedCommand(source,"setblock "+b+" create:mechanical_bearing[facing=east]")>0,"Place actual Create bearing");
                        remote(server).setBlockAndUpdate(BEARING.east(),Blocks.STONE.defaultBlockState());
                        tickingEntity=new net.minecraft.world.entity.decoration.ArmorStand(remote(server),FURNACE.getX()+.5,FURNACE.getY()+4,FURNACE.getZ()+.5);tickingEntity.setNoGravity(true);tickingEntity.setInvulnerable(true);tickingEntity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);remote(server).addFreshEntity(tickingEntity);
                    }
                    if(ticks==100) {
                        var be=remote(server).getBlockEntity(BEARING);check(be!=null,"Create bearing exists");be.getClass().getMethod("assemble").invoke(be);
                    }
                    if(ticks==400) {
                        var be=remote(server).getBlockEntity(BEARING);lateAngle=((Number)be.getClass().getMethod("getInterpolatedAngle",float.class).invoke(be,0f)).doubleValue();
                        check((Boolean)be.getClass().getMethod("isRunning").invoke(be),"Actual Create contraption assembled");
                        check(tickingEntity.tickCount>300,"Player-free entity continues past dimension idle gate: ticks="+tickingEntity.tickCount+" removed="+tickingEntity.isRemoved()+" pos="+tickingEntity.position());
                    }
                    if(ticks==437) {
                        var be=(AbstractFurnaceBlockEntity)remote(server).getBlockEntity(FURNACE);
                        check(be!=null && be.getItem(2).is(Items.IRON_INGOT),"Remote furnace smelts with zero players: block-entity ticking");
                        var create=remote(server).getBlockEntity(BEARING);double angle=((Number)create.getClass().getMethod("getInterpolatedAngle",float.class).invoke(create,0f)).doubleValue();
                        check(Math.abs(angle-lateAngle)>.01,"Late actual Create bearing angle advances between tick 400 and 437");
                        check(tickingEntity.tickCount>350,"Entity still ticking after 300 empty ticks");
                        check(remote(server).getForcedChunks().isEmpty(),"No fake vanilla forced chunks");
                        writeResult(server,"PASS seed: native transfers/auth/caps/proration-rate-reload; player-free End Create bearing angle advances at ticks 400-437, entity >350 ticks, furnace, no vanilla forced chunks");
                        finish(server);
                    }
                }
                if(phase.equals("expire") && ticks>1) {
                    var s=TeamsMod.service();
                    if(s.teamOf(OWNER).suspended) {
                        check(s.activeTickets().isEmpty(),"Nonpayment removes only mod tickets");
                        check(s.teamOf(OWNER).chunks.size()==2,"Nonpayment retains selections");
                        check(s.balance(OWNER)==0,"No negative balance/downtime debt");
                        s.deposit(OWNER,100); check(s.balance(OWNER)==60,"Exactly one resumed full bill");
                        check(s.teamOf(OWNER).leaseStart>=firstTick,"Renewal from payment/current time");
                        check(s.teamOf(OWNER).leaseEnd-s.nowMillis()<=2000,"Configured new term rather than arrears");
                        writeResult(server,"PASS expire: insufficient suspension, retained selections, one renewal after funding"); finish(server);
                    } else if(ticks>400)throw new AssertionError("Lease failed to expire within 20 seconds");
                }
            } catch(Throwable e) {
                done=true; TeamsMod.LOGGER.error("GRXM INTEGRATION FAIL phase="+phase,e);
                try { Files.writeString(Path.of("grxmteams-integration-FAIL.txt"),phase+"\n"+e); } catch(Exception ignored) {}
                server.halt(false);
            }
        });
    }
    private void start(MinecraftServer server) throws Exception {
        var s=TeamsMod.service();
        if(phase.equals("damage-native")) {
            check(s.failure()==null,"Damage fixture begins with valid isolated bank");
            Path bank=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/numismatics_bank.dat");
            var root=net.minecraft.nbt.NbtIo.readCompressed(bank.toFile());var accounts=root.getCompound("data").getList("Accounts",10);accounts.add(new net.minecraft.nbt.CompoundTag());
            if(Boolean.getBoolean("grxmteams.integration.damageLegacy")) { root.getCompound("data").remove("grxmteams");Files.delete(bank.resolveSibling("grxmteams-initialized.guard")); }
            net.minecraft.nbt.NbtIo.writeCompressed(root,bank.toFile());Files.writeString(Path.of("grxmteams-integration-damage-native-CUT.txt"),"Malformed native account appended; legacy="+Boolean.getBoolean("grxmteams.integration.damageLegacy"));Runtime.getRuntime().halt(23);return;
        }
        if(phase.equals("recover-cut")) {
            var data=((GlobalBankManagerAccessor)Numismatics.BANK).grxmteams$savedData();var state=StateCodec.decode(((TeamDataAccess)data).grxmteams$getJson());
            boolean prepared=state.bazaar.journal.values().stream().anyMatch(tx->!tx.committed);
            check(prepared?s.failure()!=null:s.failure()==null,"Prepared quarantines; committed never replayed");
            check(state.bazaar.journal.size()==1,"Frozen journal survives actual process cut");
            var tx=state.bazaar.journal.values().iterator().next();var account=Numismatics.BANK.getAccount(tx.account);check(account.getBalance()==(tx.committed?30:0),"Frozen native credit exactly once across cut");
            if(prepared){int balance=account.getBalance();check(!account.deduct(1),"Native vendor/ATM debit denied during quarantine");account.deposit(1);account.setBalance(100);check(account.getBalance()==balance,"Native credit/setBalance frozen during quarantine");}
            var playerNbt=net.minecraft.nbt.NbtIo.readCompressed(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(tx.player+".dat").toFile());
            boolean proof=tx.receipt.equals(playerNbt.getCompound("grxmteamsSales").getString(tx.id.toString()));
            check(proof==!Files.readString(Path.of("grxmteams-integration-"+(Files.exists(Path.of("grxmteams-integration-cut-prepared-CUT.txt"))?"cut-prepared":Files.exists(Path.of("grxmteams-integration-cut-player-CUT.txt"))?"cut-player":"cut-committed")+"-CUT.txt")).equals("PREPARED"),"Player receipt exists only after durable removal");
            Files.writeString(Path.of("grxmteams-integration-recover-cut-PASS.txt"),"PASS recovery prepared="+prepared);finish(server);return;
        }
        if(phase.equals("bazaar")||phase.equals("bazaar-verify")||phase.startsWith("cut-")){bazaar(server,s);return;}
        if(phase.equals("corrupt")) {
            check(s.failure()!=null,"Corrupt persisted attachment disables service");
            check(s.activeTickets().isEmpty(),"Corrupt state has zero mod tickets");
            server.saveEverything(true,true,true);
            Files.writeString(Path.of("grxmteams-integration-corrupt-PASS.txt"),s.failure()); finish(server); return;
        }
        check(s.failure()==null,"Service is available: "+s.failure());
        if(phase.equals("seed"))seed(server,s);
        else {
            var expected=StateCodec.GSON.fromJson(Files.readString(RESULT),Result.class);
            check(expected!=null && expected.team.equals(s.teamOf(OWNER).id),"Team identity survived actual restart");
            check(expected.account.equals(s.teamOf(OWNER).account),"Same treasury UUID after restart");
            check(s.balance(OWNER)==expected.balance,"Native treasury balance round trip");
            check(s.teamOf(OWNER).leaseEnd==expected.leaseEnd,"Prepaid receipt survives restart without double billing");
            check(s.teamOf(OWNER).chunks.size()==2,"Selections round trip");
            var saved=((GlobalBankManagerAccessor)Numismatics.BANK).grxmteams$savedData();
            check(saved instanceof TeamDataAccess && ((TeamDataAccess)saved).grxmteams$getJson()!=null,"Real loaded BankSavedData attachment");
            if(phase.equals("verify")) { writeResult(server,"PASS verify: actual bank and lease restart roundtrip"); finish(server); }
            else {
                // Existing prepaid term must be expired in-place, without touching the OS clock.
                // Trusted API reconciles at its endpoint, then config changes the next term to 2 sec.
                int balance=s.balance(OWNER); if(balance>0)s.withdraw(OWNER,balance);
                TeamsConfig cfg=s.configuration(); cfg.billing.periodSeconds=2; s.reload(cfg);
                timeOffset=Math.max(0,expected.leaseEnd-System.currentTimeMillis()+1);
                s.reconcile(s.nowMillis());
                check(s.teamOf(OWNER).suspended,"Insufficient expiry suspends immediately");
            }
        }
    }
    private void seed(MinecraftServer server,TeamService s) throws Exception {
        check(s.snapshot().teams.isEmpty(),"Seed requires a fresh isolated bank/team state");
        check(!s.configuration().billing.enabled,"Production defaults economy off");
        Numismatics.BANK.getOrCreateAccount(OWNER,BankAccount.Type.PLAYER).setBalance(100000);
        Numismatics.BANK.getOrCreateAccount(MEMBER,BankAccount.Type.PLAYER).setBalance(0);
        TeamsConfig cfg=s.configuration(); cfg.billing.enabled=true; cfg.billing.scalingFactor="10"; cfg.billing.teamMemberScaling="1"; cfg.billing.periodSeconds=3600; cfg.limits.maxChunksPerTeam=2; cfg.limits.allowedDimensions=new ArrayList<>(List.of("minecraft:overworld","minecraft:the_end")); s.reload(cfg);
        // Persist valid chosen rates for the restart phase via AutoConfig's own serialized file.
        Files.writeString(Path.of("config/grxmteams.json"),StateCodec.GSON.toJson(cfg));
        UUID team=s.create(OWNER,"Integration Workshop");
        check(s.balance(OWNER)==0,"BLAZE_BANKER treasury starts empty (no starter grant)");
        s.invite(OWNER,MEMBER); s.accept(MEMBER,team);
        denied(()->s.withdraw(MEMBER,1),"Ordinary member cannot withdraw");
        denied(()->s.role(MEMBER,OWNER,Role.OFFICER),"Member cannot alter owner role");
        s.role(OWNER,MEMBER,Role.OFFICER); denied(()->s.withdraw(MEMBER,1),"Officer withdrawal disabled by default");
        s.deposit(OWNER,1000); check(Numismatics.BANK.getAccount(OWNER).getBalance()==99000,"Deposit deducts real personal balance");
        s.addChunk(OWNER,FIRST); check(s.balance(OWNER)==980,"First lease pays total 1 chunk * 10 * 1 * 2 members");
        long end=s.teamOf(OWNER).leaseEnd;TeamsConfig disabled=s.configuration();disabled.billing.enabled=false;s.reload(disabled);denied(()->s.addChunk(MEMBER,SECOND),"Disabled billing cannot erase paid term or extend unpaid capacity");check(s.teamOf(OWNER).leaseEnd==end,"Disabled selection rejection preserves anniversary");s.reload(cfg); TeamsConfig changed=s.configuration();changed.billing.scalingFactor="100";s.reload(changed);s.addChunk(MEMBER,SECOND);s.reload(cfg); check(s.balance(OWNER)==960,"Prorated addition paid from native bank");
        check(s.teamOf(OWNER).leaseEnd==end,"Addition does not reset renewal");
        s.addChunk(OWNER,SECOND); check(s.balance(OWNER)==960,"Duplicate selection does not charge");
        denied(()->s.addChunk(OWNER,new Chunk("minecraft:overworld",10002,10000)),"Per-team cap enforced");
        s.create(OUTSIDER,"Other Integration"); denied(()->s.addChunk(OUTSIDER,FIRST),"Other team cannot reserve owned coordinates");
        denied(()->s.withdraw(OUTSIDER,1),"Other team cannot withdraw another treasury");
        denied(()->s.deposit(OWNER,Integer.MAX_VALUE),"Native target balance overflow rejected");
        check(s.balance(OWNER)==960,"Rejected operations leave bank unchanged");
        var bank=((GlobalBankManagerAccessor)Numismatics.BANK).grxmteams$savedData();
        var nativeTag=bank.save(new net.minecraft.nbt.CompoundTag());
        check(nativeTag.contains("grxmteams",10),"Actual bank save method contains team compound in native root");
        check(StateCodec.decode(new String(nativeTag.getCompound("grxmteams").getByteArray("json"),java.nio.charset.StandardCharsets.UTF_8)).byId(team).leaseEnd==end,"Serialized lease in actual bank NBT");
    }
    private static final class CaptureConnection extends net.minecraft.network.Connection {
        final List<net.minecraft.network.protocol.Packet<?>> packets=new ArrayList<>();
        CaptureConnection(){super(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);}
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet){packets.add(packet);}
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener listener){packets.add(packet);}
        @Override public java.net.SocketAddress getRemoteAddress(){return new java.net.InetSocketAddress("127.0.0.1",0);}
    }
    private final Map<UUID,CaptureConnection> captures=new HashMap<>();
    private net.minecraft.server.level.ServerPlayer fixture(MinecraftServer server,UUID uuid,String name) {
        var p=new net.minecraft.server.level.ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(uuid,name));
        var connection=new CaptureConnection();captures.put(uuid,connection);p.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(server,connection,p);return p;
    }
    private void bazaar(MinecraftServer server,TeamService s) throws Exception {
        check(s.failure()==null,"Bazaar service ready: "+s.failure());
        if(phase.equals("bazaar-verify")) {
            String expected=Files.readString(Path.of("bazaar-expected.json"));check(StateCodec.GSON.toJson(s.snapshot().bazaar).equals(expected),"Market, quotas, frozen prices and journal survive restart");
            check(s.balance(OWNER)==60,"Minted bank credit survives restart without replay");Files.writeString(Path.of("grxmteams-integration-bazaar-verify-PASS.txt"),"PASS persisted market, quotas, journal, actual native bank");finish(server);return;
        }
        check(s.snapshot().teams.isEmpty(),"Bazaar phase requires fresh isolated world");
        TeamsConfig cfg=s.configuration();cfg.bazaar.enabled=true;cfg.resourcePack.enabled=false;for(var e:cfg.bazaar.pool){e.minItemCount=20;e.maxItemCount=20;e.minItemPrice=3;e.maxItemPrice=3;}s.reload(cfg);
        Files.writeString(Path.of("config/grxmteams.json"),StateCodec.GSON.toJson(cfg));
        UUID team=s.create(OWNER,"Bazaar Team");s.invite(OWNER,MEMBER);s.accept(MEMBER,team);s.bazaar().rotate();
        var market=s.bazaar().snapshot();String item=market.offers.get(0).itemId();
        var p=fixture(server,OWNER,"OwnerFixture");var member=fixture(server,MEMBER,"MemberFixture");
        p.getInventory().setItem(0,new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(item)),20));
        member.getInventory().setItem(0,new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(item)),20));
        ((dev.grxm.teams.mixin.PlayerListAccessor)server.getPlayerList()).grxmteams$playerIo().save(p);
        if(phase.equals("cut-failed-commit")) {
            s.bazaar().installObserver(cut->{if(cut==BazaarService.Cut.PLAYER_SAVED)try {
                Path bank=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/numismatics_bank.dat");Files.move(bank,bank.resolveSibling("numismatics_bank.prepared-evidence"));Files.createDirectory(bank);
                Files.writeString(Path.of("grxmteams-integration-cut-player-CUT.txt"),"PLAYER_SAVED");
            }catch(Exception e){throw new RuntimeException(e);}});
            UUID destination=s.teamOf(OWNER).account;boolean failed=false;try{s.bazaar().sell(p,market.generation,team,0,10);}catch(IllegalStateException e){failed=true;}
            check(failed&&s.failure()!=null&&BankPersistence.frozen(),"Real atomic replacement failure quarantines immediately");
            var account=Numismatics.BANK.getAccount(destination);int frozen=account.getBalance();check(frozen==30,"Uncertain RAM credit retained, not guessed/refunded");check(!account.deduct(1),"Native uncertain debit blocked before yielding");account.deposit(10);account.setBalance(500);check(account.getBalance()==frozen,"All native balance mutations blocked");
            Files.writeString(Path.of("grxmteams-integration-cut-failed-commit-PASS.txt"),"PASS actual atomic replacement failure: uncertain RAM credit frozen, shutdown requested");done=true;return;
        }
        if(phase.startsWith("cut-")) {
            BazaarService.Cut target=switch(phase){case "cut-prepared"->BazaarService.Cut.PREPARED;case "cut-player"->BazaarService.Cut.PLAYER_SAVED;default->BazaarService.Cut.COMMITTED;};
            server.saveEverything(true,true,true);
            s.bazaar().installObserver(cut->{if(cut==target){try{Files.writeString(Path.of("grxmteams-integration-"+phase+"-CUT.txt"),cut.toString());}catch(Exception e){throw new RuntimeException(e);}Runtime.getRuntime().halt(23);}});
            s.bazaar().sell(p,market.generation,team,0,10);throw new AssertionError("Expected abrupt process cut");
        }
        String frozen=StateCodec.GSON.toJson(market.offers);TeamsConfig changed=s.configuration();changed.bazaar.pool.get(0).maxItemPrice=99;s.reload(changed);s.bazaar().rotate();check(frozen.equals(StateCodec.GSON.toJson(s.bazaar().snapshot().offers)),"Reload does not reroll active prices");
        BlockPos counter=new BlockPos(0,81,3);for(int x=-2;x<=2;x++)for(int y=80;y<=84;y++)for(int z=-2;z<=6;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());server.overworld().setBlockAndUpdate(counter,Blocks.SMOOTH_STONE.defaultBlockState());p.setPos(.5,80,.5);p.setYRot(0);p.setYHeadRot(0);p.setXRot(0);
        s.bazaar().bindLook(p,1);check(s.bazaar().snapshot().counters.get(1).z()==3,"Operator POV bind hits actual solid block: "+s.bazaar().snapshot().counters.get(1)+" eye="+p.getEyePosition()+" direction="+p.getViewVector(1));
        var bound=s.boundCounter("minecraft:overworld",counter);s.bazaar().open(p,bound);check(p.containerMenu instanceof BazaarMenu,"Actual vanilla shop menu opened");
        var menu=p.containerMenu;int before=p.getInventory().getItem(0).getCount();
        for(var click:net.minecraft.world.inventory.ClickType.values()){menu.clicked(4,0,click,p);menu.clicked(31,1,click,p);}
        check(p.getInventory().getItem(0).getCount()==before&&menu.getCarried().isEmpty(),"Shift/swap/drag/double-click cannot steal display stacks or mutate player slots");
        menu.clicked(10,0,net.minecraft.world.inventory.ClickType.PICKUP,p);check(s.balance(OWNER)==3,"Real GUI Sell 1 mints native bank credit");
        p.closeContainer();s.bazaar().sell(p,market.generation,team,0,9);s.bazaar().sell(member,market.generation,team,0,10);
        check(s.balance(OWNER)==60,"Real ordinary inventory sales mint native treasury credit");check(p.getInventory().getItem(0).getCount()==10&&member.getInventory().getItem(0).getCount()==10,"Exact items consumed");
        Numismatics.BANK.getOrCreateAccount(OWNER,BankAccount.Type.PLAYER).setBalance(0);s.withdraw(OWNER,3);check(s.balance(OWNER)==57,"Bazaar-only activation permits authorized withdrawal without enabling rent");s.deposit(OWNER,3);check(s.balance(OWNER)==60,"Bazaar-only contribution restores treasury");
        denied(()->s.bazaar().sell(p,market.generation,team,0,1),"Shared member quota exhausted");check(p.getInventory().getItem(0).getCount()==10&&s.balance(OWNER)==60,"Rejected sale leaves items and money unchanged");
        s.create(OUTSIDER,"Overflow Team");var outsider=fixture(server,OUTSIDER,"OtherFixture");outsider.getInventory().setItem(0,new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(item)),20));
        Numismatics.BANK.getAccount(s.teamOf(OUTSIDER).account).setBalance(Integer.MAX_VALUE-1);denied(()->s.bazaar().sell(outsider,market.generation,s.teamOf(OUTSIDER).id,0,1),"Existing balance plus payout overflow denied");check(outsider.getInventory().getItem(0).getCount()==20,"Overflow takes no items");
        outsider.setPos(.5,80,.5);
        try {
            server.getPlayerList().getPlayers().add(p);server.getPlayerList().getPlayers().add(outsider);
            for(int tick=0;tick<20;tick++)s.tick();
            for(var viewer:List.of(p,outsider)) {
                var packets=captures.get(viewer.getUUID()).packets;
                check(packets.stream().filter(packet->packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket).count()==2,"Exactly two packet-only display entities per viewer");
                String expected=viewer==p?"Team remaining: 0 / 20":"Team remaining: 20 / 20";
                check(packets.stream().filter(packet->packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket).map(packet->(net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket)packet).flatMap(packet->packet.packedItems().stream()).anyMatch(value->value.value() instanceof net.minecraft.network.chat.Component text&&text.getString().contains(expected)),"Hologram remaining quota personalized per viewer's team");
            }
            s.bazaar().unbind(1);for(int tick=0;tick<20;tick++)s.tick();
            check(captures.get(OWNER).packets.stream().anyMatch(packet->packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket),"Unbind removes virtual displays");
        } finally {server.getPlayerList().getPlayers().remove(p);server.getPlayerList().getPlayers().remove(outsider);s.clearDisplays(p);s.clearDisplays(outsider);}
        timeOffset=market.end-System.currentTimeMillis()+1;s.bazaar().rotate();long next=s.bazaar().snapshot().generation;check(next>market.generation,"Period creates new monotonic generation");
        denied(()->s.bazaar().sell(p,market.generation,team,0,1),"Stale GUI generation denied");timeOffset=0;s.bazaar().rotate();check(s.bazaar().snapshot().generation==next,"Clock rollback cannot rewind generation");
        Files.writeString(Path.of("bazaar-expected.json"),StateCodec.GSON.toJson(s.snapshot().bazaar));Files.writeString(Path.of("grxmteams-integration-bazaar-PASS.txt"),"PASS real GUI sales/shared quotas/overflow/reload/stale generation/rollback/POV bind/per-viewer native display packets");finish(server);
    }
    private record Result(UUID team,UUID account,int balance,long leaseEnd,String result) {}
    private void writeResult(MinecraftServer server,String result) throws Exception {
        var s=TeamsMod.service(); var t=s.teamOf(OWNER); server.saveEverything(true,true,true);
        Files.writeString(RESULT,StateCodec.GSON.toJson(new Result(t.id,t.account,s.balance(OWNER),t.leaseEnd,result)));
        Files.writeString(Path.of("grxmteams-integration-"+phase+"-PASS.txt"),result);
        TeamsMod.LOGGER.info("GRXM INTEGRATION {}",result);
    }
    private void finish(MinecraftServer server) { done=true; server.saveEverything(true,true,true); server.halt(false); }
}
