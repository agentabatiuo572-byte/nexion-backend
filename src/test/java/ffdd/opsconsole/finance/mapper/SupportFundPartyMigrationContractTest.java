package ffdd.opsconsole.finance.mapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure SQL shape contracts; actual DDL/metadata/privileges still require isolated MySQL execution. */
class SupportFundPartyMigrationContractTest {
    private static String sql() throws Exception {
        return Files.readString(Path.of("scripts/migrations/20261011_support_fund_operation_party.sql"));
    }

    @Test void partiesPersistSortedUniquePositiveCustomerScopeAndEnumerateWithoutCashLegs() throws Exception {
        String source = sql();
        assertThat(source).contains("operation_id BIGINT NOT NULL", "customer_id BIGINT NOT NULL",
            "PRIMARY KEY(operation_id,customer_id)", "KEY idx_support_fund_party_customer(customer_id,operation_id)",
            "CHECK(customer_id>0)", "zero-event/zero-cash receipts", "locked writer scope");
        assertThat(Pattern.compile("CREATE TABLE IF NOT EXISTS ").matcher(source).results().count()).isEqualTo(1);
        assertThat(source).contains("COLUMN_TYPE='bigint'", "IS_NULLABLE='NO'", "COLUMN_DEFAULT IS NULL",
            "EXTRA=''", "COALESCE(GENERATION_EXPRESSION,'')=''", "TABLE_TYPE='BASE TABLE'", "ENGINE='InnoDB'");
        assertThat(source.replaceAll("\\s+", "")).contains("ANDTABLE_NAME='nx_support_fund_operation_party')=2");
    }

    @Test void replayRefusesExtraConstraintsAndIndexesAndRequiresExactVisibleBtreeOrder() throws Exception {
        String source = sql();
        assertThat(source).contains("information_schema.TABLE_CONSTRAINTS", "CONSTRAINT_TYPE='PRIMARY KEY'",
            "CONSTRAINT_TYPE='CHECK'", "tc.ENFORCED='YES'", "CONSTRAINT_TYPE='FOREIGN KEY'",
            "INDEX_TYPE='BTREE'", "IS_VISIBLE='YES'", "SUB_PART IS NULL", "EXPRESSION IS NULL", "COLLATION='A'",
            "INDEX_NAME='PRIMARY' AND NON_UNIQUE=0", "INDEX_NAME='idx_support_fund_party_customer' AND NON_UNIQUE=1",
            "SEQ_IN_INDEX=1 AND COLUMN_NAME='operation_id'", "SEQ_IN_INDEX=2 AND COLUMN_NAME='customer_id'",
            "SEQ_IN_INDEX=1 AND COLUMN_NAME='customer_id'", "SEQ_IN_INDEX=2 AND COLUMN_NAME='operation_id'");
        assertThat(source.replaceAll("\\s+", "")).contains("ANDTABLE_NAME='nx_support_fund_operation_party')=3",
            "ANDTABLE_NAME='nx_support_fund_operation_party')=4");
    }

    @Test void onlyOperationRestrictForeignKeyPreservesParticipationAcrossWalletChanges() throws Exception {
        String source = sql();
        assertThat(source).contains("FOREIGN KEY(operation_id) REFERENCES nx_support_fund_operation(id) ON UPDATE RESTRICT ON DELETE RESTRICT",
            "REFERENCED_TABLE_NAME IS NOT NULL)=1", "k.ORDINAL_POSITION=1", "k.POSITION_IN_UNIQUE_CONSTRAINT=1",
            "k.REFERENCED_TABLE_SCHEMA=DATABASE()", "k.REFERENCED_COLUMN_NAME='id'", "r.UNIQUE_CONSTRAINT_NAME='PRIMARY'",
            "r.MATCH_OPTION='NONE'", "r.UPDATE_RULE='RESTRICT'", "r.DELETE_RULE='RESTRICT'");
        assertThat(source.toUpperCase(Locale.ROOT)).doesNotContain("CASCADE", "REFERENCES NX_USER_WALLET", "REFERENCES NX_USER(");
        assertThat(Pattern.compile(" FOREIGN KEY\\(").matcher(source).results().count()).isEqualTo(1);
    }

    @Test void exactTwoTriggersRejectEveryUpdateAndDeleteAndRetainLiteralBytes() throws Exception {
        String source = sql();
        for (String event : new String[]{"UPDATE", "DELETE"}) {
            String suffix = event.equals("UPDATE") ? "bu" : "bd";
            assertThat(source).contains("CREATE TRIGGER IF NOT EXISTS nx_support_fund_party_" + suffix + "_v1",
                "BEFORE " + event + " ON nx_support_fund_operation_party FOR EACH ROW",
                "TRIGGER_NAME='nx_support_fund_party_" + suffix + "_v1' AND EVENT_MANIPULATION='" + event + "'");
        }
        assertThat(source).contains("ACTION_TIMING='BEFORE'", "ACTION_ORIENTATION='ROW'", "ACTION_ORDER=1",
            "AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=2",
            "BINARY ACTION_STATEMENT=BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE'''");
        assertThat(source).doesNotContain("LOWER(", "REGEXP_REPLACE(", "GRANT ", "REVOKE ");
    }

    private static String checkPattern(String source) {
        var matcher = Pattern.compile("REGEXP_LIKE\\(cc.CHECK_CLAUSE,'([^']*)','c'\\)").matcher(source);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1).replace("[[:space:]()]", "[\\s()]").replace("[[:space:]]", "\\s");
    }

    @Test void positiveCustomerGuardRejectsChangedOperatorsLiteralsAndAdditionalPredicates() throws Exception {
        Pattern guard = Pattern.compile(checkPattern(sql()));
        for (String valid : new String[]{"customer_id>0", " ((`customer_id` > 0)) "})
            assertThat(guard.matcher(valid).matches()).as(valid).isTrue();
        for (String bad : new String[]{"customer_id>=0", "customer_id>1", "customer_id>0 or true", "other_id>0",
                "customer_id>'0'", "customer_id>0.0", "CUSTOMER_ID>0"})
            assertThat(guard.matcher(bad).matches()).as(bad).isFalse();
    }

    private static void balanced(String source) {
        int checks = 0, start = 0;
        while ((start = source.indexOf("SET @fund_party_sql=IF(", start)) >= 0) {
            int depth = 0; boolean quote = false, ended = false;
            for (int i = start; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch == '\'') {
                    if (quote && i + 1 < source.length() && source.charAt(i + 1) == '\'') i++;
                    else quote = !quote;
                } else if (!quote) {
                    if (ch == '(') depth++;
                    if (ch == ')') depth--;
                    assertThat(depth).as("guard offset %s", start).isNotNegative();
                    if (ch == ';') {
                        assertThat(depth).isZero(); start = i + 1; ended = true; break;
                    }
                }
            }
            assertThat(ended).isTrue(); checks++;
        }
        assertThat(checks).isEqualTo(6);
    }

    @Test void allDynamicGuardsBalanceAndMalformedMutationFails() throws Exception {
        String source = sql(); balanced(source);
        assertThatThrownBy(() -> balanced(source.replace("=4,'SELECT 1'", ")=4,'SELECT 1'"))).isInstanceOf(AssertionError.class);
    }

    @Test void migrationIsFailClosedRepeatableAndNeverBackfillsOrRepairsHistory() throws Exception {
        String source = sql();
        for (String guard : new String[]{"LOCK", "COLUMNS", "CONSTRAINTS", "INDEXES", "FOREIGN_KEY", "TRIGGERS"})
            assertThat(source).contains("FAIL SUPPORT_FUND_PARTY_" + guard);
        assertThat(source).contains("GET_LOCK(", "RELEASE_LOCK(", "No historical backfill", "SELECT/INSERT only", "WITHOUT --force");
        assertThat(source.toUpperCase(Locale.ROOT)).doesNotContain("DROP ", "ALTER TABLE", "INSERT INTO", "UPDATE NX_",
            "DELETE FROM", "CREATE OR REPLACE", "INSERT IGNORE", "DUPLICATE KEY", "START TRANSACTION", "COMMIT;");
    }
}
