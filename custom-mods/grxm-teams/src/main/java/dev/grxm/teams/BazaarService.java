package dev.grxm.teams;

import dev.grxm.teams.BazaarState.*;
import dev.grxm.teams.mixin.PlayerListAccessor;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

public final class BazaarService {
    private final TeamService teams;
    private final MinecraftServer server;
    private final Random random=new java.security.SecureRandom();
    /** Trusted embedding hook for test mods, never a command or production property. */
    public enum Cut { PREPARED, PLAYER_SAVED, COMMITTED }
    private java.util.function.Consumer<Cut> observer=cut->{};
    public void installObserver(java.util.function.Consumer<Cut> observer){teams.ready();this.observer=Objects.requireNonNull(observer);}
    BazaarService(TeamService teams,MinecraftServer server){this.teams=teams;this.server=server;}
    public BazaarState snapshot(){return StateCodec.GSON.fromJson(StateCodec.GSON.toJson(teams.liveMarket()),BazaarState.class);}
    public BazaarState displaySnapshot(){
        BazaarState m=teams.liveMarket(),out=new BazaarState();out.generation=m.generation;out.start=m.start;out.end=m.end;out.denomination=m.denomination;out.offers=new ArrayList<>(m.offers);out.counters=new LinkedHashMap<>(m.counters);out.used=new LinkedHashMap<>(m.used);return out;
    }
    private TeamsConfig.Bazaar config(){return teams.configuration().bazaar;}
    private Path playerFile(UUID id){return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id+".dat");}
    public void recover() {
        BazaarState m=snapshot();
        try {
            for(Sale s:m.journal.values()) {
                if(!s.committed)throw new IllegalStateException("PREPARED sale "+s.id+" quarantined; no automatic payout/refund. Original account="+s.account+", generation="+s.generation);
                CompoundTag root=NbtIo.readCompressed(playerFile(s.player).toFile());
                if(!s.receipt.equals(root.getCompound("grxmteamsSales").getString(s.id.toString())))throw new IllegalStateException("Missing/conflicting committed player receipt "+s.id);
            }
        } catch(Exception e){throw new IllegalStateException("Bazaar recovery guard: "+e,e);}
        validatePool(config()); rotate();
    }
    public void validatePool(TeamsConfig.Bazaar c) {
        for(var e:c.pool)if(!BuiltInRegistries.ITEM.containsKey(new ResourceLocation(e.itemId))||BuiltInRegistries.ITEM.get(new ResourceLocation(e.itemId))==Items.AIR)throw new IllegalArgumentException("Unknown bazaar item "+e.itemId);
    }
    public void rotate() {
        teams.ready(); var c=config(); if(!c.enabled)return;
        long now=teams.nowMillis();BazaarState current=teams.liveMarket();if(current.generation>0&&now<current.end)return;BazaarState m=snapshot();
        validatePool(c); long period=Math.multiplyExact(c.periodSeconds,1000),epoch=Math.multiplyExact(c.epochSeconds,1000);
        long start=Math.addExact(epoch,Math.multiplyExact(Math.floorDiv(now-epoch,period),period));
        if(start<0)throw new IllegalArgumentException("Bazaar clock precedes supported epoch");
        m.generation=Math.addExact(m.generation,1);m.start=start;m.end=Math.addExact(start,period);m.offers=BazaarState.roll(c,random);m.denomination=c.denomination;
        // Old usage is not needed for admission; journal freezes old obligations independently.
        m.used.clear(); teams.persistBazaar(m);
    }
    public int remaining(UUID actor,int offer) {
        BazaarState m=teams.liveMarket();var t=teams.teamOf(actor); Offer o=m.offers.get(offer);
        return o.quota()-m.used.getOrDefault(BazaarState.key(t.id,m.generation,offer),0);
    }
    public static int inventoryCount(ServerPlayer player,String itemId) {
        Item item=BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));int n=0;
        for(int slot=0;slot<36;slot++){ItemStack stack=player.getInventory().getItem(slot);if(stack.is(item))n=Math.addExact(n,stack.getCount());}return n;
    }
    public UUID sell(ServerPlayer player,long generation,UUID teamId,int offer,int count) {
        teams.ready();rotate();var cfg=config();if(!cfg.enabled)throw new IllegalArgumentException("Bazaar is disabled until configured");
        BazaarState m=snapshot();var t=teams.teamOf(player.getUUID());
        if(!t.id.equals(teamId)||m.generation!=generation||offer<0||offer>=m.offers.size())throw new IllegalArgumentException("Stale shop; reopen the counter");
        if(!player.isAlive()||player.isSpectator())throw new IllegalArgumentException("Player cannot sell now");
        if(m.journal.size()>=cfg.journalCapacity)throw new IllegalArgumentException("Sale journal full; operator checkpoint required");
        if(m.journal.values().stream().anyMatch(s->s.player.equals(player.getUUID())&&!s.committed))throw new IllegalArgumentException("Player sale already pending");
        Offer o=m.offers.get(offer);String key=BazaarState.key(t.id,m.generation,offer);int used=m.used.getOrDefault(key,0);
        if(count<1||count>o.quota()-used||count>inventoryCount(player,o.itemId()))throw new IllegalArgumentException("Insufficient items or team quota");
        int payout=Math.toIntExact(Math.multiplyExact((long)count,o.unitPrice()));UUID destination=cfg.payPersonal?player.getUUID():t.account;
        BankAccount a=Numismatics.BANK.getAccount(destination);
        if(a==null||a.type!=(cfg.payPersonal?BankAccount.Type.PLAYER:BankAccount.Type.BLAZE_BANKER)||a.getBalance()<0||(long)a.getBalance()+payout>Integer.MAX_VALUE)throw new IllegalArgumentException("Missing destination or native balance overflow");
        ListTag before=player.getInventory().save(new ListTag()); Inventory proposed=new Inventory(player);proposed.load(before.copy());int left=count;Item item=BuiltInRegistries.ITEM.get(new ResourceLocation(o.itemId()));
        for(int slot=0;slot<36&&left>0;slot++){ItemStack stack=proposed.getItem(slot);if(stack.is(item)){int n=Math.min(left,stack.getCount());stack.shrink(n);left-=n;}}
        if(left!=0)throw new IllegalStateException("Inventory changed during validation");
        ListTag after=proposed.save(new ListTag());Sale sale=new Sale();sale.id=UUID.randomUUID();sale.player=player.getUUID();sale.team=t.id;sale.account=destination;sale.personal=cfg.payPersonal;sale.generation=m.generation;sale.offer=offer;sale.count=count;sale.unitPrice=o.unitPrice();sale.itemId=o.itemId();sale.payout=payout;sale.balanceBefore=a.getBalance();sale.beforeInventory=before.toString();sale.afterInventory=after.toString();sale.receipt=StateCodec.digest(StateCodec.GSON.toJson(sale));
        m.journal.put(sale.id,sale);m.used.put(key,Math.addExact(used,count));
        var prospective=teams.snapshot();prospective.bazaar=m;StateCodec.encode(prospective); // Reject aggregate-size/schema failure before admission.
        // Everything below is a durability boundary: never compensate using guessed inventory.
        try {
            teams.persistBazaar(m);observer.accept(Cut.PREPARED);
            player.getInventory().load(after);((PlayerReceipts)player).grxmteams$receipts().putString(sale.id.toString(),sale.receipt);
            ((PlayerListAccessor)server.getPlayerList()).grxmteams$playerIo().save(player);
            Path path=playerFile(player.getUUID());
            try(FileChannel f=FileChannel.open(path,StandardOpenOption.WRITE)){f.force(true);}
            CompoundTag actual=NbtIo.readCompressed(path.toFile());
            if(!actual.getList("Inventory",10).equals(after)||!sale.receipt.equals(actual.getCompound("grxmteamsSales").getString(sale.id.toString())))throw new IllegalStateException("Player save receipt/inventory mismatch");
            try(FileChannel dir=FileChannel.open(path.getParent(),StandardOpenOption.READ)){dir.force(true);}
            observer.accept(Cut.PLAYER_SAVED);
            if(a.getBalance()!=sale.balanceBefore)throw new IllegalStateException("Frozen account balance changed during sale");
            a.deposit(payout);sale.committed=true;teams.persistBazaar(m);observer.accept(Cut.COMMITTED);
            player.getInventory().setChanged();return sale.id;
        } catch(Exception e){RuntimeException uncertain=new IllegalStateException("Sale "+sale.id+" quarantined; operator recovery required",e);teams.quarantine(uncertain);throw uncertain;}
    }
    public Counter counterAt(String dim,BlockPos pos) {
        return snapshot().counters.values().stream().filter(c->c.dimension().equals(dim)&&c.x()==pos.getX()&&c.y()==pos.getY()&&c.z()==pos.getZ()).findFirst().orElse(null);
    }
    public void bindLook(ServerPlayer player,int slot) {
        teams.ready();if(slot<1||slot>3)throw new IllegalArgumentException("Slot must be 1, 2 or 3");
        HitResult hit=player.pick(config().bindReach,1,false);if(!(hit instanceof BlockHitResult b)||hit.getType()!=HitResult.Type.BLOCK)throw new IllegalArgumentException("Look at a solid counter within reach");
        var eye=player.getEyePosition();var end=eye.add(player.getViewVector(1).scale(config().bindReach));
        var obstructing=net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player,eye,end,player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1),entity->!entity.isSpectator()&&entity.isPickable(),eye.distanceToSqr(b.getLocation()));
        if(obstructing!=null)throw new IllegalArgumentException("An entity obstructs the looked-at counter");
        BlockPos pos=b.getBlockPos();var state=player.serverLevel().getBlockState(pos);
        // Deliberately narrow, non-financial vanilla blocks. Does not replace blocks or destroy inventories.
        if(!(state.is(Blocks.BARREL)||state.is(Blocks.LECTERN)||state.is(Blocks.SMOOTH_STONE)||state.is(Blocks.STONE)||state.is(Blocks.OAK_PLANKS)))throw new IllegalArgumentException("Counter must be a vanilla barrel, lectern, smooth stone, stone or oak planks; native vendors are not supported");
        BazaarState m=snapshot();Counter counter=new Counter(player.level().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ(),BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        m.counters.entrySet().removeIf(e->e.getValue().equals(counter));m.counters.put(slot,counter);teams.persistBazaar(m);
    }
    public void invalidate(String dimension,BlockPos pos) {
        teams.ready();BazaarState m=snapshot();if(m.counters.entrySet().removeIf(e->{var c=e.getValue();return c.dimension().equals(dimension)&&c.x()==pos.getX()&&c.y()==pos.getY()&&c.z()==pos.getZ();}))teams.persistBazaar(m);
    }
    public void retireChangedCounters() {
        teams.ready();BazaarState m=snapshot();boolean changed=m.counters.entrySet().removeIf(e->{var c=e.getValue();var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new ResourceLocation(c.dimension())));BlockPos pos=new BlockPos(c.x(),c.y(),c.z());return level!=null&&level.hasChunkAt(pos)&&!BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(c.blockId());});
        if(changed)teams.persistBazaar(m);
    }
    public void unbind(int slot){teams.ready();BazaarState m=snapshot();m.counters.remove(slot);teams.persistBazaar(m);}
    public void open(ServerPlayer player,Counter counter) {
        teams.ready();rotate();if(!config().enabled)throw new IllegalArgumentException("Bazaar disabled");
        BazaarState m=snapshot();int slot=m.counters.entrySet().stream().filter(e->e.getValue().equals(counter)).map(Map.Entry::getKey).findFirst().orElseThrow();
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id,inventory,p)->new BazaarMenu(id,player,this,slot,counter),net.minecraft.network.chat.Component.literal(config().title+" #"+slot)));
    }
}
