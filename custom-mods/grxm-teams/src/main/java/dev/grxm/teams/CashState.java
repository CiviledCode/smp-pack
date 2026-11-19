package dev.grxm.teams;

import java.util.*;

/** Separate bounded physical-currency journal; never prunes or reuses bazaar sale proofs. */
public final class CashState {
    public static final int CAPACITY=4096;
    /** Pinned Numismatics 1.0.15 SPUR.value; runtime verifies against the native enum. */
    public static final int SPURS_PER_ITEM=1;
    public Map<UUID,Transfer> journal=new LinkedHashMap<>();
    public enum Direction { DEPOSIT, WITHDRAW }
    public static final class Transfer {
        public UUID id,player,team,account;
        public Direction direction;
        public int itemCount,spurs,balanceBefore;
        public String itemId="numismatics:spur";
        public int unitsPerItem=SPURS_PER_ITEM;
        public String beforeInventory,afterInventory,receipt;
        public boolean committed;
    }
    public static int spurs(int count) {
        if(count<=0)throw new IllegalArgumentException("SPUR count must be positive and fit the native treasury");
        return count;
    }
    public static String proof(Transfer t) {
        Transfer copy=StateCodec.GSON.fromJson(StateCodec.GSON.toJson(t),Transfer.class);
        copy.receipt=null;copy.committed=false;return StateCodec.digest(StateCodec.GSON.toJson(copy));
    }
    public void validate() {
        if(journal==null||journal.size()>CAPACITY)throw new IllegalArgumentException("Invalid cash journal");
        Set<UUID> pending=new HashSet<>();
        journal.forEach((id,t)->{
            if(id==null||t==null||!id.equals(t.id)||t.player==null||t.team==null||t.account==null||t.direction==null||!"numismatics:spur".equals(t.itemId)||t.unitsPerItem!=SPURS_PER_ITEM||t.spurs!=spurs(t.itemCount)||t.balanceBefore<0||t.beforeInventory==null||t.afterInventory==null||t.receipt==null||!t.receipt.matches("[0-9a-f]{64}")||!t.receipt.equals(proof(t)))throw new IllegalArgumentException("Invalid cash transfer proof");
            if(t.direction==Direction.DEPOSIT?(long)t.balanceBefore+t.spurs>Integer.MAX_VALUE:t.balanceBefore<t.spurs)throw new IllegalArgumentException("Invalid cash balance proof");
            if(!t.committed&&!pending.add(t.player))throw new IllegalArgumentException("Duplicate unresolved cash transfer");
        });
    }
}
