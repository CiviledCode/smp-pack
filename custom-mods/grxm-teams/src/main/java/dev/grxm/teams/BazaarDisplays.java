package dev.grxm.teams;
import dev.grxm.teams.BazaarState.*;
import dev.grxm.teams.mixin.TextDisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import java.util.*;

/** Packet-only entities, never added to a level, saved, ticked, dropped or picked up. */
public final class BazaarDisplays {
    private final TeamService teams;
    private final MinecraftServer server;
    private final Map<UUID,Map<Integer,View>> views=new HashMap<>();
    private long ticks;
    private record View(Counter counter,long generation,String fingerprint,Display.ItemDisplay item,Display.TextDisplay text) {}
    BazaarDisplays(TeamService teams,MinecraftServer server){this.teams=teams;this.server=server;}
    public void clear(ServerPlayer player){Map<Integer,View> old=views.remove(player.getUUID());if(old!=null)old.values().forEach(v->remove(player,v));}
    public void stop(){for(ServerPlayer player:server.getPlayerList().getPlayers())clear(player);views.clear();}
    private static void remove(ServerPlayer player,View v){player.connection.send(new ClientboundRemoveEntitiesPacket(v.item.getId(),v.text.getId()));}
    private static void data(ServerPlayer player,Entity entity){var list=entity.getEntityData().getNonDefaultValues();if(list!=null)player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(),list));}
    public void tick() {
        var cfg=teams.configuration().bazaar;
        if(++ticks%cfg.displayRefreshTicks!=0)return;
        if(!cfg.displaysEnabled||teams.failure()!=null){stop();return;}
        BazaarState m=teams.bazaar().displaySnapshot();
        Set<UUID> online=new HashSet<>();
        for(ServerPlayer player:server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());var current=views.computeIfAbsent(player.getUUID(),u->new HashMap<>());Set<Integer> wanted=new HashSet<>();
            for(var entry:m.counters.entrySet()) {
                int slot=entry.getKey();Counter c=entry.getValue();BlockPos pos=new BlockPos(c.x(),c.y(),c.z());
                if(!player.level().dimension().location().toString().equals(c.dimension())||player.distanceToSqr(c.x()+.5,c.y()+.5,c.z()+.5)>cfg.displayRadius*cfg.displayRadius||!player.level().hasChunkAt(pos)||!BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).getBlock()).toString().equals(c.blockId()))continue;
                wanted.add(slot);String fingerprint=cfg.itemHeight+"/"+cfg.textHeight+"/"+player.level().dimension().location();View view=current.get(slot);
                if(view==null||!view.counter.equals(c)||view.generation!=m.generation||!view.fingerprint.equals(fingerprint)) {
                    if(view!=null)remove(player,view);
                    Display.ItemDisplay item=new Display.ItemDisplay(EntityType.ITEM_DISPLAY,player.level());
                    CompoundTag itemTag=new CompoundTag();itemTag.putString("billboard","center");itemTag.putString("item_display","ground");item.load(itemTag);
                    if(slot<=m.offers.size())item.getSlot(0).set(new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(m.offers.get(slot-1).itemId()))));
                    else item.getSlot(0).set(new ItemStack(Items.BARRIER));
                    item.setPos(c.x()+.5,c.y()+cfg.itemHeight,c.z()+.5);
                    Display.TextDisplay text=new Display.TextDisplay(EntityType.TEXT_DISPLAY,player.level());CompoundTag tag=new CompoundTag();tag.putString("billboard","center");tag.putInt("line_width",240);tag.putBoolean("shadow",true);text.load(tag);text.setPos(c.x()+.5,c.y()+cfg.textHeight,c.z()+.5);
                    view=new View(c,m.generation,fingerprint,item,text);current.put(slot,view);
                    player.connection.send(item.getAddEntityPacket());data(player,item);player.connection.send(text.getAddEntityPacket());
                }
                ((TextDisplayAccessor)view.text).grxmteams$setText(Component.literal(label(player,m,slot,cfg)));data(player,view.text);
            }
            Iterator<Map.Entry<Integer,View>> it=current.entrySet().iterator();while(it.hasNext()){var e=it.next();if(!wanted.contains(e.getKey())){remove(player,e.getValue());it.remove();}}
        }
        views.keySet().removeIf(id->!online.contains(id));
    }
    private String label(ServerPlayer player,BazaarState m,int slot,TeamsConfig.Bazaar cfg) {
        if(!cfg.enabled)return cfg.closedText;
        if(slot>m.offers.size())return cfg.inactiveText;
        Offer o=m.offers.get(slot-1);String remaining;
        try{remaining=Integer.toString(teams.bazaar().remaining(player.getUUID(),slot-1));}catch(IllegalArgumentException e){remaining=cfg.noTeamText;}
        long seconds=Math.max(0,(m.end-teams.nowMillis()+999)/1000),days=seconds/86400;seconds%=86400;
        String time=(days>0?days+"d ":"")+String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds%3600/60,seconds%60);
        return cfg.displayText.replace("{item}",new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(o.itemId()))).getHoverName().getString()).replace("{remaining}",remaining).replace("{quota}",Integer.toString(o.quota())).replace("{price}",Integer.toString(o.unitPrice())).replace("{denomination}",m.denomination).replace("{reset}",time);
    }
}
