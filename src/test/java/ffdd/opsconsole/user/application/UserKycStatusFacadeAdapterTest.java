package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserKycStatusFacadeAdapterTest {

    private final UserOpsRepository userRepository = mock(UserOpsRepository.class);
    private final UserKycStatusFacadeAdapter adapter = new UserKycStatusFacadeAdapter(
            userRepository, mock(AuditLogService.class), mock(EventOutboxService.class));

    @Test
    void reviewCandidatesMapsC4VerifiedAliasAndNormalizesAllowedStatusCase() {
        when(userRepository.search(any(), eq("ACTIVE"), any(), anyInt())).thenReturn(List.of(
                user("U00000052", "vErIfIeD"),
                user("U00000053", "approved"),
                user("U00000054", "Pending"),
                user("U00000055", "none"),
                user("U00000056", "rejected")));

        List<String> statuses = adapter.reviewCandidates(null, 20).stream()
                .map(row -> String.valueOf(row.get("kycStatus")))
                .toList();

        assertThat(statuses).containsExactly("APPROVED", "APPROVED", "PENDING", "NONE", "REJECTED");
    }

    @Test
    void reviewCandidatesFailsClosedWhenC4StatusIsBlank() {
        when(userRepository.search(any(), eq("ACTIVE"), any(), anyInt()))
                .thenReturn(List.of(user("U00000052", " ")));

        assertThatThrownBy(() -> adapter.reviewCandidates(null, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("C4_KYC_STATUS_INVALID");
    }

    @Test
    void reviewCandidatesFailsClosedWhenC4StatusIsUnknown() {
        when(userRepository.search(any(), eq("ACTIVE"), any(), anyInt()))
                .thenReturn(List.of(user("U00000052", "VERIFICATION_STALE")));

        assertThatThrownBy(() -> adapter.reviewCandidates(null, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("C4_KYC_STATUS_INVALID");
    }

    private UserAccountView user(String userNo, String kycStatus) {
        return new UserAccountView(
                52L, userNo, "K5 candidate", "155****9999", "86", "ACTIVE", kycStatus,
                "L1", "V0", false, null, null, 0, "low", 0L, 0L, null, null);
    }
}
