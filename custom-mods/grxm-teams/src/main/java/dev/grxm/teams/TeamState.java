package dev.grxm.teams;

import java.util.*;

/** Versioned durable model, serialized only inside Numismatics' bank NBT. */
public final class TeamState {
    public int version = 2;
    public Map<UUID, Team> teams = new LinkedHashMap<>();
    public BazaarState bazaar = new BazaarState();
    public enum Role { OWNER, OFFICER, MEMBER }
    public enum Action { INVITE, STYLE, CHUNKS, WITHDRAW, ADMINISTER }
    public record Chunk(String dimension,int x,int z) implements Comparable<Chunk> {
        public Chunk { if(dimension==null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || Math.abs((long)x)>1875000 || Math.abs((long)z)>1875000) throw new IllegalArgumentException("Invalid chunk"); }
        @Override public int compareTo(Chunk c) { int d=dimension.compareTo(c.dimension); if(d!=0)return d; d=Integer.compare(x,c.x); return d==0?Integer.compare(z,c.z):d; }
    }
    public static final class Team {
        public UUID id, account;
        public String name, color, badge;
        public Map<UUID,Role> members = new LinkedHashMap<>();
        public Map<UUID,Long> invites = new LinkedHashMap<>();
        public Set<Chunk> chunks = new LinkedHashSet<>();
        public long leaseStart, leaseEnd, termMillis;
        public TeamsConfig.Billing termRate;
        public int memberSnapshot, paidCapacity;
        public int lastPayment;
        public long lastPaymentAt;
        public boolean suspended;
    }
    public static void authorize(Team t,UUID actor,Action action,TeamsConfig c) {
        Role r=t.members.get(actor);
        boolean allowed=r==Role.OWNER || r==Role.OFFICER && switch(action) {
            case INVITE -> c.permissions.officersInvite; case STYLE -> c.permissions.officersStyle;
            case CHUNKS -> c.permissions.officersManageChunks; case WITHDRAW -> c.permissions.officersWithdraw;
            case ADMINISTER -> false;
        };
        if(!allowed) throw new IllegalArgumentException("Your team role cannot perform this action");
    }
    public Team member(UUID id) { return teams.values().stream().filter(t->t.members.containsKey(id)).findFirst().orElseThrow(()->new IllegalArgumentException("You are not in a team")); }
    public Team byId(UUID id) { Team t=teams.get(id); if(t==null)throw new IllegalArgumentException("Unknown team"); return t; }
    public void validate() {
        if(version!=2 || teams==null || teams.size()>10000) throw new IllegalArgumentException("Unsupported/corrupt team state");
        if(bazaar==null)throw new IllegalArgumentException("Missing bazaar"); bazaar.validate();
        Set<UUID> members=new HashSet<>(), accounts=new HashSet<>(); Set<Chunk> chunks=new HashSet<>(); Set<String> names=new HashSet<>();
        teams.forEach((id,t)->{
            if(id==null || t==null || !id.equals(t.id) || t.account==null || t.account.equals(id) || !accounts.add(t.account)) throw new IllegalArgumentException("Invalid team/account identity");
            TeamsConfig.plain(t.name,48);
            if(t.name.isBlank() || !names.add(t.name.toLowerCase(Locale.ROOT)) || t.color==null || t.badge==null || !t.color.matches("[a-z_]{1,24}") || !t.badge.matches("[a-z0-9_-]{1,24}") || t.members==null || t.members.isEmpty() || t.members.size()>1000 || t.invites==null || t.invites.size()>10000 || t.chunks==null || t.chunks.size()>4096) throw new IllegalArgumentException("Invalid team data");
            if(t.members.values().stream().filter(r->r==Role.OWNER).count()!=1) throw new IllegalArgumentException("Team must have exactly one owner");
            t.members.forEach((u,r)->{if(u==null||r==null||!members.add(u)) throw new IllegalArgumentException("Invalid/duplicate membership");});
            t.invites.forEach((u,e)->{if(u==null||e==null||e<0) throw new IllegalArgumentException("Invalid invitation");});
            for(Chunk c:t.chunks) if(c==null || !chunks.add(new Chunk(c.dimension,c.x,c.z))) throw new IllegalArgumentException("Duplicate chunk ownership");
            if(t.termRate!=null) { TeamsConfig.decimal(t.termRate.scalingFactor);TeamsConfig.decimal(t.termRate.teamMemberScaling);if(t.termRate.maximumBill<1)throw new IllegalArgumentException("Invalid term rate snapshot"); }
            if(t.leaseStart<0 || t.leaseEnd<0 || t.termMillis<0 || t.memberSnapshot<0 || t.memberSnapshot>1000 || t.paidCapacity<0 || t.paidCapacity>4096 || t.lastPayment<0 || t.lastPaymentAt<0) throw new IllegalArgumentException("Invalid lease receipt");
            if(t.leaseEnd!=0 && (t.leaseEnd<=t.leaseStart || t.termMillis!=t.leaseEnd-t.leaseStart || t.memberSnapshot<1 || t.paidCapacity<t.chunks.size())) throw new IllegalArgumentException("Inconsistent prepaid lease");
        });
        for(UUID a:accounts) if(members.contains(a)) throw new IllegalArgumentException("Team account aliases player");
    }
}
