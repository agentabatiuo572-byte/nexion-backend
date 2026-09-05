package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.mapper.JanusCommandLeaseMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JanusCommandLeaseServiceTest {
    private final JanusCommandLeaseMapper mapper = mock(JanusCommandLeaseMapper.class);
    private final JanusCommandLeaseService service = new JanusCommandLeaseService(mapper, 90_000L);

    @Test
    void concurrentClaimCannotBorrowTheWinningExecutorLease() {
        long future = System.currentTimeMillis() + 60_000L;
        when(mapper.insert(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(0);
        when(mapper.find("device-1", "cmd-1", 3L)).thenReturn(Map.of(
                "deviceId", "device-1", "commandId", "cmd-1", "commandVersion", 3L,
                "executorId", "executor-1", "leaseToken", "e".repeat(64),
                "fencingToken", 7L, "leaseExpiresAt", future));

        JanusCommandLeaseService.Lease result = service.claim(
                "device-1", "cmd-1", 3L, "executor-1", "a".repeat(32), null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_COMMAND_LEASE_CONFLICT");
        verify(mapper).renew(anyString(), anyString(), anyLong(), anyString(), anyString(),
                anyLong(), anyString(), anyLong());
    }

    @Test
    void exactResumeTokenRenewsTheSameFenceForCrashRecovery() {
        long future = System.currentTimeMillis() + 60_000L;
        Map<String,Object> row = Map.of(
                "deviceId", "device-1", "commandId", "cmd-1", "commandVersion", 3L,
                "executorId", "executor-1", "leaseToken", "e".repeat(64),
                "fencingToken", 7L, "leaseExpiresAt", future);
        when(mapper.insert(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(0);
        when(mapper.find("device-1", "cmd-1", 3L)).thenReturn(row);
        when(mapper.renew(anyString(), anyString(), anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyLong())).thenReturn(1);

        JanusCommandLeaseService.Lease result = service.claim(
                "device-1", "cmd-1", 3L, "executor-1", "a".repeat(32), "e".repeat(64));

        assertThat(result.accepted()).isTrue();
        assertThat(result.fencingToken()).isEqualTo(7L);
        assertThat(result.leaseToken()).isEqualTo("e".repeat(64));
    }

    @Test
    void sameAttestedExecutorRecoversAClaimWhoseResponseWasLostWithoutWaitingForExpiry() {
        long future = System.currentTimeMillis() + 60_000L;
        Map<String,Object> row = Map.of(
                "deviceId", "device-1", "commandId", "cmd-1", "commandVersion", 3L,
                "executorId", "executor-1", "claimNonce", "first-claim-" + "a".repeat(20),
                "leaseToken", "e".repeat(64), "fencingToken", 7L, "leaseExpiresAt", future);
        when(mapper.insert(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(0);
        when(mapper.find("device-1", "cmd-1", 3L)).thenReturn(row);
        when(mapper.renew(anyString(), anyString(), anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyLong())).thenReturn(1);

        JanusCommandLeaseService.Lease result = service.claim(
                "device-1", "cmd-1", 3L, "executor-1", "b".repeat(32), null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.fencingToken()).isEqualTo(7L);
        assertThat(result.leaseToken()).isEqualTo("e".repeat(64));
        verify(mapper).renew(eq("device-1"), eq("cmd-1"), eq(3L), eq("executor-1"),
                eq("e".repeat(64)), eq(7L), eq("b".repeat(32)), anyLong());
    }
}
