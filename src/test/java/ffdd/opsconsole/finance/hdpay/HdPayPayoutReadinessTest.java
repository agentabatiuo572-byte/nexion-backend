package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.finance.application.FinanceSensitiveDataCipher;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HdPayPayoutReadinessTest {
    @Test void publicTestUsesSharedProviderButStillRequiresStorageEncryptionAndNormalProfile() {
        var transport = mock(HdPayProperties.class); when(transport.ready()).thenReturn(true);
        when(transport.getServerIp()).thenReturn("1.1.1.1");
        var properties = new HdPayPayoutProperties();
        var bank = mock(BankWithdrawalMapper.class); when(bank.schemaTables()).thenReturn(4);
        var cipher = mock(FinanceSensitiveDataCipher.class);
        var env = new MockEnvironment(); env.setActiveProfiles("dev");
        env.setProperty("nexion.deployment.public-test", "true");
        var readiness = new HdPayPayoutReadiness(transport, properties, bank, cipher, env);
        assertFalse(readiness.ready());
        when(bank.clientIpColumn()).thenReturn(1); assertTrue(readiness.ready());
        when(transport.ready()).thenReturn(false); assertFalse(readiness.ready());
        when(transport.ready()).thenReturn(true);
        when(bank.schemaTables()).thenReturn(3); assertFalse(readiness.ready());
        when(bank.schemaTables()).thenReturn(4);
        env.setActiveProfiles("test"); assertFalse(readiness.ready());
        env.setActiveProfiles("dev", "prod"); assertFalse(readiness.ready());
        env.setActiveProfiles("dev"); assertTrue(readiness.ready());
        when(transport.getServerIp()).thenReturn(""); assertFalse(readiness.ready());
        when(transport.getServerIp()).thenReturn("1.1.1.1");
        doThrow(new IllegalStateException("missing key")).when(cipher).validateConfiguration(); assertFalse(readiness.ready());
    }
}
