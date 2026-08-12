package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.AppLearningQuizResult;
import ffdd.opsconsole.content.domain.LearningSandboxIdempotencyRow;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LearningSandboxQuizIdempotencyServiceTest {
    private final AppLearningMapper mapper = mock(AppLearningMapper.class);
    private final LearningSandboxQuizIdempotencyService service =
            new LearningSandboxQuizIdempotencyService(mapper, new ObjectMapper().findAndRegisterModules());

    @Test
    void firstClaimRunsOnceAndPersistsTheReplayableResponseOnlyInSandboxStorage() {
        when(mapper.claimSandboxQuizIdempotency(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.completeSandboxQuizIdempotency(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        AppLearningQuizResult expected = result();

        AppLearningQuizResult actual = service.execute("run-1", 42L, "course", "v2", "hash-1", "stable-key", () -> expected);

        assertThat(actual).isEqualTo(expected);
        verify(mapper).completeSandboxQuizIdempotency(eq("run-1"), eq(42L), eq("course"), eq("v2"), eq("stable-key"), eq("COMPLETED"),
                contains("\"courseId\":\"course\""));
    }

    @Test
    void sameKeyReturnsStoredResponseWithoutRunningAnotherAttempt() throws Exception {
        when(mapper.claimSandboxQuizIdempotency(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        String json = new ObjectMapper().writeValueAsString(result());
        when(mapper.lockSandboxQuizIdempotency("run-1", 42L, "course", "v2", "stable-key"))
                .thenReturn(new LearningSandboxIdempotencyRow("hash-1", "COMPLETED", json));
        AtomicInteger actionRuns = new AtomicInteger();

        AppLearningQuizResult actual = service.execute("run-1", 42L, "course", "v2", "hash-1", "stable-key",
                () -> { actionRuns.incrementAndGet(); return result(); });

        assertThat(actual).isEqualTo(result());
        assertThat(actionRuns).hasValue(0);
        verify(mapper, never()).completeSandboxQuizIdempotency(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sameKeyWithAnotherPayloadHashFailsClosedWithoutRunningAnotherAttempt() {
        when(mapper.claimSandboxQuizIdempotency(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(mapper.lockSandboxQuizIdempotency("run-1", 42L, "course", "v2", "stable-key"))
                .thenReturn(new LearningSandboxIdempotencyRow("hash-1", "COMPLETED", "{}"));

        assertThatThrownBy(() -> service.execute("run-1", 42L, "course", "v2", "hash-2", "stable-key", () -> result()))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        verify(mapper, never()).completeSandboxQuizIdempotency(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static AppLearningQuizResult result() {
        return new AppLearningQuizResult("course", "v2", 100, true, true, true, new BigDecimal("20.000000"), 1);
    }
}
