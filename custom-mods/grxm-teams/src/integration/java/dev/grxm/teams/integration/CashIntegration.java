package dev.grxm.teams.integration;

import dev.grxm.teams.*;
import dev.grxm.teams.mixin.*;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;

/** Two guards; separately repackaged test MOD. No real-player/world admission. */
public final class CashIntegration implements ModInitializer {
    private static final UUID OWNER=UUID.fromString("32f00c00-0000-4000-8000-000000000001"),MEMBER=UUID.fromString("32f00c00-0000-4000-8000-000000000002"),OTHER=UUID.fromString("32f00c00-0000-4000-8000-000000000003");
    private boolean done;
    private static String phase;
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    private static void denied(Runnable action,String why){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,why);}
    private static Item item(String id){return BuiltInRegistries.ITEM.get(new ResourceLocation(id));}
    private static ItemStack spur(int n){return new ItemStack(item("numismatics:spur"),n);}
    private static final class Capture extends Connection {
        Capture(){super(PacketFlow.SERVERBOUND);}
        @Override public void send(Packet<?> p){}
        @Override public void send(Packet<?> p,PacketSendListener l){}
        @Override public java.net.SocketAddress getRemoteAddress(){return new java.net.InetSocketAddress("127.0.0.1",0);}
    }
    private static ServerPlayer player(MinecraftServer server,UUID id,String name,boolean load)throws Exception {
        var p=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(id,name));new ServerGamePacketListenerImpl(server,new Capture(),p);
        if(load)p.load(NbtIo.readCompressed(file(server,id).toFile()));return p;
    }
    private static Path file(MinecraftServer s,UUID id){return s.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id+".dat");}
    private static ListTag extras(ListTag inventory){ListTag out=new ListTag();for(int n=0;n<inventory.size();n++)if((inventory.getCompound(n).getByte("Slot")&255)>=36)out.add(inventory.get(n).copy());return out;}
    private static int count(ServerPlayer p){int n=0;for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(item("numismatics:spur")))n+=p.getInventory().getItem(i).getCount();return n;}
    private static void save(MinecraftServer s,ServerPlayer p){((PlayerListAccessor)s.getPlayerList()).grxmteams$playerIo().save(p);}
    @Override public void onInitialize(){
        if(!Boolean.getBoolean("grxmteams.cashIntegration")||!Files.isRegularFile(Path.of("grxmteams-integration-ALLOW")))throw new IllegalStateException("Cash tests require opt-in and isolated marker");
        phase=System.getProperty("grxmteams.cash.phase","seed");
        ServerTickEvents.END_SERVER_TICK.register(server->{if(done)return;done=true;try{run(server);Files.writeString(Path.of(phase+"-PASS.txt"),"PASS "+phase+": physical SPUR, durable bank/player proofs; no personal accounts");}catch(Throwable e){TeamsMod.LOGGER.error("Cash integration FAIL "+phase,e);try{Files.writeString(Path.of(phase+"-FAIL.txt"),e.toString());}catch(Exception ignored){}}finally{server.halt(false);}});
    }
    private static void run(MinecraftServer server)throws Exception {
        TeamService s=TeamsMod.service();
        if(phase.equals("recover")){recover(server,s);return;}
        check(s.failure()==null,"Service ready: "+s.failure());
        if(phase.equals("seed")) {
            check(s.snapshot().teams.isEmpty(),"Fresh isolated fixture");TeamsConfig cfg=s.configuration();cfg.bazaar.enabled=true;cfg.permissions.officersWithdraw=true;cfg.bazaar.payPersonal=false;cfg.resourcePack.enabled=false;s.reload(cfg);Files.writeString(Path.of("config/grxmteams.json"),StateCodec.GSON.toJson(cfg));
            UUID team=s.create(OWNER,"Physical Cash");s.invite(OWNER,MEMBER);s.accept(MEMBER,team);
            var p=player(server,OWNER,"CashOwner",false);for(int i=0;i<3;i++)p.getInventory().setItem(i,spur(64));p.getInventory().setItem(3,spur(8));
            var inventory=p.getInventory().save(new ListTag());var backpack=new ItemStack(item("backpacked:backpack"));check(!backpack.isEmpty(),"Real Backpacked installed");ListTag nested=new ListTag();CompoundTag contents=spur(64).save(new CompoundTag());contents.putByte("Slot",(byte)0);nested.add(contents);backpack.getOrCreateTag().put("Items",nested);CompoundTag entry=backpack.save(new CompoundTag());entry.putByte("Slot",(byte)200);inventory.add(entry);p.getInventory().load(inventory);
            p.getInventory().setItem(36,new ItemStack(Items.DIAMOND_BOOTS));p.getInventory().setItem(40,spur(64));ListTag before=extras(p.getInventory().save(new ListTag()));check(before.size()==3,"Armor/offhand/backpack equipped");
            s.deposit(p,100);check(count(p)==100&&s.balance(OWNER)==100,"Real cash deposit without personal account");check(extras(p.getInventory().save(new ListTag())).equals(before),"Extended inventory preserved");
            var m=player(server,MEMBER,"CashMember",false);m.getInventory().setItem(0,spur(10));save(server,m);
            Files.writeString(Path.of("seed-extras.snbt"),before.toString());
        } else if(phase.equals("cross")) {
            var p=player(server,OWNER,"CashOwner",true);var market=s.bazaar().snapshot();var offer=market.offers.get(0);p.getInventory().setItem(10,new ItemStack(item(offer.itemId()),1));
            s.cash().installObserver(cut->{if(cut==CashService.Cut.PREPARED)denied(()->s.bazaar().sell(p,market.generation,s.teamOf(OWNER).id,0,1),"Unresolved cash blocks bazaar admission");});s.deposit(p,1);s.cash().installObserver(cut->{});
            s.bazaar().installObserver(cut->{if(cut==BazaarService.Cut.PREPARED)denied(()->s.deposit(p,1),"Unresolved bazaar blocks cash admission");});s.bazaar().sell(p,market.generation,s.teamOf(OWNER).id,0,1);s.bazaar().installObserver(cut->{});
            s.withdraw(p,1);check(count(p)==100&&s.balance(OWNER)==100+offer.unitPrice(),"Shared receipts survive cash/bazaar/cash sequence without quota interference");
            var replacement=player(server,OWNER,"CashOwner",false);net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.invoker().copyFromPlayer(p,replacement,false);
            check(((CashReceipts)replacement).grxmteams$cashReceipts().equals(((CashReceipts)p).grxmteams$cashReceipts())&&((PlayerReceipts)replacement).grxmteams$receipts().equals(((PlayerReceipts)p).grxmteams$receipts()),"Respawn callback copies both proof namespaces");
        } else if(phase.equals("happy")) {
            var p=player(server,OWNER,"CashOwner",true);var m=player(server,MEMBER,"CashMember",true);var other=player(server,OTHER,"CashOther",false);
            check(count(p)==100&&s.balance(OWNER)==100,"Seed survives actual restart");ListTag before=extras(p.getInventory().save(new ListTag()));
            denied(()->s.withdraw(m,1),"Member withdrawal denied");denied(()->s.deposit(other,1),"Outsider deposit denied");denied(()->s.deposit(p,0),"Zero count denied");denied(()->s.deposit(p,Integer.MAX_VALUE),"Overflow denied");denied(()->s.withdraw(p,101),"Insufficient treasury denied");
            p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);denied(()->s.deposit(p,1),"Spectator denied");p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.setHealth(0);denied(()->s.withdraw(p,1),"Dead denied");p.setHealth(20);
            ListTag original=p.getInventory().save(new ListTag());for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIAMOND,64));denied(()->s.withdraw(p,1),"Full main inventory denied without dropping");check(s.balance(OWNER)==100&&count(p)==0,"Full denial no debit");p.getInventory().load(original);
            s.deposit(m,1);check(s.balance(OWNER)==101&&count(m)==9,"Members can deposit");s.role(OWNER,MEMBER,TeamState.Role.OFFICER);s.withdraw(m,1);check(s.balance(OWNER)==100&&count(m)==10,"Authorized officer withdrawal");s.role(OWNER,MEMBER,TeamState.Role.MEMBER);
            s.deposit(p,20);s.withdraw(p,40);check(count(p)==120&&s.balance(OWNER)==80,"Whole spurs exact native units roundtrip");check(p.getInventory().getItem(1).getCount()==48&&p.getInventory().getItem(2).getCount()==64,"Real main-stack merging, no denomination exchange");
            check(extras(p.getInventory().save(new ListTag())).equals(before)&&before.toString().equals(Files.readString(Path.of("seed-extras.snbt"))),"Nested backpack armor/offhand untouched");
            // Neither gearwheels nor nested/offhand spurs pay a deposit.
            original=p.getInventory().save(new ListTag());for(int i=0;i<36;i++)p.getInventory().setItem(i,ItemStack.EMPTY);p.getInventory().setItem(0,new ItemStack(item("create:cogwheel"),64));denied(()->s.deposit(p,1),"Create gearwheel/nested/offhand excluded");p.getInventory().setItem(0,new ItemStack(item("numismatics:cog"),64));denied(()->s.deposit(p,1),"COG is not a SPUR");p.getInventory().setItem(0,new ItemStack(item("numismatics:crown"),64));denied(()->s.deposit(p,1),"CROWN is not a SPUR");p.getInventory().load(original);
            Files.writeString(Path.of("happy-state.json"),StateCodec.encode(s.snapshot()));Files.writeString(Path.of("happy-inventory.snbt"),p.getInventory().save(new ListTag()).toString());
        } else if(phase.equals("verify")) {
            check(StateCodec.encode(s.snapshot()).equals(Files.readString(Path.of("happy-state.json"))),"Exact journal survives restart without replay");var p=player(server,OWNER,"CashOwner",true);check(count(p)==120&&s.balance(OWNER)==80&&p.getInventory().save(new ListTag()).toString().equals(Files.readString(Path.of("happy-inventory.snbt"))),"Exact full persisted player and balance");
        } else {
            var p=player(server,OWNER,"CashOwner",true);boolean withdraw=phase.startsWith("withdraw");
            boolean fault=phase.endsWith("FAULT");
            CashService.Cut target=fault?CashService.Cut.PLAYER_SAVED:CashService.Cut.valueOf(phase.substring(phase.indexOf('-')+1));
            s.cash().installObserver(cut->{if(cut!=target)return;try {
                if(fault){Path bank=server.getWorldPath(LevelResource.ROOT).resolve("data/numismatics_bank.dat");Files.move(bank,bank.resolveSibling("numismatics_bank.prepared-evidence"));Files.createDirectory(bank);Files.writeString(bank.resolve("blocker"),"disposable cash commit failure");return;}
                Files.writeString(Path.of("cash-CUT.txt"),phase);Runtime.getRuntime().halt(23);
            }catch(Exception e){throw new RuntimeException(e);}});
            if(fault){
                boolean failed=false;try{if(withdraw)s.withdraw(p,10);else s.deposit(p,10);}catch(IllegalStateException e){failed=true;}
                check(failed&&s.failure()!=null&&BankPersistence.frozen(),"Real failed atomic bank commit quarantines");var bank=Numismatics.BANK.getAccount(safeState().member(OWNER).account);int frozen=bank.getBalance();check(frozen==(withdraw?90:110),"Uncertain RAM amount retained, never guessed compensation");check(!bank.deduct(1),"Native debit frozen before yielding");bank.deposit(1);bank.setBalance(500);check(bank.getBalance()==frozen,"Native credit/set frozen before yielding");
                Path evidence=server.getWorldPath(LevelResource.ROOT).resolve("data/numismatics_bank.prepared-evidence");CompoundTag root=NbtIo.readCompressed(evidence.toFile());TeamState disk=StateCodec.decode(new String(root.getCompound("data").getCompound("grxmteams").getByteArray("json"),java.nio.charset.StandardCharsets.UTF_8));check(!disk.cash.journal.values().stream().reduce((a,b)->b).orElseThrow().committed,"Durable PREPARED evidence survives commit failure");
                Files.writeString(Path.of("fault-details.txt"),"PASS actual failed atomic replacement "+phase+" RAM="+frozen+" preserved PREPARED; native mutators frozen; shutdown requested");return;
            }
            if(withdraw)s.withdraw(p,10);else s.deposit(p,10);throw new AssertionError("Expected abrupt exit");
        }
        check(Numismatics.BANK.getAccount(OWNER)==null&&Numismatics.BANK.getAccount(MEMBER)==null,"No personal account required/created");server.saveEverything(true,true,true);
    }
    private static TeamState safeState(){return StateCodec.decode(((TeamDataAccess)((GlobalBankManagerAccessor)Numismatics.BANK).grxmteams$savedData()).grxmteams$getJson());}
    private static void recover(MinecraftServer server,TeamService s)throws Exception {
        String cut=Files.readString(Path.of("cash-CUT.txt"));var data=((GlobalBankManagerAccessor)Numismatics.BANK).grxmteams$savedData();TeamState state=StateCodec.decode(((TeamDataAccess)data).grxmteams$getJson());
        var tx=state.cash.journal.values().stream().reduce((a,b)->b).orElseThrow();boolean committed=cut.endsWith("COMMITTED"),saved=!cut.endsWith("PREPARED"),withdraw=cut.startsWith("withdraw");
        check(tx.committed==committed&&(s.failure()!=null)==!committed,"PREPARED quarantines; COMMITTED admitted without replay");
        BankAccount bank=Numismatics.BANK.getAccount(tx.account);int expected=withdraw?90:committed?110:100;check(bank.getBalance()==expected,"Exact bank boundary including durable withdrawal reservation");
        var p=player(server,OWNER,"CashOwner",true);check(count(p)==(saved?(withdraw?110:90):100),"Exact phase player inventory; no guessed recovery");check(extras(p.getInventory().save(new ListTag())).toString().equals(Files.readString(Path.of("seed-extras.snbt"))),"Crash preserves backpack armor/offhand");
        if(!committed){int frozen=bank.getBalance();check(BankPersistence.frozen()&&!bank.deduct(1),"Native debits frozen");bank.deposit(1);bank.setBalance(100);check(bank.getBalance()==frozen,"Native credit/set frozen");}
        check(((CashReceipts)p).grxmteams$cashReceipts().contains(tx.id.toString())==saved,"Receipt exactly when player saved");
        Files.writeString(Path.of("recovery-details.txt"),"PASS "+cut+" balance="+expected+" inventory="+count(p)+" committed="+committed+" quarantined="+(s.failure()!=null));
    }
}
