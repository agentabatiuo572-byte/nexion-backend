package ffdd.opsconsole.treasury.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsTreasuryControllerB1Test {

    @Test
    void b1ReadCanReachItsDashboardAndCanonicalNetExposureOnly() {
        assertThat(authority("bDomainDashboard"))
                .contains("overview_b1_read")
                .doesNotContain("overview_b1_write");
        assertThat(authority("netExposure"))
                .contains("overview_b1_read", "finance_d3_read")
                .doesNotContain("overview_b1_write");
    }

    @Test
    void b1WritesKeepDedicatedLeastPrivilegeAuthorities() {
        assertThat(authority("acknowledgeBDomainAlert"))
                .contains("overview_b1_write")
                .doesNotContain("overview_b1_read");
        assertThat(authority("updateThresholds"))
                .contains("overview_b1_redline_write", "overview_b1_runrisk_write")
                .doesNotContain("overview_b1_read");
        assertThat(authority("createInjection"))
                .contains("finance_d3_injection_create")
                .doesNotContain("overview_b1_write");
        assertThat(authority("reconciliationExport"))
                .contains("finance_d3_export")
                .doesNotContain("overview_b1_write");
    }

    private static String authority(String methodName) {
        Method method = java.util.Arrays.stream(OpsTreasuryController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
