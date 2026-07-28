package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsVietnamPaymentControllerSecurityTest {
    @Test
    void endpointsUseTheApprovedLeastPrivilegeAuthorities() throws Exception {
        assertPermission("vietQrOverview", "finance_d1_read", String.class, Integer.class, Integer.class);
        assertPermission("reconcile", "finance_d1_bank_reconcile", Long.class, String.class, String.class,
                ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest.class);
        assertPermission("registerVietQrReceipt", "finance_d1_bank_reconcile", String.class,
                ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest.class);
        assertPermission("createBankAccount", "finance_d1_bank_account_manage", String.class,
                ffdd.opsconsole.finance.dto.VietQrBankAccountCreateRequest.class);
        assertPermission("updateBankAccount", "finance_d1_bank_account_manage", Long.class, String.class,
                ffdd.opsconsole.finance.dto.VietQrBankAccountCommandRequest.class);
        assertPermission("updateVietQrConfig", "finance_d1_bank_config_manage", String.class,
                ffdd.opsconsole.finance.dto.VietQrConfigUpdateRequest.class);
        assertPermission("fxQuote", "finance_d6_read");
        assertPermission("updateFxQuote", "finance_d6_manage", String.class,
                ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest.class);
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes) throws Exception {
        Method method = OpsVietnamPaymentController.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('" + permission + "')");
    }
}
