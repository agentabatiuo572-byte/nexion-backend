package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.ConversationTimeoutPolicy;
import ffdd.opsconsole.content.dto.ConversationTimeoutPolicyUpdateRequest;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ConversationTimeoutPolicyServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 25, 10, 0);

    private final ConversationTimeoutPolicyMapper mapper = mock(ConversationTimeoutPolicyMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ConversationTimeoutPolicyService service = new ConversationTimeoutPolicyService(
            mapper,
            auditLogService,
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneId.of("UTC")));

    @Test
    void currentReturnsDurablePolicy() {
        when(mapper.selectPolicy()).thenReturn(policy(5L, 4, 20));

        var result = service.current();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().warnMinutes()).isEqualTo(4);
        assertThat(result.getData().closeMinutes()).isEqualTo(20);
        assertThat(result.getData().version()).isEqualTo(5L);
    }

    @Test
    void updateRejectsInvalidRangesOrderingAndShortReason() {
        assertThat(service.update(new ConversationTimeoutPolicyUpdateRequest(
                0, 20, 5L, "Marina K.", "有效调整原因")).getCode()).isEqualTo(422);
        assertThat(service.update(new ConversationTimeoutPolicyUpdateRequest(
                10, 10, 5L, "Marina K.", "有效调整原因")).getCode()).isEqualTo(422);
        assertThat(service.update(new ConversationTimeoutPolicyUpdateRequest(
                4, 20, 5L, "Marina K.", "太短")).getCode()).isEqualTo(422);

        verify(mapper, never()).updatePolicy(any(Integer.class), any(Integer.class), any(Long.class),
                any(String.class), any(String.class), any(LocalDateTime.class));
    }

    @Test
    void updateRejectsFractionalMinutesFromRealJsonWithoutTruncating() throws Exception {
        ConversationTimeoutPolicyUpdateRequest request = new ObjectMapper().readValue("""
                {
                  "warnMinutes": 1.5,
                  "closeMinutes": 5,
                  "expectedVersion": 5,
                  "operator": "Marina K.",
                  "reason": "拒绝浮点分钟并保留现有策略"
                }
                """, ConversationTimeoutPolicyUpdateRequest.class);

        var result = service.update(request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("M3_TIMEOUT_MINUTES_INTEGER_REQUIRED");
        verify(mapper, never()).selectPolicyForUpdate();
        verify(mapper, never()).updatePolicy(any(Integer.class), any(Integer.class), any(Long.class),
                any(String.class), any(String.class), any(LocalDateTime.class));
    }

    @Test
    void updateRejectsOutOfIntegerRangeMinutesWithoutThrowing() throws Exception {
        ConversationTimeoutPolicyUpdateRequest request = new ObjectMapper().readValue("""
                {
                  "warnMinutes": 1e100,
                  "closeMinutes": 5,
                  "expectedVersion": 5,
                  "operator": "Marina K.",
                  "reason": "拒绝超出整数范围的分钟值"
                }
                """, ConversationTimeoutPolicyUpdateRequest.class);

        var result = service.update(request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("M3_TIMEOUT_MINUTES_INTEGER_REQUIRED");
        verify(mapper, never()).selectPolicyForUpdate();
    }

    @Test
    void updateUsesCasAndFailsClosedOnStaleVersion() {
        when(mapper.selectPolicyForUpdate()).thenReturn(policy(6L, 4, 20));

        var result = service.update(new ConversationTimeoutPolicyUpdateRequest(
                5, 30, 5L, "Marina K.", "根据当前接待量调整闲置策略"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("M3_TIMEOUT_POLICY_STALE");
        verify(mapper, never()).updatePolicy(any(Integer.class), any(Integer.class), any(Long.class),
                any(String.class), any(String.class), any(LocalDateTime.class));
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void updatePersistsWithCasAndWritesRequiredAudit() {
        when(mapper.selectPolicyForUpdate()).thenReturn(policy(5L, 4, 20));
        when(mapper.updatePolicy(5, 30, 5L, "Marina K.", "根据当前接待量调整闲置策略", NOW)).thenReturn(1);
        when(mapper.selectPolicy()).thenReturn(policy(6L, 5, 30));

        var result = service.update(new ConversationTimeoutPolicyUpdateRequest(
                5, 30, 5L, "Marina K.", "根据当前接待量调整闲置策略"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo(6L);
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
    }

    private ConversationTimeoutPolicy policy(long version, int warn, int close) {
        return new ConversationTimeoutPolicy(
                "GLOBAL",
                warn,
                close,
                version,
                "superadmin",
                "初始化",
                NOW);
    }
}
