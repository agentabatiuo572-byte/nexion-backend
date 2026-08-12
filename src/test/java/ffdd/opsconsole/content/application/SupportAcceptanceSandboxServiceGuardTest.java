package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportAcceptanceSandboxServiceGuardTest {
    @Test
    void mixedOrDisabledProfileFailsBeforeAnySandboxReadForEveryAdminAndCommandEntry() {
        SupportAcceptanceSandboxProfileGuard guard = mock(SupportAcceptanceSandboxProfileGuard.class);
        SupportAcceptanceSandboxMapper mapper = mock(SupportAcceptanceSandboxMapper.class);
        doThrow(new RuntimeException("SUPPORT_ACCEPTANCE_SANDBOX_PROFILE_FORBIDDEN"))
                .when(guard).requireAvailable();
        SupportAcceptanceSandboxService service = new SupportAcceptanceSandboxService(guard, mapper, Clock.systemUTC(), "run-1");

        assertThatThrownBy(service::adminConversations).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(service::adminTickets).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.adminTicket("ATK-1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.adminReply("ACV-1", "key", Map.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.adminTransfer("ACV-1", "key", Map.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.adminTicketReply("ATK-1", "key", Map.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.adminTicketClose("ATK-1", "key", Map.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.commandResult(7L, "key")).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(mapper);
    }
}
