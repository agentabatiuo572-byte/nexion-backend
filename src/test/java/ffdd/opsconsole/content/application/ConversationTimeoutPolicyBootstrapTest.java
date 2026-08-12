package ffdd.opsconsole.content.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import org.junit.jupiter.api.Test;

class ConversationTimeoutPolicyBootstrapTest {
    @Test
    void isolatedStartupDoesNotCreateOrSeedOfficialTimeoutTables() {
        ConversationTimeoutPolicyMapper mapper = mock(ConversationTimeoutPolicyMapper.class);
        ProductionSupportPathGuard disabled = mock(ProductionSupportPathGuard.class);
        new ConversationTimeoutPolicyBootstrap(mapper, disabled).initialize();
        verify(mapper, never()).ensurePolicyTable();
        verify(mapper, never()).ensureEventTable();
        verify(mapper, never()).insertDefaultPolicy();
    }
}
