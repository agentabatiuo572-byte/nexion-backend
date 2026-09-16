package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.finance.application.FinanceSensitiveDataCipher;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class HdPayPayoutReadiness {
    private final HdPayProperties transport;
    private final HdPayPayoutProperties properties;
    private final BankWithdrawalMapper mapper;
    private final FinanceSensitiveDataCipher cipher;
    private final Environment environment;
    public boolean ready() {
        try {
            if (!properties.ready(transport) || !FundsSandboxProfileGuard.isStrictProductionProfile(environment.getActiveProfiles())
                    || mapper.schemaTables() != 4 || mapper.clientIpColumn() != 1) return false;
            cipher.validateConfiguration();
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }
}
