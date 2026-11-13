package dev.grxm.teams;

import dev.grxm.teams.TeamState.*;
import dev.grxm.teams.mixin.GlobalBankManagerAccessor;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Public server-thread API; snapshots are detached. No API grants or creates personal money. */
public final class TeamService {
    private final MinecraftServer server;
    private final java.util.function.LongSupplier timeSource;
    public long nowMillis() { return timeSource.getAsLong(); }
    private TeamsConfig config;
    private TeamState state;
    private BankSavedData bankData;
    private TeamDataAccess attachment;
    private String failure;
    private final Set<Chunk> tickets=new TreeSet<>();
    private long ticks;
    private int admittedThisTick;
    private static final TicketType<ChunkPos> TICKET=TicketType.create("grxmteams",Comparator.comparingLong(ChunkPos::toLong));

    public TeamService(MinecraftServer server,TeamsConfig config) { this(server,config,System::currentTimeMillis); }
    public TeamService(MinecraftServer server,TeamsConfig config,java.util.function.LongSupplier timeSource) {
        this.server=server; this.config=config; this.timeSource=Objects.requireNonNull(timeSource);
        try {
            config.validate();
            bankData=((GlobalBankManagerAccessor)Numismatics.BANK).grxmteams$savedData();
            if(bankData==null || !(bankData instanceof TeamDataAccess)) throw new IllegalStateException("Numismatics bank attachment unavailable");
            attachment=(TeamDataAccess)bankData;
            BankPersistence.requireAttachment(attachment);
            if(attachment.grxmteams$getError()!=null) throw new IllegalStateException(attachment.grxmteams$getError());
            state=attachment.grxmteams$getJson()==null?new TeamState():StateCodec.decode(attachment.grxmteams$getJson());
            validateAccounts(state); validateConfiguredState(state,config);
            publish(state,StateCodec.encode(state)); // Verify the loaded bank and repair a missing sticky guard before admission.
            bazaar=new BazaarService(this,server); bazaar.recover(); displays=new BazaarDisplays(this,server);
            reconcile(nowMillis());
            refreshPresentation();
        } catch(RuntimeException e) { fail(e); }
    }
    private void thread() { if(!server.isSameThread()) throw new IllegalStateException("Team mutations must run on the Minecraft server thread"); }
    void ready() { thread(); if(BankPersistence.failure()!=null)fail(new IllegalStateException(BankPersistence.failure())); if(failure!=null)throw new IllegalStateException("Teams disabled: "+failure); if(bankData!=((GlobalBankManagerAccessor)Numismatics.BANK).grxmteams$savedData()) { fail(new IllegalStateException("Bank instance changed")); throw new IllegalStateException(failure); } }
    private void financial() { ready(); if(!config.billing.enabled&&!config.bazaar.enabled)throw new IllegalArgumentException("Economy transfers are disabled until billing or bazaar is explicitly configured and enabled"); }
    private void fail(RuntimeException e) { BankPersistence.freeze(); failure=e.getMessage()==null?e.toString():e.getMessage(); removeTickets(); TeamsMod.LOGGER.error("GRXM Teams failed closed: {}",failure,e); }
    public BazaarState.Counter boundCounter(String dim,net.minecraft.core.BlockPos pos) {
        thread(); if(state==null||state.bazaar==null)return null;
        return state.bazaar.counters.values().stream().filter(c->c.dimension().equals(dim)&&c.x()==pos.getX()&&c.y()==pos.getY()&&c.z()==pos.getZ()).findFirst().orElse(null);
    }
    public String failure() { return failure; }
    public TeamsConfig configuration() { return StateCodec.GSON.fromJson(StateCodec.GSON.toJson(config),TeamsConfig.class); }
    public TeamState snapshot() { ready(); return copy(); }
    private TeamState copy() { return StateCodec.decode(StateCodec.encode(state)); }
    public Team teamOf(UUID actor) { ready(); return StateCodec.GSON.fromJson(StateCodec.GSON.toJson(state.member(actor)),Team.class); }
    private BankAccount account(Team t) {
        BankAccount a=Numismatics.BANK.getAccount(t.account);
        if(a==null || a.type!=BankAccount.Type.BLAZE_BANKER || !a.id.equals(t.account) || a.getBalance()<0) {
            IllegalStateException e=new IllegalStateException("Missing/invalid treasury account; refusing replacement"); fail(e); throw e;
        }
        return a;
    }
    private BankAccount personal(UUID actor) { BankAccount a=Numismatics.BANK.getAccount(actor); if(a==null || a.type!=BankAccount.Type.PLAYER || !a.id.equals(actor)) throw new IllegalArgumentException("A native personal Numismatics bank account is required"); return a; }
    private void validateAccounts(TeamState s) { for(Team t:s.teams.values()) account(t); }
    private void publish(TeamState next,String encoded) {
        attachment.grxmteams$setJson(encoded); state=next; bankData.setDirty();
        try { BankPersistence.commit(bankData); } catch(RuntimeException e) { fail(e); server.halt(false); throw e; }
    }
    BazaarState liveMarket(){ready();return state.bazaar;}
    void persistBazaar(BazaarState market) { TeamState next=copy(); next.bazaar=market; publish(next); }
    void quarantine(RuntimeException e) { fail(e); server.halt(false); }
    public final BazaarService bazaar() { ready(); return bazaar; }
    private BazaarService bazaar;
    private BazaarDisplays displays;
    public void clearDisplays(ServerPlayer player){if(displays!=null)displays.clear(player);}
    private void publish(TeamState next) { publish(next,StateCodec.encode(next)); }
    private static void capacity(BankAccount target,int amount) { if(amount<0 || target.getBalance()<0 || (long)target.getBalance()+amount>Integer.MAX_VALUE) throw new IllegalArgumentException("Native bank balance would overflow"); }
    private static void move(BankAccount from,BankAccount to,int amount) { capacity(to,amount); if(amount<=0 || from.getBalance()<amount)throw new IllegalArgumentException("Insufficient funds or invalid amount"); if(!from.deduct(amount))throw new IllegalArgumentException("Insufficient funds"); to.deposit(amount); }
    private static void name(String name,TeamsConfig c) { TeamsConfig.plain(name,c.limits.maxNameLength); if(name.isBlank() || !name.equals(name.strip()) || !name.matches("[\\p{L}\\p{N} _.'-]+"))throw new IllegalArgumentException("Team names may contain letters, digits, spaces, _, ., apostrophe and -"); }
    private void availableName(TeamState next,String name,UUID self) { name(name,config); if(next.teams.values().stream().anyMatch(t->!t.id.equals(self)&&t.name.equalsIgnoreCase(name)))throw new IllegalArgumentException("Team name is already used"); }
    private void noOtherMembership(TeamState next,UUID who) { if(next.teams.values().stream().anyMatch(t->t.members.containsKey(who)))throw new IllegalArgumentException("Player already belongs to a team"); Presentation.checkPlayerAvailable(server,next,who); }

    public UUID create(UUID actor,String name) {
        ready(); TeamState next=copy(); noOtherMembership(next,actor); availableName(next,name,null);
        if(next.teams.size()>=config.limits.maxTeams)throw new IllegalArgumentException("Team limit reached");
        Team t=new Team(); t.id=UUID.randomUUID(); do { t.account=UUID.randomUUID(); } while(Numismatics.BANK.getAccount(t.account)!=null || t.account.equals(t.id));
        Presentation.checkKeyAvailable(server,t.id);
        t.name=name; t.color=config.presentation.defaultColor; t.badge=config.presentation.defaultBadge; t.members.put(actor,Role.OWNER); next.teams.put(t.id,t);
        String encoded=StateCodec.encode(next);
        BankAccount bank=Numismatics.BANK.getOrCreateAccount(t.account,BankAccount.Type.BLAZE_BANKER);
        if(bank.getBalance()!=0 || bank.type!=BankAccount.Type.BLAZE_BANKER)throw new IllegalStateException("Native treasury did not start empty");
        bank.updateTrustList(List::clear); bank.setLabel("GRXM Team "+t.id); publish(next,encoded); refreshPresentation(); return t.id;
    }
    public void invite(UUID actor,UUID target) {
        ready(); TeamState next=copy(); Team t=next.member(actor); TeamState.authorize(t,actor,Action.INVITE,config); noOtherMembership(next,target);
        long now=nowMillis(); t.invites.entrySet().removeIf(e->e.getValue()<=now);
        if(t.members.size()>=config.limits.maxMembers || t.invites.size()>=config.limits.maxInvitesPerTeam && !t.invites.containsKey(target))throw new IllegalArgumentException("Member/invitation cap reached");
        t.invites.put(target,Prices.nextEnd(now,config.limits.inviteSeconds)); publish(next);
        var p=server.getPlayerList().getPlayer(target); if(p!=null)p.sendSystemMessage(Component.literal(message("invite").replace("{team}",t.name).replace("{id}",t.id.toString())));
    }
    public void accept(UUID actor,UUID team) {
        ready(); TeamState next=copy(); noOtherMembership(next,actor); Team t=next.byId(team);
        Long expiry=t.invites.get(actor); if(expiry==null || expiry<=nowMillis())throw new IllegalArgumentException("No valid invitation");
        if(t.members.size()>=config.limits.maxMembers)throw new IllegalArgumentException("Team member cap reached");
        t.members.put(actor,Role.MEMBER); for(Team other:next.teams.values()) other.invites.remove(actor); publish(next); refreshPresentation();
    }
    public void leave(UUID actor) { ready(); TeamState next=copy(); Team t=next.member(actor); if(t.members.get(actor)==Role.OWNER)throw new IllegalArgumentException("Transfer ownership or disband first"); t.members.remove(actor); publish(next); refreshPresentation(); }
    public void role(UUID actor,UUID target,Role role) {
        ready(); if(role==Role.OWNER)throw new IllegalArgumentException("Use transfer for ownership"); TeamState next=copy(); Team t=next.member(actor); TeamState.authorize(t,actor,Action.ADMINISTER,config);
        if(!t.members.containsKey(target) || actor.equals(target))throw new IllegalArgumentException("Invalid role target"); t.members.put(target,role); publish(next);
    }
    public void transfer(UUID actor,UUID target) { ready(); TeamState next=copy(); Team t=next.member(actor); TeamState.authorize(t,actor,Action.ADMINISTER,config); if(actor.equals(target)||!t.members.containsKey(target))throw new IllegalArgumentException("New owner must be another member"); t.members.put(actor,Role.OFFICER); t.members.put(target,Role.OWNER); publish(next); }
    public void disband(UUID actor) {
        ready(); TeamState next=copy(); Team t=next.member(actor); TeamState.authorize(t,actor,Action.ADMINISTER,config);
        BankAccount treasury=account(t); int balance=treasury.getBalance(); BankAccount owner=balance>0?personal(actor):null; if(balance>0) { financial(); capacity(owner,balance); }
        next.teams.remove(t.id); String encoded=StateCodec.encode(next); if(balance>0)move(treasury,owner,balance); publish(next,encoded); refreshPresentation(); reconcileTickets(nowMillis());
    }
    public void style(UUID actor,String name,String color,String badge) {
        ready(); TeamState next=copy(); Team t=next.member(actor); TeamState.authorize(t,actor,Action.STYLE,config); availableName(next,name,t.id);
        if(!config.presentation.colors.contains(color)||!config.presentation.badges.containsKey(badge))throw new IllegalArgumentException("Unconfigured color or badge");
        t.name=name; t.color=color; t.badge=badge; publish(next); refreshPresentation();
    }
    public int balance(UUID actor) { ready(); return account(state.member(actor)).getBalance(); }
    public void deposit(UUID actor,int amount) {
        financial(); Team t=state.member(actor); if(amount<=0)throw new IllegalArgumentException("Amount must be positive");
        BankAccount from=personal(actor),to=account(t); capacity(to,amount); String encoded=StateCodec.encode(state); move(from,to,amount); publish(state,encoded); reconcile(nowMillis());
    }
    public void withdraw(UUID actor,int amount) {
        financial(); Team t=state.member(actor); TeamState.authorize(t,actor,Action.WITHDRAW,config); if(amount<=0)throw new IllegalArgumentException("Amount must be positive");
        BankAccount from=account(t),to=personal(actor); capacity(to,amount); String encoded=StateCodec.encode(state); move(from,to,amount); publish(state,encoded);
    }
    private void chunkAllowed(Chunk c) { if(!config.limits.allowedDimensions.contains(c.dimension()) || level(c)==null)throw new IllegalArgumentException("Dimension is not allowed or loaded"); }
    private int selected(TeamState s) { return s.teams.values().stream().mapToInt(t->t.chunks.size()).sum(); }
    public void addChunk(UUID actor,Chunk chunk) {
        ready(); chunkAllowed(chunk); TeamState next=copy(); Team t=next.member(actor); TeamState.authorize(t,actor,Action.CHUNKS,config);
        if(t.chunks.contains(chunk))return;
        if(next.teams.values().stream().anyMatch(other->other.chunks.contains(chunk)))throw new IllegalArgumentException("Chunk selected by another team");
        if(t.chunks.size()>=config.limits.maxChunksPerTeam || selected(next)>=config.limits.maxChunksGlobal)throw new IllegalArgumentException("Chunk selection cap reached");
        long now=nowMillis(); if(now<t.leaseStart)throw new IllegalArgumentException("Wall clock precedes prepaid term; retry when clock recovers"); int payment=0; BankAccount bank=account(t);
        if(Prices.active(now,t.leaseStart,t.leaseEnd)) {
            int added=Math.max(0,t.chunks.size()+1-t.paidCapacity);
            if(added>0) {
                if(!config.billing.enabled)throw new IllegalArgumentException("Billing is disabled; cannot extend an existing prepaid capacity until billing is enabled");
                if(t.termRate==null)throw new IllegalStateException("Legacy prepaid term lacks rate snapshot; operator recovery required");
                payment=Prices.addition(added,t.memberSnapshot,t.leaseEnd-now,t.termMillis,t.termRate);
            }
            if(bank.getBalance()<payment)throw new IllegalArgumentException("Treasury cannot cover prorated addition");
            t.paidCapacity=Math.max(t.paidCapacity,t.chunks.size()+1);
        } else if(t.leaseEnd!=0) { clearLease(t); }
        t.chunks.add(chunk);
        if(payment>0) { t.lastPayment=payment; t.lastPaymentAt=now; }
        String encoded=StateCodec.encode(next); if(payment>0 && !bank.deduct(payment))throw new IllegalArgumentException("Insufficient treasury funds"); publish(next,encoded);
        reconcile(now);
    }
    public void removeChunk(UUID actor,Chunk chunk) { ready(); TeamState next=copy(); Team t=next.member(actor); TeamState.authorize(t,actor,Action.CHUNKS,config); if(!t.chunks.remove(chunk))throw new IllegalArgumentException("Chunk is not selected by your team"); publish(next); reconcileTickets(nowMillis()); }
    /** Trusted administrator integration API: command wrapper checks operator permission. */
    public void adminRemoveChunk(UUID team,Chunk chunk) { ready(); TeamState next=copy(); if(!next.byId(team).chunks.remove(chunk))throw new IllegalArgumentException("Chunk not selected"); publish(next); reconcileTickets(nowMillis()); }
    public int cost(UUID actor) { ready(); Team t=state.member(actor); return Prices.total(t.chunks.size(),t.members.size(),config.billing); }
    public String status(UUID actor) { ready(); Team t=state.member(actor); return t.name+" ["+t.id+"] balance="+account(t).getBalance()+" "+config.billing.denomination+", selected="+t.chunks.size()+", tickets="+t.chunks.stream().filter(tickets::contains).count()+", bill="+cost(actor)+", leaseEnd="+t.leaseEnd+", membersSnapshot="+t.memberSnapshot+", suspended="+t.suspended+", billing="+config.billing.enabled; }
    public String billingStatus() { thread(); if(failure!=null)return "FAIL CLOSED: "+failure; return "billing="+config.billing.enabled+", teams="+state.teams.size()+", selected="+selected(state)+", tickets="+tickets.size()+", periodSeconds="+config.billing.periodSeconds; }
    public String message(String key) { return config.messages.getOrDefault(key,key); }
    private static void clearLease(Team t) { t.leaseStart=0; t.leaseEnd=0; t.termMillis=0; t.memberSnapshot=0; t.paidCapacity=0; }

    /** Reconcile once from real current time; no catch-up debt. Public for isolated integration drivers. */
    public void reconcile(long now) {
        ready(); if(now<0)throw new IllegalArgumentException("Invalid clock");
        if(!config.billing.enabled) { removeTickets(); return; }
        try {
            validateAccounts(state);
            for(UUID id:new ArrayList<>(state.teams.keySet())) {
                Team current=state.byId(id);
                if(Prices.active(now,current.leaseStart,current.leaseEnd) || now<current.leaseStart)continue; // Clock rollback: no free ticks or duplicate prepayment.
                if(current.chunks.isEmpty() && current.leaseEnd==0 && !current.suspended)continue;
                if(current.suspended && current.leaseEnd==0 && !current.chunks.isEmpty() && account(current).getBalance()<Prices.total(current.chunks.size(),current.members.size(),config.billing))continue;
                TeamState next=copy(); Team t=next.byId(id); clearLease(t);
                if(t.chunks.isEmpty()) { t.suspended=false; if(current.leaseEnd!=0||current.suspended)publish(next); continue; }
                BankAccount bank=account(t); int bill=Prices.total(t.chunks.size(),t.members.size(),config.billing);
                if(bank.getBalance()<bill) {
                    t.suspended=true; if(!current.suspended || current.leaseEnd!=0) { publish(next); notifyMembers(t,message("suspended")); }
                    continue;
                }
                t.termRate=StateCodec.GSON.fromJson(StateCodec.GSON.toJson(config.billing),TeamsConfig.Billing.class); t.leaseStart=now; t.leaseEnd=Prices.nextEnd(now,config.billing.periodSeconds); t.termMillis=t.leaseEnd-now; t.memberSnapshot=t.members.size(); t.paidCapacity=t.chunks.size(); t.lastPayment=bill; t.lastPaymentAt=now; t.suspended=false;
                String encoded=StateCodec.encode(next);
                if(bill>0 && !bank.deduct(bill))throw new IllegalStateException("Native treasury changed during server-thread billing");
                publish(next,encoded); notifyMembers(t,message("renewed"));
            }
            reconcileTickets(now);
        } catch(RuntimeException e) { fail(e); }
    }
    private void notifyMembers(Team team,String message) { for(UUID id:team.members.keySet()) { var p=server.getPlayerList().getPlayer(id); if(p!=null)p.sendSystemMessage(Component.literal(message)); } }
    private ServerLevel level(Chunk c) { return server.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation(c.dimension()))); }
    private void reconcileTickets(long now) {
        Set<Chunk> desired=new TreeSet<>();
        if(failure==null && config.billing.enabled) for(Team t:state.teams.values()) if(Prices.active(now,t.leaseStart,t.leaseEnd))for(Chunk c:t.chunks)if(config.limits.allowedDimensions.contains(c.dimension()) && level(c)!=null)desired.add(c);
        for(Chunk c:new ArrayList<>(tickets)) if(!desired.contains(c)) { ServerLevel world=level(c); if(world!=null)world.getChunkSource().removeRegionTicket(TICKET,new ChunkPos(c.x(),c.z()),2,new ChunkPos(c.x(),c.z())); tickets.remove(c); }
        for(Chunk c:desired) if(!tickets.contains(c)) { if(admittedThisTick>=config.limits.ticketsPerTick)break; admittedThisTick++; level(c).getChunkSource().addRegionTicket(TICKET,new ChunkPos(c.x(),c.z()),2,new ChunkPos(c.x(),c.z())); tickets.add(c); }
    }
    public Set<Chunk> activeTickets() { thread(); return Set.copyOf(tickets); }
    public void startTick() {
        thread(); admittedThisTick=0; if(failure!=null)return;
        reconcileTickets(nowMillis());
        for(Chunk c:tickets) { ServerLevel world=level(c); if(world!=null)world.resetEmptyTime(); }
    }
    public void tick() { thread(); if(displays!=null)displays.tick(); if(failure!=null)return; long now=nowMillis(); if(bazaar!=null){bazaar.rotate();if(ticks%20==0)bazaar.retireChangedCounters();} if(++ticks%config.billing.checkEveryTicks==0)reconcile(now); else reconcileTickets(now); }
    private void removeTickets() { for(Chunk c:new ArrayList<>(tickets)) { ServerLevel world=level(c); if(world!=null)world.getChunkSource().removeRegionTicket(TICKET,new ChunkPos(c.x(),c.z()),2,new ChunkPos(c.x(),c.z())); } tickets.clear(); }
    public void stop() { thread(); if(displays!=null)displays.stop(); removeTickets(); }
    public void refreshPresentation() { ready(); Presentation.refresh(server,state,config); }
    private static void validateConfiguredState(TeamState s,TeamsConfig c) {
        if(s.teams.size()>c.limits.maxTeams || s.teams.values().stream().mapToInt(t->t.chunks.size()).sum()>c.limits.maxChunksGlobal)throw new IllegalArgumentException("Configured limits below existing state");
        for(Team t:s.teams.values()) {
            name(t.name,c); if(t.members.size()>c.limits.maxMembers || t.chunks.size()>c.limits.maxChunksPerTeam || t.invites.size()>c.limits.maxInvitesPerTeam || !c.presentation.badges.containsKey(t.badge) || !c.presentation.colors.contains(t.color) || t.chunks.stream().anyMatch(ch->!c.limits.allowedDimensions.contains(ch.dimension())))throw new IllegalArgumentException("Configuration excludes existing selections/team presentation/members; migrate first");
            Prices.total(t.chunks.size(),t.members.size(),c.billing);
        }
    }
    public void reload(TeamsConfig candidate) {
        ready(); candidate.validate(); validateConfiguredState(state,candidate); if(bazaar!=null)bazaar.validatePool(candidate.bazaar);
        for(Team t:state.teams.values())Presentation.checkKeyAvailable(server,t.id);
        TeamsConfig old=config;
        try { config=StateCodec.GSON.fromJson(StateCodec.GSON.toJson(candidate),TeamsConfig.class); refreshPresentation(); reconcile(nowMillis()); }
        catch(RuntimeException e) { config=old; throw e; }
    }
}
