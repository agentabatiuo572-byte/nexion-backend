package ffdd.opsconsole.finance.hdpay;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Fails provider-mode startup before traffic if the expand migration is incomplete. */
@Component
public class HdPaySchemaReadiness {
    private final HdPayProperties properties;
    private final HdPayOrderMapper mapper;

    public HdPaySchemaReadiness(HdPayProperties properties, HdPayOrderMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostConstruct
    void verify() {
        if (!properties.providerMode()) return;
        if (!properties.ready()) {
            throw new IllegalStateException("HDPAY_CONFIGURATION_INCOMPLETE");
        }
        if (mapper.countRequiredSchemaTables() != 3
                || mapper.countRequiredSchemaColumns() != 14
                || mapper.countRequiredUniqueIndexes() != 6
                || mapper.countSettlementTargetCheck() != 1
                || mapper.countCallbackRecoveryIndex() != 1) {
            throw new IllegalStateException("HDPAY_SCHEMA_NOT_READY");
        }
    }
}
