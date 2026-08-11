package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class A2RuntimePolicyTest {
    private final PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
    private final A2RuntimePolicy policy = new A2RuntimePolicy(repository);

    @Test
    void readsAllAuthoritativeA2ValuesWithinTheirContracts() {
        when(repository.findActiveByKey("admin.a2.reason_min_chars")).thenReturn(item("12 字"));
        when(repository.findActiveByKey("admin.a2.retention_months")).thenReturn(item("24 个月"));
        when(repository.findActiveByKey("admin.a2.schema_version")).thenReturn(item("v4"));

        assertThat(policy.reasonMinChars()).isEqualTo(12);
        assertThat(policy.retentionMonths()).isEqualTo(24);
        assertThat(policy.schemaVersion()).isEqualTo("v4");
        assertThat(policy.reasonPolicy().sourceKey()).isEqualTo("admin.a2.reason_min_chars");
    }

    @Test
    void missingOrInvalidValuesFailClosedInsteadOfFallingBack() {
        when(repository.findActiveByKey("admin.a2.reason_min_chars")).thenReturn(Optional.empty());
        when(repository.findActiveByKey("admin.a2.retention_months")).thenReturn(item("12 months"));
        when(repository.findActiveByKey("admin.a2.schema_version")).thenReturn(item("bad version"));

        assertThatThrownBy(policy::reasonMinChars).isInstanceOf(BizException.class)
                .hasMessage("A2_REASON_POLICY_UNAVAILABLE");
        assertThatThrownBy(policy::retentionMonths).isInstanceOf(BizException.class)
                .hasMessage("A2_RETENTION_POLICY_INVALID");
        assertThatThrownBy(policy::schemaVersion).isInstanceOf(BizException.class)
                .hasMessage("A2_SCHEMA_VERSION_INVALID");
    }

    @Test
    void pollutedNumericPoliciesFailClosedInsteadOfDeletingNonDigits() {
        when(repository.findActiveByKey(A2RuntimePolicy.REASON_MIN_KEY)).thenReturn(item("1x3 字"));
        when(repository.findActiveByKey(A2RuntimePolicy.RETENTION_KEY)).thenReturn(item("2x4 个月"));

        assertThatThrownBy(policy::reasonMinChars).isInstanceOf(BizException.class)
                .hasMessage("A2_REASON_POLICY_INVALID");
        assertThatThrownBy(policy::retentionMonths).isInstanceOf(BizException.class)
                .hasMessage("A2_RETENTION_POLICY_INVALID");
    }

    private Optional<PlatformConfigItem> item(String value) {
        LocalDateTime now = LocalDateTime.now();
        return Optional.of(new PlatformConfigItem(1L, "key", value, "STRING", "admin_a2",
                "ADMIN", "test", 1, now, now));
    }
}
