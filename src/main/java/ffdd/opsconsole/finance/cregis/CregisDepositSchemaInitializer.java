package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CregisDepositSchemaInitializer implements ApplicationRunner {
    private final CregisDepositMapper mapper;
    private final CregisProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getMode() == CregisProperties.Mode.PROVIDER
                && (mapper.schemaTableCount() != 6 || mapper.schemaUniqueIndexCount() != 4
                    || mapper.depositOrderLogIndexCount() != 1))
            throw new IllegalStateException("CREGIS_DEPOSIT_SCHEMA_MISSING: apply "
                    + "scripts/migrations/20260928_cregis_deposit.sql before enabling PROVIDER");
    }
}
