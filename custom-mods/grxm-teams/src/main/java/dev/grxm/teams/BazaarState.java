package dev.grxm.teams;
import java.util.*;

/** Journal is deliberately bounded and never automatically compacted. */
public final class BazaarState {
    public long generation, start, end;
    public String denomination="spurs";
    public List<Offer> offers=new ArrayList<>();
    public Map<Integer,Counter> counters=new LinkedHashMap<>();
    public Map<String,Integer> used=new LinkedHashMap<>();
    public Map<UUID,Sale> journal=new LinkedHashMap<>();
    public record Offer(String itemId,int unitPrice,int quota) {}
    public record Counter(String dimension,int x,int y,int z,String blockId) {}
    public static class Sale {
        public UUID id,player,team,account;
        public long generation;
        public int offer,count,unitPrice,payout,balanceBefore;
        public String itemId,beforeInventory,afterInventory,receipt;
        public boolean personal,committed;
    }
    public static String key(UUID team,long generation,int offer) { return team+"/"+generation+"/"+offer; }
    public void validate() {
        if(generation<0||start<0||end<0||generation>0&&end<=start||offers==null||offers.size()>3||counters==null||counters.size()>3||used==null||used.size()>30000||journal==null||journal.size()>4096)throw new IllegalArgumentException("Invalid bazaar state");
        TeamsConfig.plain(denomination,64); Set<String> ids=new HashSet<>();
        for(Offer o:offers)if(o==null||o.itemId()==null||!o.itemId().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")||!ids.add(o.itemId())||o.unitPrice()<1||o.quota()<1||(long)o.unitPrice()*o.quota()>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid offer");
        counters.forEach((slot,c)->{if(slot==null||slot<1||slot>3||c==null||c.dimension()==null||c.blockId()==null||c.y() < -2048||c.y()>2048||Math.abs((long)c.x())>30000000||Math.abs((long)c.z())>30000000)throw new IllegalArgumentException("Invalid counter");});
        used.forEach((k,n)->{if(k==null||!k.matches("[0-9a-f-]{36}/[0-9]+/[0-2]")||n==null||n<0)throw new IllegalArgumentException("Invalid quota usage");});
        journal.forEach((id,s)->{
            if(s==null||!id.equals(s.id)||s.player==null||s.team==null||s.account==null||s.generation<1||s.offer<0||s.offer>2||s.count<1||s.unitPrice<1||s.payout!=(long)s.count*s.unitPrice||s.balanceBefore<0||(long)s.balanceBefore+s.payout>Integer.MAX_VALUE||s.itemId==null||s.beforeInventory==null||s.afterInventory==null||s.receipt==null||!s.receipt.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid sale proof");
        });
    }
    public static List<Offer> roll(TeamsConfig.Bazaar c,Random random) {
        c.validate(); List<TeamsConfig.PoolEntry> pool=new ArrayList<>(c.pool.stream().filter(e->e.rarityWeight>0).toList()); List<Offer> out=new ArrayList<>();
        for(int i=0;i<c.offerCount;i++) {
            double total=pool.stream().mapToDouble(e->e.rarityWeight).sum(); double r=random.nextDouble()*total; int chosen=pool.size()-1;
            for(int j=0;j<pool.size();j++) { r-=pool.get(j).rarityWeight; if(r<0){chosen=j;break;} }
            var e=pool.remove(chosen); out.add(new Offer(e.itemId,e.minItemPrice+random.nextInt(e.maxItemPrice-e.minItemPrice+1),e.minItemCount+random.nextInt(e.maxItemCount-e.minItemCount+1)));
        }
        return out;
    }
}
