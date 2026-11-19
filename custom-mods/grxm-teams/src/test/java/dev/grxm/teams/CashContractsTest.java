package dev.grxm.teams;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class CashContractsTest {
    private static CashState.Transfer transfer(CashState.Direction direction,int count,int balance) {
        var t=new CashState.Transfer();t.id=UUID.randomUUID();t.player=UUID.randomUUID();t.team=UUID.randomUUID();t.account=UUID.randomUUID();t.direction=direction;t.itemCount=count;t.spurs=CashState.spurs(count);t.balanceBefore=balance;t.beforeInventory="[]";t.afterInventory="[]";t.receipt=CashState.proof(t);return t;
    }
    @Test void wholeSpursAreOneNativeUnitPerItem() {
        assertEquals(1,CashState.spurs(1));assertEquals(200,CashState.spurs(200));assertEquals(Integer.MAX_VALUE,CashState.spurs(Integer.MAX_VALUE));
        for(int bad:new int[]{0,-1,Integer.MIN_VALUE})assertThrows(IllegalArgumentException.class,()->CashState.spurs(bad));
    }
    @Test void legacySchemaMigratesWithoutInventingCashOrDroppingBazaarProofs() {
        TeamState s=new TeamState();var sale=new BazaarState.Sale();sale.id=UUID.randomUUID();sale.player=UUID.randomUUID();sale.team=UUID.randomUUID();sale.account=UUID.randomUUID();sale.generation=1;sale.count=1;sale.unitPrice=3;sale.payout=3;sale.itemId="minecraft:diamond";sale.beforeInventory="[]";sale.afterInventory="[]";sale.receipt="a".repeat(64);sale.committed=true;s.bazaar.journal.put(sale.id,sale);
        var root=JsonParser.parseString(StateCodec.encode(s)).getAsJsonObject();root.addProperty("version",2);root.remove("cash");
        assertEquals(sale.receipt,StateCodec.decode(root.toString()).bazaar.journal.get(sale.id).receipt);
        assertEquals(3,StateCodec.decode(root.toString()).version);assertTrue(StateCodec.decode(root.toString()).cash.journal.isEmpty());
        root.addProperty("version",1);root.remove("bazaar");assertEquals(3,StateCodec.decode(root.toString()).version);
    }
    @Test void schemaThreeRequiresEveryCashProofFieldAndCompleteAggregate() {
        TeamState s=new TeamState();var t=transfer(CashState.Direction.DEPOSIT,1,0);s.cash.journal.put(t.id,t);
        String valid=StateCodec.encode(s);assertEquals(t.receipt,StateCodec.decode(valid).cash.journal.get(t.id).receipt);
        var root=JsonParser.parseString(valid).getAsJsonObject();root.remove("cash");assertThrows(IllegalArgumentException.class,()->StateCodec.decode(root.toString()));
        var original=JsonParser.parseString(valid).getAsJsonObject().getAsJsonObject("cash").getAsJsonObject("journal").getAsJsonObject(t.id.toString());
        for(String field:new ArrayList<>(original.keySet())) {
            var broken=JsonParser.parseString(valid).getAsJsonObject();broken.getAsJsonObject("cash").getAsJsonObject("journal").getAsJsonObject(t.id.toString()).remove(field);
            assertThrows(IllegalArgumentException.class,()->StateCodec.decode(broken.toString()),field);
        }
    }
    @Test void proofBoundsAndTamperingFailClosedAndCommitDoesNotChangeReceipt() {
        TeamState s=new TeamState();var t=transfer(CashState.Direction.WITHDRAW,2,2);s.cash.journal.put(t.id,t);String proof=t.receipt;t.committed=true;assertEquals(proof,CashState.proof(t));assertDoesNotThrow(()->StateCodec.encode(s));
        t.balanceBefore=1;t.receipt=CashState.proof(t);assertThrows(IllegalArgumentException.class,()->StateCodec.encode(s));
        t.balanceBefore=2;t.receipt=CashState.proof(t);t.afterInventory="[{Slot:0b}]";assertThrows(IllegalArgumentException.class,()->StateCodec.encode(s));
        t=transfer(CashState.Direction.DEPOSIT,1,Integer.MAX_VALUE);s.cash.journal.clear();s.cash.journal.put(t.id,t);assertThrows(IllegalArgumentException.class,()->StateCodec.encode(s));
    }
    @Test void inDevelopmentCogProofsCannotBeReinterpretedAsSpurs() {
        TeamState s=new TeamState();var t=transfer(CashState.Direction.DEPOSIT,1,0);s.cash.journal.put(t.id,t);var root=JsonParser.parseString(StateCodec.encode(s)).getAsJsonObject();var tx=root.getAsJsonObject("cash").getAsJsonObject("journal").getAsJsonObject(t.id.toString());tx.remove("itemCount");tx.remove("itemId");tx.remove("unitsPerItem");tx.addProperty("cogCount",1);tx.addProperty("spurs",64);assertThrows(IllegalArgumentException.class,()->StateCodec.decode(root.toString()));
        t.itemId="numismatics:cog";t.unitsPerItem=64;t.receipt=CashState.proof(t);assertThrows(IllegalArgumentException.class,()->StateCodec.encode(s));
    }
    @Test void boundedJournalNeverPrunesAndDuplicatePendingPlayerRejected() {
        TeamState s=new TeamState();var first=transfer(CashState.Direction.DEPOSIT,1,0);s.cash.journal.put(first.id,first);var next=transfer(CashState.Direction.DEPOSIT,1,0);next.player=first.player;next.receipt=CashState.proof(next);s.cash.journal.put(next.id,next);assertThrows(IllegalArgumentException.class,()->StateCodec.encode(s));
        first.committed=true;assertDoesNotThrow(()->StateCodec.encode(s));
        s.cash.journal.clear();for(int i=0;i<=CashState.CAPACITY;i++){var tx=transfer(CashState.Direction.DEPOSIT,1,0);tx.committed=true;s.cash.journal.put(tx.id,tx);}assertThrows(IllegalArgumentException.class,()->StateCodec.encode(s));assertEquals(CashState.CAPACITY+1,s.cash.journal.size());
    }
}
