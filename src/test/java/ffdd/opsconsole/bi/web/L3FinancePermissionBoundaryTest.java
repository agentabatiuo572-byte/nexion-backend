package ffdd.opsconsole.bi.web;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.treasury.web.OpsTreasuryController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class L3FinancePermissionBoundaryTest {

    @Test
    void l3DedicatedTreasurySnapshotRequiresOnlyTheL3ReadCapability() throws Exception {
        Method method = L3FinanceReportController.class.getMethod("treasurySnapshot");

        assertThat(L3FinanceReportController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/admin/bi/finance");
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/treasury-snapshot");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('bi_l3_read')");
    }

    @Test
    void genericTreasuryReadsRemainInsideTheD3AndB2Boundary() throws Exception {
        assertThat(permission(OpsTreasuryController.class.getMethod("liabilities", boolean.class)))
                .contains("finance_d3_read", "overview_b2_read")
                .doesNotContain("bi_l3_read");
        assertThat(permission(OpsTreasuryController.class.getMethod("maturityForecast", String.class)))
                .contains("finance_d3_read", "overview_b2_read")
                .doesNotContain("bi_l3_read");
    }

    private String permission(Method method) {
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
