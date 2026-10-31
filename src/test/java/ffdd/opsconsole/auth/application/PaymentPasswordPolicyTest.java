package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class PaymentPasswordPolicyTest {
    @Test void preservesLeadingZeroAndRejectsOnlyTheAgreedWeakClasses() {
        assertThatCode(() -> AppPaymentPasswordService.requireNew("018290","018290")).doesNotThrowAnyException();
        assertThatCode(() -> AppPaymentPasswordService.requireNew("123457","123457")).doesNotThrowAnyException();
        for (String invalid : new String[]{"12345","1234567","１２３４５６","١٢٣٤٥٦","12 456","abcdef"})
            assertThatThrownBy(() -> AppPaymentPasswordService.requireNew(invalid,invalid)).hasMessage("PAYMENT_PASSWORD_INVALID");
        for (String weak : new String[]{"000000","999999","012345","123456","456789","987654","654321","543210"})
            assertThatThrownBy(() -> AppPaymentPasswordService.requireNew(weak,weak)).hasMessage("PAYMENT_PASSWORD_TOO_SIMPLE");
        assertThatThrownBy(() -> AppPaymentPasswordService.requireNew("018290","018291")).hasMessage("PAYMENT_PASSWORD_CONFIRM_MISMATCH");
    }
    @Test void credentialRequestsDoNotExposeSecretsThroughDefaultLogging() {
        assertThat(new AppPaymentPasswordService.Setup("018290","018290").toString()).doesNotContain("018290");
        assertThat(new AppPaymentPasswordService.Change("018290","394827","394827",1L,"attempt123").toString()).doesNotContain("018290","394827");
        assertThat(new ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.Authorization("018290",1L,"attempt123").toString()).doesNotContain("018290");
    }
    @Test void missingPepperOrPersistedPolicyFailsClosedInsteadOfReportingAnUnconfiguredCredential() {
        var repo=mock(ffdd.opsconsole.auth.mapper.PaymentPasswordMapper.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(repo.policy(false)).thenReturn(null);
        var missingPepper=new AppPaymentPasswordService(repo,new com.fasterxml.jackson.databind.ObjectMapper(),java.time.Clock.systemUTC(),
                mock(UserOtpDeliveryService.class),mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),
                mock(ffdd.opsconsole.shared.audit.AuditLogService.class),manager,"","v1",5,900,900);
        assertThatThrownBy(()->missingPepper.state(7L)).hasMessage("PAYMENT_PASSWORD_UNAVAILABLE");
        verifyNoInteractions(repo);
        var missingPolicy=new AppPaymentPasswordService(repo,new com.fasterxml.jackson.databind.ObjectMapper(),java.time.Clock.systemUTC(),
                mock(UserOtpDeliveryService.class),mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),
                mock(ffdd.opsconsole.shared.audit.AuditLogService.class),manager,"isolated-test-pepper-32-bytes-or-longer","v1",5,900,900);
        assertThatThrownBy(()->missingPolicy.state(7L)).hasMessage("PAYMENT_PASSWORD_POLICY_UNAVAILABLE");
        when(repo.policy(false)).thenReturn(java.util.Map.of("max_attempts",0,"window_minutes",15,"lock_minutes",15));
        assertThatThrownBy(()->missingPolicy.state(7L)).hasMessage("PAYMENT_PASSWORD_POLICY_UNAVAILABLE");
    }
}
