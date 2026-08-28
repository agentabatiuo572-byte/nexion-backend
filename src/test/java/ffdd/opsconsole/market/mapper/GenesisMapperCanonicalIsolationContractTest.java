package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GenesisMapperCanonicalIsolationContractTest {

    @Test
    void g4HoldingAndSecondaryAggregatesExcludeSandboxMarkedUsers() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/market/mapper/GenesisMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains(
                "JOIN nx_user u ON u.id = h.user_id",
                "JOIN nx_user order_user ON order_user.id = o.user_id",
                "JOIN nx_user ledger_user ON ledger_user.id = ledger.user_id",
                "COALESCE(u.sandbox, 0) = 0",
                "COALESCE(order_user.sandbox, 0) = 0",
                "COALESCE(ledger_user.sandbox, 0) = 0");
        assertThat(mapper).doesNotContain("LEFT JOIN nx_user u ON u.id = h.user_id");
    }
}
