package ffdd.opsconsole.content.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationSocketTicketsTest {
    @Test void ticketsAreSingleUseAndConcurrentTabsDoNotRevokeEachOther() {
        var access = mock(ConversationSocketAccess.class);
        when(access.authenticate("jwt", "USER")).thenReturn(new UsernamePasswordAuthenticationToken("12", null, List.of()));
        var tickets = new ConversationSocketTickets(access);
        String first = tickets.issue("Bearer jwt", "USER");
        String second = tickets.issue("Bearer jwt", "USER");
        assertNotEquals(first, second);
        assertEquals("USER:12", tickets.consume(first).actor());
        assertThrows(RuntimeException.class, () -> tickets.consume(first));
        assertEquals("jwt", tickets.consume(second).token());
    }
    @Test void failedAuthenticationCannotMintTicket() {
        var access = mock(ConversationSocketAccess.class);
        when(access.authenticate(anyString(), anyString())).thenThrow(new IllegalArgumentException());
        var tickets = new ConversationSocketTickets(access);
        assertThrows(RuntimeException.class, () -> tickets.issue("Bearer invalid", "ADMIN"));
        assertThrows(RuntimeException.class, () -> tickets.consume("unknown"));
        assertThrows(RuntimeException.class, () -> tickets.issue(null, "USER"));
    }
}
