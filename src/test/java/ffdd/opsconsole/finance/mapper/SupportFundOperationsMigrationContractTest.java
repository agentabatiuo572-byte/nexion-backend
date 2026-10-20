package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** SQL contracts only; no database, transaction, writer, or provenance is certified by these tests. */
class SupportFundOperationsMigrationContractTest {
    private static String sql() throws Exception {
        return Files.readString(Path.of("scripts/migrations/20261011_support_fund_operations.sql")).replace("\r\n", "\n");
    }
    private static String compact(String source) { return source.replaceAll("\\s+", ""); }
    private static String table(String source, String name) {
        var match = Pattern.compile("CREATE TABLE IF NOT EXISTS " + name
                + " \\(\\n(.*?)\\n\\) ENGINE=InnoDB;", Pattern.DOTALL).matcher(source);
        assertThat(match.find()).as("%s exists", name).isTrue();
        return match.group(1);
    }

    @Test void stableReceiptCapturesFormalSourceOrderedEventsAndDatabaseUtcWithoutReadyFlag() throws Exception {
        String source = sql(), operation = table(source, "nx_support_fund_operation");
        assertThat(operation).contains("op_key VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL",
                "UNIQUE KEY uk_support_fund_operation_key(op_key)", "business_type VARCHAR(64)",
                "phase VARCHAR(32)", "source_table VARCHAR(64)", "source_key VARCHAR(192)",
                "source_environment VARCHAR(16)", "ordered_events JSON NOT NULL",
                "source_snapshot JSON NOT NULL", "source_proof JSON NOT NULL",
                "receipt_sha256 CHAR(64)", "protocol_version VARCHAR(64)",
                "recorded_db_utc DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6))");
        assertThat(source).contains("source_environment='PRODUCTION'", "json_type(ordered_events)='ARRAY'",
                "json_type(source_snapshot)='OBJECT'", "json_type(source_proof)='OBJECT'",
                "json_length(source_snapshot)>0", "json_length(source_proof)>0");
        assertThat(operation.toUpperCase(Locale.ROOT)).doesNotContain("READY", "CONFIG", "STATUS");
        // A successful business state may change no cash; service validates its event semantics.
        assertThat(source).doesNotContain("json_length(ordered_events)>0");
    }

    @Test void everyRevisionCanBindOnlyOneReceiptButAReceiptCanBindMultipleWalletsAndRevisions() throws Exception {
        String leg = compact(table(sql(), "nx_support_fund_operation_leg"));
        assertThat(leg).contains("operation_idBIGINTNOTNULL", "wallet_incarnationCHAR(36)",
                "revisionBIGINTNOTNULL", "PRIMARYKEY(wallet_incarnation,revision)",
                "KEYidx_support_fund_operation_leg_operation(operation_id)",
                "CHECK(revision>=0)");
        assertThat(leg).doesNotContain("UNIQUEKEY", "last_revision", "lastRevision",
                "PRIMARYKEY(operation_id", "UNIQUE(operation_id");
        assertThat(sql()).contains("ALL affected journal revisions", "may have no journal legs");
    }

    @Test void onlyOperationForeignKeyIsRestrictAndHistorySurvivesWalletDeletion() throws Exception {
        String source = sql(), operation = table(source, "nx_support_fund_operation");
        String leg = table(source, "nx_support_fund_operation_leg");
        assertThat(operation).doesNotContain("FOREIGN KEY");
        assertThat(leg).contains("FOREIGN KEY(operation_id) REFERENCES nx_support_fund_operation(id)",
                "ON UPDATE RESTRICT ON DELETE RESTRICT");
        assertThat(source).contains("r.UPDATE_RULE='RESTRICT'", "r.DELETE_RULE='RESTRICT'",
                "k.REFERENCED_TABLE_SCHEMA=DATABASE()", "k.REFERENCED_COLUMN_NAME='id'");
        assertThat(source.toUpperCase(Locale.ROOT)).doesNotContain("REFERENCES NX_USER_WALLET",
                "REFERENCES NX_SUPPORT_WALLET_JOURNAL", "CASCADE");
        assertThat(source).contains("no journal/wallet FK", "Missing source, birth, leg, or continuity is UNKNOWN");
    }

    @Test void receiptsAndJsonCannotBecomeMutableConfiguration() throws Exception {
        String source = sql();
        for (String suffix : new String[] {"operation", "leg"}) {
            String target = suffix.equals("operation") ? "nx_support_fund_operation" : "nx_support_fund_operation_leg";
            for (String event : new String[] {"UPDATE", "DELETE"}) {
                String key = event.equals("UPDATE") ? "bu" : "bd";
                assertThat(source).contains("CREATE TRIGGER IF NOT EXISTS nx_support_fund_" + suffix + "_" + key + "_v1",
                        "BEFORE " + event + " ON " + target + " FOR EACH ROW",
                        "FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_" + suffix.toUpperCase(Locale.ROOT)
                                + "_" + key.toUpperCase(Locale.ROOT) + "_V1_SHAPE");
            }
        }
        assertThat(source).contains("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE'",
                "EVENT_MANIPULATION=", "ACTION_STATEMENT", "SELECT/INSERT only", "CURRENT_USER()");
        assertThat(source).doesNotContain("GRANT ", "REVOKE ");
    }

    @Test void replayChecksExactColumnsUniqueKeyCheckForeignKeyAndTriggerInventory() throws Exception {
        String source = sql();
        assertThat(source).contains("TABLE_TYPE='BASE TABLE'", "ENGINE='InnoDB'", "GENERATION_EXPRESSION",
                "IS_NULLABLE='NO'", "COLUMN_DEFAULT IS NULL", "EXTRA='DEFAULT_GENERATED'",
                "COLLATION_NAME='utf8mb4_bin'", "COLLATION_NAME='ascii_bin'",
                "tc.ENFORCED='YES'", "cc.CHECK_CLAUSE", "SUB_PART IS NULL", "IS_VISIBLE='YES'",
                "REFERENCED_TABLE_NAME IS NOT NULL", "GET_LOCK(", "RELEASE_LOCK(",
                "FAIL SUPPORT_FUND_OPERATIONS_UNIQUE_SHAPE",
                "FAIL SUPPORT_FUND_OPERATIONS_FOREIGN_KEY_SHAPE",
                "FAIL SUPPORT_FUND_OPERATIONS_TRIGGER_INVENTORY");
        assertThat(compact(source)).contains("ANDTABLE_NAME='nx_support_fund_operation')=13",
                "ANDTABLE_NAME='nx_support_fund_operation_leg')=3",
                "ANDEVENT_OBJECT_TABLEIN('nx_support_fund_operation','nx_support_fund_operation_leg'))=4");
        assertThat(source.toUpperCase(Locale.ROOT)).doesNotContain("DROP TABLE", "DROP TRIGGER",
                "CREATE OR REPLACE", "INSERT IGNORE", "ON DUPLICATE KEY", "ALTER TABLE");
    }

    @Test void structureDoesNotRewriteOldFactsOrPretendToExecuteBusinessTransactions() throws Exception {
        String source = sql();
        assertThat(source).contains("MySQL 8.0.29+", "DDL is not transactional",
                "business/source/proof validation is in the writer", "never authorize spending",
                "No historical backfill");
        assertThat(source.toUpperCase(Locale.ROOT)).doesNotContain("INSERT INTO", "UPDATE NX_", "DELETE FROM",
                "START TRANSACTION", "COMMIT;", "ROLLBACK;", "FIRSTPAYMENT", "NX_SUPPORT_PAYMENT_HISTORY_BIRTH",
                "EARNINGS_USDT_DEBITED", "FUND_REVISION=");
        assertThat(Pattern.compile("CREATE TABLE IF NOT EXISTS ").matcher(source).results().count()).isEqualTo(2);
    }

    private static boolean checkMatches(String source, String name, String clause) {
        var guard = Pattern.compile("tc.CONSTRAINT_NAME='" + name
                + "'\\s+AND REGEXP_LIKE\\((?:cc.CHECK_CLAUSE|REPLACE\\(cc.CHECK_CLAUSE,CONCAT\\(CHAR\\(92\\),CHAR\\(39\\)\\),CHAR\\(39\\)\\)),'((?:''|[^'])*)','c'\\)")
                .matcher(source);
        assertThat(guard.find()).as("case-sensitive guard %s", name).isTrue();
        String expression = guard.group(1).replace("''", "'")
                .replace("[[:space:]()]", "[\\s()]").replace("[[:space:]]", "\\s");
        assertThat(expression).startsWith("^").endsWith("$");
        if (source.contains("REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),")) {
            clause = clause.replace("\\'", "'");
        }
        return Pattern.compile(expression).matcher(clause).matches();
    }

    private static boolean digestMatches(String source, String clause) {
        if (!checkMatches(source, "ck_support_fund_operation_digest", clause)) return false;
        var expected = Pattern.compile("BINARY REGEXP_SUBSTR\\((?:cc.CHECK_CLAUSE|REPLACE\\(cc.CHECK_CLAUSE,CONCAT\\(CHAR\\(92\\),CHAR\\(39\\)\\),CHAR\\(39\\)\\)),'((?:''|[^'])*)'\\)=BINARY '((?:''|[^'])*)'")
                .matcher(source);
        assertThat(expected.find()).isTrue();
        if (source.contains("REGEXP_SUBSTR(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),")) {
            clause = clause.replace("\\'", "'");
        }
        var literal = Pattern.compile("'[^']*'").matcher(clause);
        return literal.find() && literal.group().equals(expected.group(2).replace("''", "'"));
    }

    @Test void metadataGuardRejectsChangedQuotedContentsAndAdditionalPredicates() throws Exception {
        String source = sql(), environment = "ck_support_fund_operation_environment";
        for (String valid : new String[] {"source_environment='PRODUCTION'",
                " ((`source_environment` = _ascii'PRODUCTION')) ", "source_environment=_utf8mb4'PRODUCTION'"}) {
            assertThat(checkMatches(source, environment, valid)).as(valid).isTrue();
        }
        for (String invalid : new String[] {"source_environment='production'", "source_environment='PRO DUCTION'",
                "source_environment='PRO(DUCTION)'", "source_environment='PRODUCTION' or true"}) {
            assertThat(checkMatches(source, environment, invalid)).as(invalid).isFalse();
        }
        String json = "json_type(ordered_events)='ARRAY' and json_type(source_snapshot)='OBJECT'"
                + " and json_length(source_snapshot)>0 and json_type(source_proof)='OBJECT' and json_length(source_proof)>0";
        assertThat(checkMatches(source, "ck_support_fund_operation_json", json)).isTrue();
        assertThat(checkMatches(source, "ck_support_fund_operation_json",
                json.replace("'ARRAY'", "_utf8mb4'ARRAY'").replace("'OBJECT'", "_ascii'OBJECT'"))).isTrue();
        for (String invalid : new String[] {json.replace("'ARRAY'", "'array'"), json.replace("'ARRAY'", "'A(R)RAY'"),
                json.replace("'OBJECT'", "'OB JECT'"), json.replace("'ARRAY'", "'AR\\nRAY'"), json + " or true"}) {
            assertThat(checkMatches(source, "ck_support_fund_operation_json", invalid)).as(invalid).isFalse();
        }
        String digest = "regexp_like(receipt_sha256,'^[0-9a-f]{64}$','c')";
        assertThat(digestMatches(source, digest)).isTrue();
        assertThat(digestMatches(source, " (regexp_like(`receipt_sha256`,_ascii'^[0-9a-f]{64}$',_utf8mb4'c')) ")).isTrue();
        for (String invalid : new String[] {digest.replace("[0-9a-f]", "[0-9A-F]"),
                digest.replace("64", "6 4"), digest.replace("64", "(64)"), digest.replace("'c'", "'i'"), digest + " or true"}) {
            assertThat(digestMatches(source, invalid)).as(invalid).isFalse();
        }
        String identity = "char_length(op_key)>0 and char_length(op_key)=char_length(trim(op_key))"
                + " and char_length(business_type)>0 and char_length(phase)>0 and char_length(source_table)>0"
                + " and char_length(source_key)>0 and char_length(protocol_version)>0";
        assertThat(checkMatches(source, "ck_support_fund_operation_identity", identity)).isTrue();
        assertThat(checkMatches(source, "ck_support_fund_operation_identity", identity + " or true")).isFalse();
        assertThat(checkMatches(source, "ck_support_fund_operation_leg_revision", "((`revision` >= 0))")).isTrue();
        assertThat(checkMatches(source, "ck_support_fund_operation_leg_revision", "revision>=0 or true")).isFalse();
        assertThat(source).doesNotContain("REGEXP_REPLACE(cc.CHECK_CLAUSE",
                "LOWER(cc.CHECK_CLAUSE");
    }

    @Test void immutableTriggerShapeDoesNotEraseWhitespaceInsideErrorLiteral() throws Exception {
        String source = sql();
        assertThat(source).doesNotContain("REGEXP_REPLACE(ACTION_STATEMENT");
        String expected = "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE'";
        assertThat(source).contains("BINARY ACTION_STATEMENT=", "BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=");
        assertThat(expected.replace("RECORD_IMMUTABLE", "RECORD_ IMMUTABLE")).isNotEqualTo(expected);
    }

    @Test void escapedMetadataQuotesKeepEnvironmentJsonAndDigestContentsExact() throws Exception {
        String source = sql(), environment = "source_environment=_utf8mb4\\'PRODUCTION\\'";
        assertThat(checkMatches(source, "ck_support_fund_operation_environment", environment)).isTrue();
        for (String invalid : new String[]{environment.replace("PRODUCTION", "production"),
                environment.replace("PRODUCTION", "PRO DUCTION"), environment.replace("PRODUCTION", "PRO(DUCTION)"),
                environment.replace("PRODUCTION", "PRO\\'DUCTION"), environment.replace("PRODUCTION", "PRO\\DUCTION"),
                environment.replace("\\'", "\\\\'"), environment + " or true"}) {
            assertThat(checkMatches(source, "ck_support_fund_operation_environment", invalid)).as(invalid).isFalse();
        }
        String json = "json_type(ordered_events)=_utf8mb4\\'ARRAY\\' and json_type(source_snapshot)=_utf8mb4\\'OBJECT\\'"
                + " and json_length(source_snapshot)>0 and json_type(source_proof)=_utf8mb4\\'OBJECT\\' and json_length(source_proof)>0";
        assertThat(checkMatches(source, "ck_support_fund_operation_json", json)).isTrue();
        assertThat(checkMatches(source, "ck_support_fund_operation_json", json.replace("ARRAY", "A(R)RAY"))).isFalse();
        String digest = "regexp_like(receipt_sha256,_utf8mb4\\'^[0-9a-f]{64}$\\',_utf8mb4\\'c\\')";
        assertThat(digestMatches(source, digest)).isTrue();
        for (String invalid : new String[]{digest.replace("[0-9a-f]", "[0-9A-F]"),
                digest.replace("64", "6\\'4"), digest.replace("\\'c", "\\'i"), digest + " or true"}) {
            assertThat(digestMatches(source, invalid)).as(invalid).isFalse();
        }
        assertThat(source).contains("REGEXP_SUBSTR(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),");
    }

    private static void assertGuardParenthesesBalanced(String source, String variable) {
        int checked = 0, from = 0, start;
        while ((start = source.indexOf("SET " + variable + "=IF(", from)) >= 0) {
            boolean quoted = false, ended = false;
            int depth = 0;
            for (int i = start; i < source.length(); i++) {
                char current = source.charAt(i);
                if (current == '\'') {
                    if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (!quoted) {
                    if (current == '(') depth++;
                    if (current == ')') depth--;
                    assertThat(depth).as("guard at offset %s, character %s", start, i).isNotNegative();
                    if (current == ';') {
                        assertThat(depth).as("guard at offset %s", start).isZero();
                        from = i + 1;
                        ended = true;
                        break;
                    }
                }
            }
            assertThat(ended).as("guard at offset %s terminates outside a literal", start).isTrue();
            checked++;
        }
        assertThat(checked).isGreaterThan(5);
    }

    @Test void everyDynamicGuardBalancesSqlParenthesesWithoutCountingQuotedRegexCharacters() throws Exception {
        String operation = sql();
        assertGuardParenthesesBalanced(operation, "@fund_operation_sql");
        assertGuardParenthesesBalanced(Files.readString(Path.of("scripts/migrations/20261011_support_fund_journal.sql")),
                "@fund_sql");
        // Mutation must fail even though every CHECK regex still matches its isolated samples.
        String malformed = operation.replace("=4,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_CHECKS'",
                ")=4,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_CHECKS'");
        assertThatThrownBy(() -> assertGuardParenthesesBalanced(malformed, "@fund_operation_sql"))
                .isInstanceOf(AssertionError.class);
    }
}
