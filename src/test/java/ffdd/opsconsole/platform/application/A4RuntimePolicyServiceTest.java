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

class A4RuntimePolicyServiceTest {
    @Test
    void day0AndEventRetentionAreReadFromA4Authority() {
        PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
        when(repository.findActiveByKey("admin.a4.event.kpi.day0")).thenReturn(item("120 秒"));
        when(repository.findActiveByKey("admin.a4.event.kpi.event_retention")).thenReturn(item("18 个月"));
        when(repository.findActiveByKey("admin.a4.event.kpi.sampling"))
                .thenReturn(item("浏览/会话 0% · 资金/风控/转化 100%"));
        A4RuntimePolicyService policy = new A4RuntimePolicyService(repository);

        assertThat(policy.day0Seconds()).isEqualTo(120);
        assertThat(policy.eventRetentionMonths()).isEqualTo(18);
        assertThat(policy.samplingPercent("acquisition", false)).isZero();
        assertThat(policy.samplingPercent("risk", true)).isEqualTo(100);
    }

    @Test
    void samplingReadsTheSameAdminKeyAtBothZeroAndOneHundredPercent() {
        PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
        when(repository.findActiveByKey(A4RuntimePolicyService.SAMPLING_KEY))
                .thenReturn(item("浏览/会话 0% · 资金/风控/转化 100%"),
                        item("浏览/会话 100% · 资金/风控/转化 100%"));
        A4RuntimePolicyService policy = new A4RuntimePolicyService(repository);
        assertThat(policy.samplingPercent("acquisition", false)).isZero();
        assertThat(policy.samplingPercent("acquisition", false)).isEqualTo(100);
    }

    @Test
    void missingDay0FailsClosedSoB3AndL1CannotSilentlyUseNinety() {
        PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
        when(repository.findActiveByKey("admin.a4.event.kpi.day0")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new A4RuntimePolicyService(repository).day0Seconds())
                .isInstanceOf(BizException.class)
                .hasMessage("A4_DAY0_POLICY_UNAVAILABLE");
    }

    @Test
    void samplingRejectsAmbiguousOrNonAuthoritativePolicyText() {
        PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
        when(repository.findActiveByKey(A4RuntimePolicyService.SAMPLING_KEY))
                .thenReturn(item("资金 100% · 浏览 0%"),
                        item("浏览/会话 0% · 资金/风控/转化 99%"));
        A4RuntimePolicyService policy = new A4RuntimePolicyService(repository);

        assertThatThrownBy(() -> policy.samplingPercent("acquisition", false))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SAMPLING_POLICY_INVALID");
        assertThatThrownBy(() -> policy.samplingPercent("acquisition", false))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SAMPLING_POLICY_INVALID");
    }

    @Test
    void pollutedNumericPoliciesFailClosedInsteadOfDeletingNonDigits() {
        PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
        when(repository.findActiveByKey(A4RuntimePolicyService.DAY0_KEY)).thenReturn(item("9x0 秒"));
        when(repository.findActiveByKey(A4RuntimePolicyService.EVENT_RETENTION_KEY)).thenReturn(item("1x8 个月"));
        A4RuntimePolicyService policy = new A4RuntimePolicyService(repository);

        assertThatThrownBy(policy::day0Seconds).isInstanceOf(BizException.class)
                .hasMessage("A4_DAY0_POLICY_INVALID");
        assertThatThrownBy(policy::eventRetentionMonths).isInstanceOf(BizException.class)
                .hasMessage("A4_EVENT_RETENTION_POLICY_INVALID");
    }

    private Optional<PlatformConfigItem> item(String value) {
        LocalDateTime now = LocalDateTime.now();
        return Optional.of(new PlatformConfigItem(1L, "key", value, "STRING", "admin_a4_event",
                "ADMIN", "test", 1, now, now));
    }
}
