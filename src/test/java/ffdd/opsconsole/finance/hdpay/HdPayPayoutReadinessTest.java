package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.finance.application.FinanceSensitiveDataCipher;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HdPayPayoutReadinessTest {
    @Test void sharedConfigurationRequiresActualStorageAndEncryptionAndPreservesDeploymentBoundary() {
        var transport = mock(HdPayProperties.class); when(transport.ready()).thenReturn(true);
        var properties = new HdPayPayoutProperties();
        var bank = mock(BankWithdrawalMapper.class); when(bank.schemaTables()).thenReturn(4);
        var cipher = mock(FinanceSensitiveDataCipher.class);
        var env = new MockEnvironment(); env.setActiveProfiles("dev"); properties.captureEnvironment(env);
        var readiness = new HdPayPayoutReadiness(transport, properties, bank, cipher, env);
        assertFalse(readiness.ready());
        when(bank.clientIpColumn()).thenReturn(1); assertTrue(readiness.ready());
        env.setProperty("nexion.deployment.public-test", "true"); assertFalse(readiness.ready());
        env.setProperty("nexion.deployment.public-test", "false"); assertTrue(readiness.ready());
        doThrow(new IllegalStateException("missing key")).when(cipher).validateConfiguration(); assertFalse(readiness.ready());
    }
}
