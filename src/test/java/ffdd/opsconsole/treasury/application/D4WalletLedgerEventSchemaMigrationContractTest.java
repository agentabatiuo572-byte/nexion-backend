package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class D4WalletLedgerEventSchemaMigrationContractTest {

    @Test
    void migrationRegistersTheCanonicalWalletLedgerPayloadAsActiveAndAuthoritative() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts", "migrations", "20260727_d4_wallet_ledger_event_schema.sql"));

        assertThat(sql)
                .contains("'wallet.ledger_posted'")
                .contains("'ACTIVE'")
                .contains("is_server_authoritative")
                .contains("'user_id'")
                .contains("'biz_type'")
                .contains("'asset'")
                .contains("'direction'")
                .contains("'amount'")
                .contains("'balance_after'")
                .contains("'biz_no'")
                .contains("'status'")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("INSERT INTO nx_event_schema_revision");
    }
}
