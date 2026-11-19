package dev.grxm.teams;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;

/** Plans only slots 0..35, retaining the actual extended serializer's full NBT image. */
final class CashInventory {
    final ListTag before,after;
    final ItemStack[] main=new ItemStack[36];
    CashInventory(ServerPlayer p,ItemStack currency,int count,boolean deposit) {
        before=p.getInventory().save(new ListTag());
        for(int slot=0;slot<36;slot++)main[slot]=p.getInventory().getItem(slot).copy();
        int left=count;
        if(deposit) {
            for(int slot=0;slot<36&&left>0;slot++)if(main[slot].is(currency.getItem())) {int n=Math.min(left,main[slot].getCount());main[slot].shrink(n);left-=n;}
        } else {
            for(int slot=0;slot<36&&left>0;slot++)if(!main[slot].isEmpty()&&ItemStack.isSameItemSameTags(main[slot],currency)) {
                int n=Math.min(left,Math.max(0,Math.min(main[slot].getMaxStackSize(),p.getInventory().getMaxStackSize())-main[slot].getCount()));main[slot].grow(n);left-=n;
            }
            for(int slot=0;slot<36&&left>0;slot++)if(main[slot].isEmpty()) {int n=Math.min(left,Math.min(currency.getMaxStackSize(),p.getInventory().getMaxStackSize()));main[slot]=currency.copyWithCount(n);left-=n;}
        }
        if(left!=0)throw new IllegalArgumentException(deposit?"Not enough physical SPUR items in main inventory":"Not enough main inventory space for SPUR items; nothing withdrawn");
        // Reproduce the real serializer's main-slot order, retaining all non-main entries.
        after=new ListTag();
        for(int slot=0;slot<36;slot++)if(!main[slot].isEmpty())after.add(entry(slot,main[slot]));
        for(int i=0;i<before.size();i++)if((before.getCompound(i).getByte("Slot")&255)>=36)after.add(before.get(i).copy());
    }
    private static CompoundTag entry(int slot,ItemStack stack){CompoundTag tag=new CompoundTag();tag.putByte("Slot",(byte)slot);return stack.save(tag);}
    void apply(ServerPlayer p) {
        if(!p.getInventory().save(new ListTag()).equals(before))throw new IllegalStateException("Inventory changed after cash preparation");
        for(int slot=0;slot<36;slot++)if(!ItemStack.matches(p.getInventory().getItem(slot),main[slot]))p.getInventory().setItem(slot,main[slot].copy());
        p.getInventory().setChanged();
    }
}
