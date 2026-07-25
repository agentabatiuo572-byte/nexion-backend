package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetRepository;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetCreateRequest;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetDisableRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsJanusRemoteTargetServiceTest {
    private final JanusRemoteTargetRepository repository = mock(JanusRemoteTargetRepository.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final JanusRemoteTargetProperties properties = new JanusRemoteTargetProperties();
    private final JanusRemoteTargetNetworkGuard networkGuard = mock(JanusRemoteTargetNetworkGuard.class);
    private final OpsJanusRemoteTargetService service =
            new OpsJanusRemoteTargetService(
                    repository, idempotency, audit, new ObjectMapper(), properties, networkGuard);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void executeIdempotentAction() {
        properties.setAllowedOrigins(List.of("https://approved.example"));
        when(networkGuard.allows(any())).thenReturn(true);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void rejectsNonHttpsAndPrivateTargetsBeforePersistence() {
        ApiResult<JanusRemoteTargetView> http = service.create("idem-1",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站", "http://example.com/app",
                        "risk-owner", 0, "批准目标变更原因完整", "影响已评估且具备回滚方案"));
        ApiResult<JanusRemoteTargetView> privateIp = service.create("idem-2",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站", "https://127.0.0.1/app",
                        "risk-owner", 0, "批准目标变更原因完整", "影响已评估且具备回滚方案"));
        ApiResult<JanusRemoteTargetView> querySecret = service.create("idem-3",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站",
                        "https://approved.example/app?token=secret", "risk-owner", 0,
                        "批准目标变更原因完整", "影响已评估且具备回滚方案"));

        assertThat(http.getCode()).isEqualTo(422);
        assertThat(privateIp.getCode()).isEqualTo(422);
        assertThat(querySecret.getCode()).isEqualTo(422);
        verify(repository, never()).createVersion(any());
    }

    @Test
    void rejectsAnHttpsOriginNotPresentInTheDeploymentAllowlist() {
        ApiResult<JanusRemoteTargetView> result = service.create("idem-untrusted",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站",
                        "https://untrusted.example/app", "risk-owner", 0,
                        "批准目标变更原因完整", "影响已评估且具备回滚方案"));
        assertThat(result.getCode()).isEqualTo(422);
        verify(repository, never()).createVersion(any());
    }

    @Test
    void createsAnImmutableNextVersionWithCasAndRequiredAudit() {
        JanusRemoteTargetView persisted = target("finance-main", 3, "ACTIVE", 0, 7);
        when(repository.createVersion(any())).thenReturn(persisted);

        ApiResult<JanusRemoteTargetView> result = service.create("idem-create",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站",
                        "https://approved.example/app", "risk-owner", 2,
                        "批准新版本用于灰度切换", "两条策略待迁移且已有回滚方案"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().remoteTargetVersion()).isEqualTo(3);
        verify(repository).createVersion(any());
        verify(audit).recordRequired(any());
    }

    @Test
    void staleDisableFailsClosedWithoutCancellingCommands() {
        when(repository.find("finance-main", 3))
                .thenReturn(Optional.of(target("finance-main", 3, "ACTIVE", 0, 6)));
        when(repository.disableVersion(eq("finance-main"), eq(3), eq(9L), eq(6L), anyString()))
                .thenReturn(null);

        ApiResult<JanusRemoteTargetView> result = service.disable("finance-main", 3, "idem-disable",
                new JanusRemoteTargetDisableRequest(6L, 9L,
                        "目标证书异常需要停用", "未领取命令必须取消且策略待迁移"));

        assertThat(result.getCode()).isEqualTo(409);
        verify(repository, never()).cancelUnclaimedCommands(anyString(), anyInt(), anyLong());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void disablingCancelsUnclaimedCommandsButDoesNotClaimDeviceSuccess() {
        when(repository.find("finance-main", 3))
                .thenReturn(Optional.of(target("finance-main", 3, "ACTIVE", 0, 7)));
        when(repository.disableVersion(eq("finance-main"), eq(3), eq(9L), eq(7L), anyString()))
                .thenReturn(target("finance-main", 3, "DISABLED", 0, 8));
        when(repository.cancelUnclaimedCommands("finance-main", 3, 9L)).thenReturn(4);

        ApiResult<JanusRemoteTargetView> result = service.disable("finance-main", 3, "idem-disable",
                new JanusRemoteTargetDisableRequest(7L, 9L,
                        "目标证书异常需要停用", "未领取命令必须取消且策略待迁移"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("DISABLED");
        assertThat(result.getData().cancelledCommandCount()).isEqualTo(4);
        verify(repository).cancelUnclaimedCommands("finance-main", 3, 9L);
        verify(audit).recordRequired(any());
    }

    @Test
    void sameKeyReplayReturnsBeforeReadingAlreadyDisabledBusinessState() {
        JanusRemoteTargetView replay = target("finance-main", 3, "DISABLED", 0, 8);
        when(idempotency.execute(eq("K6_REMOTE_TARGET_DISABLE"), eq("idem-replay"), anyString(),
                eq(ApiResult.class), any())).thenReturn(ApiResult.ok(replay));

        ApiResult<JanusRemoteTargetView> result = service.disable("finance-main", 3, "idem-replay",
                new JanusRemoteTargetDisableRequest(7L, 9L,
                        "目标证书异常需要停用", "未领取命令必须取消且策略待迁移"));

        assertThat(result.getData().status()).isEqualTo("DISABLED");
        verify(repository, never()).find("finance-main", 3);
    }

    @Test
    void requiredAuditFailureFailsTheMutationInsteadOfReportingSuccess() {
        when(repository.createVersion(any())).thenReturn(target("finance-main", 1, "ACTIVE", 0, 0));
        doThrow(new IllegalStateException("AUDIT_REQUIRED_FAILED")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> service.create("idem-audit-failure",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站",
                        "https://approved.example/app", "risk-owner", 0,
                        "批准新版本用于灰度切换", "两条策略待迁移且已有回滚方案")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUDIT_REQUIRED_FAILED");
    }

    @Test
    void listReturnsPersistedHistoryWithoutInventingSeedRows() {
        when(repository.list()).thenReturn(List.of(target("finance-main", 2, "ACTIVE", 1, 5)));
        ApiResult<List<JanusRemoteTargetView>> result = service.list();
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).strategyCount()).isEqualTo(1);
    }

    @Test
    void dnsResolutionFailureRejectsCreationBeforePersistence() {
        when(networkGuard.allows(any())).thenReturn(false);

        ApiResult<JanusRemoteTargetView> result = service.create("idem-dns",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站",
                        "https://approved.example/app", "risk-owner", 0,
                        "批准新版本用于灰度切换", "影响已确认且具备回滚安排"));

        assertThat(result.getCode()).isEqualTo(422);
        verify(repository, never()).createVersion(any());
    }

    @Test
    void writesAuthenticatedAdminUsernameInsteadOfNumericPrincipal() {
        var authentication = new UsernamePasswordAuthenticationToken("123", "n/a", List.of());
        authentication.setDetails(Map.of("username", "superadmin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(repository.createVersion(any())).thenAnswer(invocation -> {
            var command = (ffdd.opsconsole.janus.domain.JanusRemoteTargetCreateCommand) invocation.getArgument(0);
            assertThat(command.operator()).isEqualTo("superadmin");
            return target("finance-main", 1, "ACTIVE", 0, 0);
        });

        ApiResult<JanusRemoteTargetView> result = service.create("idem-actor",
                new JanusRemoteTargetCreateRequest("finance-main", "财务主站",
                        "https://approved.example/app", "risk-owner", 0,
                        "批准新版本用于灰度切换", "影响已确认且具备回滚安排"));

        assertThat(result.getCode()).isZero();
        SecurityContextHolder.clearContext();
    }

    private JanusRemoteTargetView target(String key, int version, String status, int strategies, long lockVersion) {
        return new JanusRemoteTargetView(9, key, version, status, "财务主站",
                "https://approved.example/app", "https://approved.example", "ADMIN", "risk-owner",
                1L, 2L, "operator", "批准新版本用于灰度切换", "影响已确认",
                lockVersion, strategies, 2, 0);
    }
}
