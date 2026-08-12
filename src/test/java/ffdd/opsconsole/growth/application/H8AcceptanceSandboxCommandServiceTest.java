package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class H8AcceptanceSandboxCommandServiceTest {
    private final ReferralRewardMapper mapper = mock(ReferralRewardMapper.class);
    private final H8AcceptanceSandboxCommandService service =
            new H8AcceptanceSandboxCommandService(mapper, new ObjectMapper());

    @Test
    void replaysTheSameRunScopedTerminalResultWithoutExecutingAgain() {
        when(mapper.findSandboxCommand("RUN-H8-1", "key-1")).thenReturn(
                new ReferralRewardMapper.H8SandboxCommandRow("hash-1", "SUCCEEDED", "{\"settled\":1,\"runId\":\"RUN-H8-1\"}"));

        Map<String, Object> result = service.execute("RUN-H8-1", "key-1", "hash-1", () -> {
            throw new AssertionError("replay must not execute settlement");
        });

        assertThat(result).containsEntry("settled", 1).containsEntry("runId", "RUN-H8-1");
        verify(mapper, never()).insertSandboxCommand("RUN-H8-1", "key-1", "hash-1");
    }

    @Test
    void rejectsTheSameKeyWhenTheRunScopedPayloadHashChanges() {
        when(mapper.findSandboxCommand("RUN-H8-1", "key-1")).thenReturn(
                new ReferralRewardMapper.H8SandboxCommandRow("hash-1", "SUCCEEDED", "{}"));

        assertThatThrownBy(() -> service.execute("RUN-H8-1", "key-1", "hash-2", Map::of))
                .hasMessageContaining("H8_SANDBOX_IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
    }

    @Test
    void storesNewSuccessfulTerminalResultInDedicatedRunScopedFacts() {
        when(mapper.insertSandboxCommand("RUN-H8-1", "key-1", "hash-1")).thenReturn(1);
        when(mapper.completeSandboxCommand(org.mockito.ArgumentMatchers.eq("RUN-H8-1"),
                org.mockito.ArgumentMatchers.eq("key-1"), org.mockito.ArgumentMatchers.contains("settled"))).thenReturn(1);

        Map<String, Object> result = service.execute("RUN-H8-1", "key-1", "hash-1",
                () -> Map.of("settled", 1, "runId", "RUN-H8-1"));

        assertThat(result).containsEntry("settled", 1);
        verify(mapper).completeSandboxCommand(org.mockito.ArgumentMatchers.eq("RUN-H8-1"),
                org.mockito.ArgumentMatchers.eq("key-1"), org.mockito.ArgumentMatchers.contains("RUN-H8-1"));
    }
}
