package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeRequest;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsUserServiceTest {
    private final FakeUserOpsRepository userRepository = new FakeUserOpsRepository();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsUserService service = new OpsUserService(userRepository, coverageFacade, auditLogService);

    @Test
    void overviewKeepsSunsetCapabilitiesHistoricalOnly() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("sunsetCompatibility").toString())
                .contains("Premium history")
                .contains("NEX v2 maturity")
                .contains("Points adjustments are rejected");
    }

    @Test
    void statusChangeRequiresIdempotencyKey() {
        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                null,
                new UserStatusUpdateRequest("FROZEN", "risk hold", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void statusChangeWritesAudit() {
        ApiResult<UserAccountView> result = service.updateStatus(
                1L,
                "idem-c1",
                new UserStatusUpdateRequest("FROZEN", "risk hold", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("FROZEN");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("C1_USER_STATUS_CHANGED");
    }

    @Test
    void creditAssetAdjustmentBelowB1RedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.createAssetAdjustment(
                1L,
                "idem-c4",
                new UserAssetAdjustmentRequest("NEX", "CREDIT", "10", "manual compensation", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void revokingAlreadyRevokedSessionReturns409() {
        userRepository.sessions.put("rt-revoked", new UserSessionView(
                1L, "rt-revoked", "ios", "10.0.0.*", "REVOKED", LocalDateTime.now(), LocalDateTime.now().plusDays(1), LocalDateTime.now()));

        ApiResult<UserSessionView> result = service.revokeSession(
                "rt-revoked",
                "idem-c3",
                new UserSessionRevokeRequest("security cleanup", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    private static final class FakeUserOpsRepository implements UserOpsRepository {
        private UserAccountView user = new UserAccountView(
                1L,
                "U00000001",
                "Alice",
                "138****8000",
                "86",
                "ACTIVE",
                "PENDING",
                "L1",
                "V1",
                true,
                new BigDecimal("10"),
                new BigDecimal("20"),
                LocalDateTime.now().minusDays(10),
                LocalDateTime.now());
        private final Map<String, UserSessionView> sessions = new LinkedHashMap<>();
        private final List<String> adjustments = new ArrayList<>();

        @Override
        public Map<String, Object> overview() {
            return new LinkedHashMap<>(Map.of("totalUsers", 1L, "activeUsers", 1L));
        }

        @Override
        public List<UserAccountView> search(String keyword, String status, String kycStatus, int limit) {
            return List.of(user);
        }

        @Override
        public Optional<UserAccountView> findById(Long userId) {
            return userId != null && userId.equals(user.id()) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public List<UserSessionView> sessions(Long userId, int limit) {
            return List.copyOf(sessions.values());
        }

        @Override
        public void updateUserStatus(Long userId, String status, String reason) {
            user = new UserAccountView(
                    user.id(), user.userNo(), user.nickname(), user.phoneMasked(), user.countryCode(), status, user.kycStatus(),
                    user.userLevel(), user.vRank(), user.twoFactorEnabled(), user.walletUsdt(), user.walletNex(), user.registeredAt(), user.lastLoginAt());
        }

        @Override
        public Optional<UserSessionView> findSession(String refreshTokenId) {
            return Optional.ofNullable(sessions.get(refreshTokenId));
        }

        @Override
        public void revokeSession(String refreshTokenId, String reason) {
            UserSessionView session = sessions.get(refreshTokenId);
            sessions.put(refreshTokenId, new UserSessionView(
                    session.userId(), session.refreshTokenId(), session.deviceName(), session.clientIpMasked(), "REVOKED",
                    session.issuedAt(), session.expiresAt(), LocalDateTime.now()));
        }

        @Override
        public void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt) {
        }

        @Override
        public void createAssetAdjustment(String adjustmentNo, Long userId, String asset, String direction, BigDecimal amount, String reason, String operator) {
            adjustments.add(adjustmentNo);
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }
}
