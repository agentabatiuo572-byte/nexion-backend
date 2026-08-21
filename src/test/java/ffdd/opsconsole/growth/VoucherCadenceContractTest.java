package ffdd.opsconsole.growth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VoucherCadenceContractTest {
    @Test
    void cadenceMigrationAndServerProjectionExist() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260817_h7_voucher_cadence.sql"));
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/growth/mapper/GrowthVoucherMapper.java"));
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/growth/application/AppGrowthEngagementService.java"));
        assertTrue(migration.contains("popup_delay_ms") && migration.contains("popup_cooldown_hours"));
        assertTrue(migration.contains("nx_voucher_popup_state"));
        assertTrue(mapper.contains("popupMaxPerSession"));
        assertTrue(service.contains("nextEligibleAt") && service.contains("provenance"));
    }
}
