package dev.grxm.teams;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractsTest {
    private static TeamsConfig config() { return new TeamsConfig(); }
    @Test void weightedWeeklySelectionIsUniqueBoundedAndZeroWeightNeverSelected() {
        var c=config().bazaar;c.pool.get(0).rarityWeight=0;c.pool.get(1).rarityWeight=100;
        Random random=new Random(1);int heavy=0;
        for(int n=0;n<1000;n++) {
            var offers=BazaarState.roll(c,random);assertEquals(3,offers.size());assertEquals(3,offers.stream().map(BazaarState.Offer::itemId).distinct().count());
            assertFalse(offers.stream().anyMatch(o->o.itemId().equals(c.pool.get(0).itemId)));
            if(offers.stream().anyMatch(o->o.itemId().equals(c.pool.get(1).itemId)))heavy++;
            for(var o:offers){assertTrue(o.unitPrice()>=1&&o.unitPrice()<=4);assertTrue(o.quota()>=64&&o.quota()<=128);}
        }
        assertTrue(heavy>950,"Weight changes inclusion likelihood, not retry-based duplicate bias");
        c.pool.get(0).rarityWeight=Double.NaN;assertThrows(IllegalArgumentException.class,c::validate);
    }
    @Test void persistedMarketAndJournalCannotBeSilentlyDefaultedOrTruncated() {
        var s=new TeamState();s.bazaar.generation=3;s.bazaar.start=1000;s.bazaar.end=2000;s.bazaar.offers=BazaarState.roll(config().bazaar,new Random(2));
        String json=StateCodec.encode(s);assertEquals(s.bazaar.offers,StateCodec.decode(json).bazaar.offers);
        var root=com.google.gson.JsonParser.parseString(json).getAsJsonObject();root.getAsJsonObject("bazaar").remove("journal");assertThrows(IllegalArgumentException.class,()->StateCodec.decode(root.toString()));
        var sale=new BazaarState.Sale();s.bazaar.journal.put(UUID.randomUUID(),sale);assertThrows(IllegalArgumentException.class,s::validate);
        assertEquals(2,StateCodec.decode("{\"version\":1,\"teams\":{}}").version);
    }
    @Test void totalIsOneRoundedTeamBillNotPerChunkRounding() {
        var c=config(); c.billing.scalingFactor="0.1"; c.billing.teamMemberScaling="0.3";
        assertEquals(1,Prices.total(7,3,c.billing)); // .63 -> 1, NOT seven rounded charges
        c.billing.scalingFactor="1.25"; c.billing.teamMemberScaling="2.5";
        assertEquals(66,Prices.total(7,3,c.billing)); // 65.625 -> 66
    }
    @Test void proratesExactIncreaseAndRoundsOnlyAtTheEnd() {
        var c=config(); c.billing.scalingFactor="1.01"; c.billing.teamMemberScaling="1";
        assertEquals(2,Prices.addition(3,1,500,1000,c.billing));
        assertEquals(0,Prices.addition(3,1,0,1000,c.billing));
        assertThrows(IllegalArgumentException.class,()->Prices.addition(1,1,1001,1000,c.billing));
    }
    @Test void overflowNegativeScientificAndExcessivePricesAreRejected() {
        var c=config(); c.billing.scalingFactor="999999999999";
        assertThrows(ArithmeticException.class,()->Prices.total(4096,1000,c.billing));
        assertThrows(IllegalArgumentException.class,()->TeamsConfig.decimal("-1"));
        assertThrows(IllegalArgumentException.class,()->TeamsConfig.decimal("1e3"));
        c.billing.scalingFactor="10"; c.billing.maximumBill=19;
        assertThrows(IllegalArgumentException.class,()->Prices.total(2,1,c.billing));
    }
    @Test void wallClockUsesSingleRenewalFromNowAndDoesNotMultiplyDowntime() {
        long original=1700000000000L, now=original+100L*604800000;
        assertFalse(Prices.active(now,original,Prices.nextEnd(original,604800)));
        assertEquals(now+604800000,Prices.nextEnd(now,604800));
        assertFalse(Prices.active(original-1,original,original+1000));
        assertFalse(Prices.active(original+1000,original,original+1000));
        assertThrows(ArithmeticException.class,()->Prices.nextEnd(Long.MAX_VALUE,604800));
    }
    @Test void defaultsDisableEconomyAndInvalidCandidateDoesNotAlterOriginal() {
        TeamsConfig c=config(); c.validate(); assertFalse(c.billing.enabled);
        TeamsConfig candidate=StateCodec.GSON.fromJson(StateCodec.GSON.toJson(c),TeamsConfig.class); candidate.billing.enabled=true;
        assertThrows(IllegalArgumentException.class,candidate::validate); assertFalse(c.billing.enabled);
        candidate.billing.scalingFactor="1"; candidate.validate();
        candidate.resourcePack.sha1="bad"; assertThrows(IllegalArgumentException.class,candidate::validate);
    }
    @Test void configurationRejectsAbusiveLimitsAndPresentation() {
        var c=config(); c.limits.maxChunksGlobal=4097; assertThrows(IllegalArgumentException.class,c::validate);
        c=config(); c.presentation.prefixBefore="\u00a7k"; TeamsConfig bad=c; assertThrows(IllegalArgumentException.class,bad::validate);
        assertThrows(IllegalArgumentException.class,()->TeamsConfig.plain("x\u202ey",24));
    }
    private static TeamState.Team team(UUID owner) {
        TeamState.Team t=new TeamState.Team(); t.id=UUID.randomUUID(); t.account=UUID.randomUUID(); t.name="Workshop"; t.color="gold"; t.badge="gear"; t.members.put(owner,TeamState.Role.OWNER); return t;
    }
    @Test void authorizationEnforcesMembershipAndRoleOnTreasuryAndAdmin() {
        UUID owner=UUID.randomUUID(), officer=UUID.randomUUID(), member=UUID.randomUUID(), outsider=UUID.randomUUID();
        var t=team(owner); t.members.put(officer,TeamState.Role.OFFICER); t.members.put(member,TeamState.Role.MEMBER); var c=config();
        TeamState.authorize(t,owner,TeamState.Action.WITHDRAW,c);
        TeamState.authorize(t,officer,TeamState.Action.CHUNKS,c);
        assertThrows(IllegalArgumentException.class,()->TeamState.authorize(t,officer,TeamState.Action.WITHDRAW,c));
        assertThrows(IllegalArgumentException.class,()->TeamState.authorize(t,officer,TeamState.Action.ADMINISTER,c));
        assertThrows(IllegalArgumentException.class,()->TeamState.authorize(t,member,TeamState.Action.CHUNKS,c));
        assertThrows(IllegalArgumentException.class,()->TeamState.authorize(t,outsider,TeamState.Action.INVITE,c));
        c.permissions.officersWithdraw=true; TeamState.authorize(t,officer,TeamState.Action.WITHDRAW,c);
    }
    @Test void persistenceRoundTripsReceiptSnapshotAndDimensionAwareOwnership() {
        UUID owner=UUID.randomUUID(); var s=new TeamState(); var t=team(owner); s.teams.put(t.id,t);
        t.chunks.add(new TeamState.Chunk("minecraft:overworld",10,-10)); t.chunks.add(new TeamState.Chunk("minecraft:the_nether",10,-10));
        t.leaseStart=1000; t.leaseEnd=2000; t.termMillis=1000; t.memberSnapshot=1; t.paidCapacity=2; t.lastPayment=20; t.lastPaymentAt=1000;
        t.invites.put(UUID.randomUUID(),9999L); String json=StateCodec.encode(s); var round=StateCodec.decode(json).byId(t.id);
        assertEquals(t.account,round.account); assertEquals(2,round.chunks.size()); assertEquals(2000,round.leaseEnd); assertEquals(20,round.lastPayment); assertEquals(t.members,round.members); assertEquals(t.invites,round.invites);
        round.members.clear(); assertEquals(1,t.members.size());
        assertEquals(StateCodec.digest(json),StateCodec.digest(json)); assertNotEquals(StateCodec.digest(json),StateCodec.digest(json+" "));
    }
    @Test void persistenceRejectsDuplicateMembershipOwnershipAndMissingTreasuryIdentity() {
        UUID owner=UUID.randomUUID(); var s=new TeamState(); var a=team(owner); var b=team(owner); b.name="Other"; s.teams.put(a.id,a); s.teams.put(b.id,b);
        assertThrows(IllegalArgumentException.class,s::validate); b.members.clear(); b.members.put(UUID.randomUUID(),TeamState.Role.OWNER);
        var chunk=new TeamState.Chunk("minecraft:overworld",1,1); a.chunks.add(chunk); b.chunks.add(chunk); assertThrows(IllegalArgumentException.class,s::validate);
        b.chunks.clear(); s.validate(); a.account=null; assertThrows(IllegalArgumentException.class,s::validate);
    }
    @Test void persistenceFailsClosedForUnknownSchemaMissingRootAndInvalidReceipt() {
        assertThrows(IllegalArgumentException.class,()->StateCodec.decode("{}"));
        assertThrows(IllegalArgumentException.class,()->StateCodec.decode("{\"version\":2,\"teams\":{}}"));
        var s=new TeamState(); var t=team(UUID.randomUUID()); s.teams.put(t.id,t); t.chunks.add(new TeamState.Chunk("minecraft:overworld",0,0));
        t.leaseStart=1000; t.leaseEnd=2000; t.termMillis=1000; t.memberSnapshot=1; t.paidCapacity=0;
        assertThrows(IllegalArgumentException.class,s::validate);
        assertThrows(IllegalArgumentException.class,()->new TeamState.Chunk("BAD",0,0));
        assertThrows(IllegalArgumentException.class,()->new TeamState.Chunk("minecraft:overworld",Integer.MIN_VALUE,0));
    }
}
