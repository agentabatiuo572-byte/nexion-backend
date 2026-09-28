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
                && (mapper.schemaTableCount() != 7 || mapper.controlTableCount() != 5
                    || mapper.controlColumnCount() != 5 || mapper.deliveryEvidenceColumnCount() != 4
                    || mapper.schemaUniqueIndexCount() != 4
                    || mapper.schemaPoolColumnCount() != 2 || mapper.schemaRiskColumnCount() != 4
                    || mapper.depositOrderLogIndexCount() != 1
                    || mapper.legacyAddressCount() != 0 || mapper.legacyCreditedEventCount() != 0))
            throw new IllegalStateException("CREGIS_DEPOSIT_SCHEMA_OR_LEGACY_DATA_REQUIRES_REVIEW");
    }
}
