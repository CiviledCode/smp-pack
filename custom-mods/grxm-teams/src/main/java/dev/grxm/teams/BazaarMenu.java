package dev.grxm.teams;
import dev.grxm.teams.BazaarState.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import java.util.*;

/** Vanilla chest protocol; no display stack or player slot can ever be moved by this menu. */
public final class BazaarMenu extends AbstractContainerMenu {
    private final ServerPlayer viewer;
    private final BazaarService shop;
    private final Counter counter;
    private final long generation;
    private final UUID team;
    private final boolean payPersonal;
    private final int offerIndex;
    private final SimpleContainer display=new SimpleContainer(27);
    public BazaarMenu(int id,ServerPlayer player,BazaarService shop,int slot,Counter counter) {
        super(MenuType.GENERIC_9x3,id);this.viewer=player;this.shop=shop;this.counter=counter;this.offerIndex=slot-1;
        payPersonal=TeamsMod.service().configuration().bazaar.payPersonal;BazaarState m=shop.displaySnapshot();generation=m.generation;team=TeamsMod.service().teamOf(player.getUUID()).id;
        for(int n=0;n<27;n++)addSlot(new Slot(display,n,8+(n%9)*18,18+(n/9)*18){@Override public boolean mayPlace(ItemStack s){return false;} @Override public boolean mayPickup(Player p){return false;}});
        for(int n=0;n<36;n++)addSlot(new Slot(player.getInventory(),n<27?n+9:n-27,8+(n%9)*18,n<27?85+(n/9)*18:143){@Override public boolean mayPlace(ItemStack s){return false;} @Override public boolean mayPickup(Player p){return false;}});
        refresh();
    }
    private static ItemStack icon(Item item,String title,String... lines) {
        ItemStack stack=new ItemStack(item);stack.setHoverName(Component.literal(title));ListTag lore=new ListTag();
        for(String line:lines)lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(line))));stack.getOrCreateTagElement("display").put("Lore",lore);return stack;
    }
    private void refresh() {
        display.clearContent();BazaarState m=shop.displaySnapshot();
        if(offerIndex>=m.offers.size()){display.setItem(13,icon(Items.BARRIER,"Counter inactive this week"));return;}
        Offer o=m.offers.get(offerIndex);int remaining=shop.remaining(viewer.getUUID(),offerIndex);String destination=TeamsMod.service().configuration().bazaar.payPersonal?"your personal bank":"team treasury";
        display.setItem(4,icon(BuiltInRegistries.ITEM.get(new ResourceLocation(o.itemId())),o.itemId(),"Unit price: "+o.unitPrice()+" "+m.denomination,"Team quota left: "+remaining+" / "+o.quota(),"Destination: "+destination,"Week end (UTC): "+java.time.Instant.ofEpochMilli(m.end)));
        int all=Math.min(remaining,BazaarService.inventoryCount(viewer,o.itemId()));int[] amounts={1,16,64,all};
        for(int n=0;n<4;n++){int amount=amounts[n];display.setItem(10+n,icon(Items.PAPER,"Sell "+(n==3?"All ("+amount+")":amount),"Payout: "+((long)amount*o.unitPrice())+" "+m.denomination,"To: "+destination,"Requires "+amount+" ordinary inventory items","Remaining team allowance: "+remaining));}
        display.setItem(22,icon(Items.BOOK,"Per-team weekly allowance","All team members share this quota","Team changes/recreation may reset allowance","No per-player cap; only this counter's offer"));
    }
    @Override public void clicked(int slot,int button,ClickType type,Player player) {
        if(player!=viewer||type!=ClickType.PICKUP||button!=0||slot<10||slot>13)return;
        try {
            if(!stillValid(player))throw new IllegalArgumentException("Counter out of reach or no longer bound");
            if(payPersonal!=TeamsMod.service().configuration().bazaar.payPersonal)throw new IllegalArgumentException("Payout destination changed; reopen counter");
            BazaarState m=shop.displaySnapshot();if(m.generation!=generation||offerIndex>=m.offers.size())throw new IllegalArgumentException("Stale shop; reopen counter");
            int count=switch(slot){case 10->1;case 11->16;case 12->64;default->Math.min(shop.remaining(player.getUUID(),offerIndex),BazaarService.inventoryCount(viewer,m.offers.get(offerIndex).itemId()));};
            shop.sell(viewer,generation,team,offerIndex,count);viewer.sendSystemMessage(Component.literal("Sold "+count+" items."));refresh();broadcastChanges();
        } catch(RuntimeException e){viewer.sendSystemMessage(Component.literal("Bazaar: "+e.getMessage()));}
    }
    @Override public boolean stillValid(Player player) {
        BlockPos pos=new BlockPos(counter.x(),counter.y(),counter.z());
        if(player!=viewer||!player.level().dimension().location().toString().equals(counter.dimension())||player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64)return false;
        return counter.equals(shop.displaySnapshot().counters.get(offerIndex+1))&&BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).getBlock()).toString().equals(counter.blockId());
    }
    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    @Override public boolean canTakeItemForPickAll(ItemStack stack,Slot slot){return false;}
}
