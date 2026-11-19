package dev.grxm.teams;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InvitationCodesTest {
    @Test void collisionRetriesWithoutReassigningExistingReferences() {
        Iterator<String> candidates=List.of("ABCDEF","ABCDEF","ZYXWVU").iterator();
        assertEquals("ZYXWVU",InvitationCodes.allocate(List.of("ABCDEF"),candidates::next));
        assertThrows(IllegalStateException.class,()->InvitationCodes.allocate(List.of("ABCDEF"),()->"ABCDEF"));
    }
    @Test void referencesSurviveRestartAndLegacyTeamsNeedNoDestructiveMigration() {
        TeamState state=new TeamState();
        TeamState.Team t=new TeamState.Team();
        t.id=UUID.randomUUID(); t.account=UUID.randomUUID(); t.name="Workshop"; t.color="white"; t.badge="gear";
        t.members.put(UUID.randomUUID(),TeamState.Role.OWNER); t.invites.put(UUID.randomUUID(),10000L);
        state.teams.put(t.id,t);
        assertNull(StateCodec.decode(StateCodec.encode(state)).byId(t.id).inviteCode);
        t.inviteCode="ABCDEF";
        assertEquals("ABCDEF",StateCodec.decode(StateCodec.encode(state)).byId(t.id).inviteCode);
        t.inviteCode="bad";
        assertThrows(IllegalArgumentException.class,()->StateCodec.encode(state));
    }
    @Test void normalizationDoesNotRepairMalformedInput() {
        assertEquals("ABCDEF",InvitationCodes.normalize("abCdEf"));
        for(String code:List.of(" ABCDEF","ABCDE1","ABCDE","ABCDEFZ"))assertThrows(IllegalArgumentException.class,()->InvitationCodes.normalize(code));
    }
}
