package ffdd.opsconsole.content;

import ffdd.opsconsole.content.application.SupportOwnershipService;
import static org.mockito.Mockito.*;

/** Existing domain tests isolate authorization; dedicated binding tests exercise the real guard. */
public final class SupportTestDependencies {
    private SupportTestDependencies() {}
    public static SupportOwnershipService ownership() {
        var guard=mock(SupportOwnershipService.class);
        when(guard.actorId()).thenReturn(1L);
        when(guard.actorId(any())).thenReturn(1L);
        when(guard.supervisor(any())).thenReturn(true);
        when(guard.canRead(any(),any())).thenReturn(true);
        when(guard.canReadConversation(any(),any())).thenReturn(true);
        return guard;
    }
}
