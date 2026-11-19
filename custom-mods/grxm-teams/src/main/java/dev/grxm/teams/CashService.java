package dev.grxm.teams;

import dev.grxm.teams.CashState.*;
import dev.grxm.teams.mixin.PlayerListAccessor;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Server-thread, conservative cross-file cash exchange. PREPARED is never automatically resolved. */
public final class CashService {
    static {
        // Vanilla respawn copies inventory selectively, not our durable transaction proofs.
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.register((oldPlayer,newPlayer,alive)->{
            ((CashReceipts)newPlayer).grxmteams$cashReceipts().merge(((CashReceipts)oldPlayer).grxmteams$cashReceipts().copy());
            ((PlayerReceipts)newPlayer).grxmteams$receipts().merge(((PlayerReceipts)oldPlayer).grxmteams$receipts().copy());
        });
    }
    private final TeamService teams;
    private final MinecraftServer server;
    public enum Cut { PREPARED, PLAYER_SAVED, COMMITTED }
    private java.util.function.Consumer<Cut> observer=cut->{};
    CashService(TeamService teams,MinecraftServer server){this.teams=teams;this.server=server;}
    /** Trusted isolated integration hook; no production commands/properties. */
    public void installObserver(java.util.function.Consumer<Cut> observer){teams.ready();this.observer=Objects.requireNonNull(observer);}
    private Path playerFile(UUID player){return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player+".dat");}
    public void recover() {
        try {
            TeamState state=teams.snapshot();
            for(var t:state.cash.journal.values())if(!t.committed)throw new IllegalStateException("PREPARED cash transfer "+t.id+" quarantined; no automatic delivery/refund; account="+t.account+", direction="+t.direction);
            // Check BOTH directions of receipt/journal correspondence, including orphan player receipts.
            Set<UUID> players=new HashSet<>();state.cash.journal.values().forEach(t->players.add(t.player));state.bazaar.journal.values().forEach(t->players.add(t.player));
            Path directory=server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if(Files.isDirectory(directory))try(var paths=Files.list(directory)){for(Path p:paths.filter(p->p.getFileName().toString().endsWith(".dat")).toList())players.add(UUID.fromString(p.getFileName().toString().replaceFirst("\\.dat$","")));}
            for(UUID player:players)checkReceipts(state,player,NbtIo.readCompressed(playerFile(player).toFile()));
        } catch(Exception e){throw new IllegalStateException("Cash/bazaar recovery guard: "+e,e);}
    }
    static void checkReceipts(TeamState state,UUID player,CompoundTag root) {
        if(root==null)throw new IllegalStateException("Missing saved financial player "+player);
        CompoundTag cash=root.getCompound("grxmteamsCash"),sales=root.getCompound("grxmteamsSales");
        Map<String,String> expectedCash=new HashMap<>(),expectedSales=new HashMap<>();
        for(var t:state.cash.journal.values())if(t.player.equals(player)&&t.committed)expectedCash.put(t.id.toString(),t.receipt);
        for(var t:state.bazaar.journal.values())if(t.player.equals(player)&&t.committed)expectedSales.put(t.id.toString(),t.receipt);
        checkNamespace(cash,expectedCash);checkNamespace(sales,expectedSales);
    }
    private static void checkNamespace(CompoundTag receipts,Map<String,String> expected) {
        if(!receipts.getAllKeys().equals(expected.keySet()))throw new IllegalStateException("Financial receipt/journal keys conflict");
        expected.forEach((id,proof)->{if(!receipts.contains(id,Tag.TAG_STRING)||!proof.equals(receipts.getString(id)))throw new IllegalStateException("Financial receipt/journal proof conflict "+id);});
    }
    /** Admission shared by cash and bazaar; a previous unresolved obligation must never be bypassed. */
    void admit(ServerPlayer player) {
        teams.ready();TeamState state=teams.snapshot();UUID id=player.getUUID();
        if(state.cash.journal.values().stream().anyMatch(t->t.player.equals(id)&&!t.committed)||state.bazaar.journal.values().stream().anyMatch(t->t.player.equals(id)&&!t.committed))throw new IllegalArgumentException("Prior financial transaction is unresolved; operator recovery required");
        try {
            CompoundTag live=new CompoundTag();live.put("grxmteamsCash",((CashReceipts)player).grxmteams$cashReceipts());live.put("grxmteamsSales",((PlayerReceipts)player).grxmteams$receipts());checkReceipts(state,id,live);
            boolean hasProof=state.cash.journal.values().stream().anyMatch(t->t.player.equals(id))||state.bazaar.journal.values().stream().anyMatch(t->t.player.equals(id));
            if(Files.exists(playerFile(id)))checkReceipts(state,id,NbtIo.readCompressed(playerFile(id).toFile()));else if(hasProof)throw new IllegalStateException("Missing saved financial player");
        } catch(Exception e){RuntimeException fault=new IllegalStateException("Prior cash/bazaar receipt conflict; operator recovery required",e);teams.quarantine(fault);throw fault;}
    }
    UUID exchange(ServerPlayer player,int count,Direction direction) {
        teams.cashFinancial();admit(player);
        int amount=CashState.spurs(count);var t=teams.teamOf(player.getUUID());
        validatePlayer(player,t,direction);
        if(Coin.SPUR.value!=CashState.SPURS_PER_ITEM)throw new IllegalStateException("Pinned SPUR value changed");
        BankAccount bank=Numismatics.BANK.getAccount(t.account);
        if(bank==null||!bank.id.equals(t.account)||bank.type!=BankAccount.Type.BLAZE_BANKER||bank.getBalance()<0)throw new IllegalStateException("Missing/invalid treasury");
        if(direction==Direction.DEPOSIT?(long)bank.getBalance()+amount>Integer.MAX_VALUE:bank.getBalance()<amount)throw new IllegalArgumentException("Insufficient treasury funds or native balance overflow");
        TeamState next=teams.snapshot();
        if(next.cash.journal.size()>=CashState.CAPACITY)throw new IllegalArgumentException("Cash journal full; operator checkpoint required");
        CashInventory inventory=new CashInventory(player,new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation("numismatics:spur"))),count,direction==Direction.DEPOSIT);
        Transfer transfer=new Transfer();transfer.id=UUID.randomUUID();transfer.player=player.getUUID();transfer.team=t.id;transfer.account=t.account;transfer.direction=direction;transfer.itemCount=count;transfer.spurs=amount;transfer.balanceBefore=bank.getBalance();transfer.beforeInventory=inventory.before.toString();transfer.afterInventory=inventory.after.toString();transfer.receipt=CashState.proof(transfer);
        next.cash.journal.put(transfer.id,transfer);StateCodec.encode(next);
        try {
            // Withdrawal reservation and PREPARED are in the SAME synchronous bank commit.
            if(direction==Direction.WITHDRAW&&!bank.deduct(amount))throw new IllegalStateException("Treasury changed before reservation");
            teams.persistCash(next.cash);observer.accept(Cut.PREPARED);
            validatePlayer(player,teams.teamOf(player.getUUID()),direction);
            if(!teams.teamOf(player.getUUID()).id.equals(transfer.team))throw new IllegalStateException("Team changed after preparation");
            inventory.apply(player);
            ((CashReceipts)player).grxmteams$cashReceipts().putString(transfer.id.toString(),transfer.receipt);
            ((PlayerListAccessor)server.getPlayerList()).grxmteams$playerIo().save(player);
            Path path=playerFile(player.getUUID());
            try(FileChannel file=FileChannel.open(path,StandardOpenOption.WRITE)){file.force(true);}
            CompoundTag saved=NbtIo.readCompressed(path.toFile());
            if(!saved.getList("Inventory",Tag.TAG_COMPOUND).equals(inventory.after)||!transfer.receipt.equals(saved.getCompound("grxmteamsCash").getString(transfer.id.toString())))throw new IllegalStateException("Cash player save receipt/full inventory mismatch");
            try(FileChannel dir=FileChannel.open(path.getParent(),StandardOpenOption.READ)){dir.force(true);}
            observer.accept(Cut.PLAYER_SAVED);
            if(bank.getBalance()!=transfer.balanceBefore-(direction==Direction.WITHDRAW?amount:0))throw new IllegalStateException("Reserved treasury changed during cash exchange");
            if(direction==Direction.DEPOSIT)bank.deposit(amount);
            transfer.committed=true;teams.persistCash(next.cash);observer.accept(Cut.COMMITTED);
            return transfer.id;
        } catch(Exception e){RuntimeException uncertain=new IllegalStateException("Cash transfer "+transfer.id+" quarantined; operator recovery required",e);teams.quarantine(uncertain);throw uncertain;}
    }
    private void validatePlayer(ServerPlayer p,TeamState.Team t,Direction direction) {
        if(!p.isAlive()||p.isSpectator())throw new IllegalArgumentException("Player cannot exchange cash now");
        if(direction==Direction.WITHDRAW)TeamState.authorize(t,p.getUUID(),TeamState.Action.WITHDRAW,teams.configuration());
    }
}
