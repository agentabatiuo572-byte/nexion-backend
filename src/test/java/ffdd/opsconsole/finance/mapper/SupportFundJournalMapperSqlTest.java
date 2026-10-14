package ffdd.opsconsole.finance.mapper;

import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupportFundJournalMapperSqlTest {
    private final Configuration configuration = new Configuration();
    SupportFundJournalMapperSqlTest() { configuration.addMapper(SupportFundJournalMapper.class); }
    @Test void everyObservationIsFreshLockingReadAndNeverSkipsOrTruncates() {
        for (String method : new String[]{"lockWallet", "journal", "birthCandidates", "operation", "legs", "parties", "customerOperations"}) {
            var statement = configuration.getMappedStatement(SupportFundJournalMapper.class.getName() + "." + method);
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            assertThat(statement.isUseCache()).isFalse(); assertThat(statement.isFlushCacheRequired()).isTrue();
            assertThat(statement.getBoundSql(Map.of()).getSql()).contains("?").doesNotContain("LIMIT", "SKIP LOCKED", "${");
        }
    }
    @Test void writableWalletKeepsExclusiveLockAndImmutableEvidenceNeedsOnlySelectPrivilege() {
        var wallet = configuration.getMappedStatement(SupportFundJournalMapper.class.getName() + ".lockWallet").getBoundSql(Map.of()).getSql();
        assertThat(wallet).endsWith("FOR UPDATE").doesNotContain("FOR SHARE");
        for (String method : new String[]{"journal", "birthCandidates", "operation", "legs", "parties", "customerOperations"}) {
            var sql = configuration.getMappedStatement(SupportFundJournalMapper.class.getName() + "." + method).getBoundSql(Map.of()).getSql();
            assertThat(sql).as(method).endsWith("FOR SHARE").doesNotContain("FOR UPDATE", "SKIP LOCKED", "NOWAIT");
        }
    }
    @Test void walletAndJournalIncludeFullDoubleCurrencyTupleAndExactRevisionRange() {
        assertThat(SupportFundJournalMapper.WALLET).contains("WHERE user_id=#{customerId}", "ORDER BY id", "fund_incarnation incarnation", "fund_revision revision");
        for (String field : new String[]{"id", "user_id", "usdt_available", "nex_available", "pending_withdraw", "cregis_risk_held", "version", "sandbox", "is_deleted", "created_at"}) {
            assertThat(SupportFundJournalMapper.WALLET).contains(field);
            assertThat(SupportFundJournalMapper.JOURNAL).contains("old_" + field, "new_" + field);
        }
        assertThat(SupportFundJournalMapper.JOURNAL).contains("wallet_incarnation=#{incarnation}", "revision>#{beforeRevision}", "revision<=#{afterRevision}", "ORDER BY revision", "protocol_sha256", "recorded_db_utc");
    }
    @Test void insertsAreAppendOnlyParameterizedAndNeverHideConflict() {
        for (String method : new String[]{"insertOperation", "insertLeg", "insertParty"}) {
            var statement = configuration.getMappedStatement(SupportFundJournalMapper.class.getName() + "." + method);
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.INSERT);
            assertThat(statement.getBoundSql(Map.of()).getSql()).contains("INSERT INTO", "?").doesNotContain("IGNORE", "DUPLICATE", "REPLACE", "${");
        }
        assertThat(SupportFundJournalMapper.OPERATION).contains("source_snapshot", "source_proof", "ordered_events", "receipt_sha256", "protocol_version", "source_environment");
        assertThat(SupportFundJournalMapper.LEGS).contains("WHERE operation_id=#{operationId}", "ORDER BY wallet_incarnation,revision");
        var birth = configuration.getMappedStatement(SupportFundJournalMapper.class.getName() + ".birthCandidates").getBoundSql(Map.of()).getSql();
        assertThat(birth).contains("old_id=? OR new_id=? OR old_user_id=? OR new_user_id=?").doesNotContain("is_deleted=0", "NEW_ZERO", "LIMIT");
        assertThat(SupportFundJournalMapper.PARTIES).contains("WHERE operation_id=#{operationId}", "ORDER BY customer_id");
        var customers = configuration.getMappedStatement(SupportFundJournalMapper.class.getName() + ".customerOperations").getBoundSql(Map.of()).getSql();
        assertThat(customers).contains("FROM nx_support_fund_operation_party WHERE customer_id=? ORDER BY operation_id FOR SHARE");
        assertThat(SupportFundJournalMapper.class.getDeclaredMethods()).hasSize(10);
    }
}
