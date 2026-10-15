package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** SQL shape evidence only; this does not execute MySQL or establish transactional behavior. */
class SupportFundJournalMigrationContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20261011_support_fund_journal.sql");
    // From schema.sql, Janus wallet sandbox migration, and Cregis risk-held migration.
    private static final List<String> WATCHED = List.of("id", "user_id", "usdt_available",
            "nex_available", "pending_withdraw", "cregis_risk_held", "version",
            "sandbox", "is_deleted", "created_at");

    private static String sql() throws Exception { return Files.readString(MIGRATION); }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static String trigger(String sql, String suffix) {
        var match = Pattern.compile("CREATE TRIGGER IF NOT EXISTS nx_support_wallet_journal_"
                + suffix + "_v1\\s+[^$]*?\\n(BEGIN.*?)\\$\\$", Pattern.DOTALL).matcher(sql);
        assertThat(match.find()).as("trigger %s exists", suffix).isTrue();
        return match.group(1);
    }

    @Test void everyRealWalletFieldIsWatchedAndCapturedBeforeAndAfter() throws Exception {
        String source = sql(), before = compact(trigger(source, "bu")), after = compact(trigger(source, "au"));
        for (String field : WATCHED) {
            assertThat(before).as("watch %s", field).contains("NOT(OLD." + field + "<=>NEW." + field + ")");
            assertThat(after).as("column pair %s", field).contains("old_" + field + ",new_" + field);
            assertThat(after).as("value pair %s", field).contains("OLD." + field + ",NEW." + field);
            assertThat(source).contains("COLUMN_NAME='" + field + "'");
        }
        assertThat(before).contains("SETNEW.fund_revision=OLD.fund_revision+1;");
        assertThat(before).doesNotContain("SETNEW.version=", "SETNEW.usdt_available=", "SETNEW.nex_available=",
                "earnings_usdt_debited=", "allocation", "withdrawable");
        assertThat(after).contains("IFNEW.fund_revision<>OLD.fund_revisionTHEN");
    }

    @Test void actualNewZeroBirthDoesNotLaunderExistingOrRebuiltWallets() throws Exception {
        String insert = compact(trigger(sql(), "ai"));
        assertThat(insert).contains("NEW.fund_revision=0ANDNEW.version=0ANDNEW.usdt_available=0",
                "NEW.nex_available=0", "NEW.pending_withdraw=0", "NEW.cregis_risk_held=0",
                "NEW.is_deleted=0", "ANDprior_incarnation=0,'NEW_ZERO','NEW_UNPROVEN'",
                "WHEREnew_id=NEW.idORold_id=NEW.idORnew_user_id=NEW.user_idORold_user_id=NEW.user_id",
                "SELECTEXISTS(", "INTOprior_incarnation", "NULL,NEW.id", "NULL,NEW.user_id");
        assertThat(sql()).doesNotContain("INSERT INTO nx_support_wallet_journal SELECT",
                "nx_support_payment_history_birth", "first_payment");
    }

    @Test void revisionAndIncarnationAreSeparateFromBusinessVersionAndCannotResetSilently() throws Exception {
        String source = sql(), before = compact(trigger(source, "bu"));
        assertThat(source).contains("ADD COLUMN fund_revision BIGINT NOT NULL DEFAULT 0",
                "ALTER COLUMN fund_incarnation SET DEFAULT (UUID())",
                "UNIQUE KEY uk_support_wallet_journal_revision(wallet_incarnation,revision)");
        assertThat(before).contains("NOT(NEW.fund_incarnation<=>OLD.fund_incarnation)",
                "NOT(NEW.fund_revision<=>OLD.fund_revision)",
                "SIGNALSQLSTATE'45000'", "'SUPPORT_WALLET_JOURNAL_METADATA_CHANGED'");
        assertThat(source).contains("No birth rows are backfilled", "No FK/cascade",
                "current wallet/incarnation/tail equality", "contiguous revisions", "adjacent full snapshots",
                "database write/DDL", "cannot certify that interval");
    }

    @Test void exactReplayGuardsPreserveTheEarningsTriggerAndRejectUnknownShape() throws Exception {
        String source = sql();
        for (String suffix : List.of("ai", "bu", "au")) {
            var expected = Pattern.compile("SET @fund_expected_" + suffix
                    + "='((?:''|[^'])*)';", Pattern.DOTALL).matcher(source);
            assertThat(expected.find()).isTrue();
            assertThat(expected.group(1).replace("''", "'"))
                    .isEqualTo(trigger(source, suffix));
            assertThat(source).contains("support_fund_journal_trigger_" + suffix + "_shape_invalid()",
                    "BINARY ACTION_STATEMENT=BINARY @fund_expected_" + suffix,
                    "TRIGGER_NAME='nx_support_wallet_journal_" + suffix + "_v1'");
        }
        assertThat(source).contains("FOLLOWS nx_wallet_earnings_debit_counter_v1",
                "ACTION_ORDER", "support_fund_journal_wallet_trigger_order_invalid()",
                "support_fund_journal_table_shape_invalid()", "support_fund_journal_unique_shape_invalid()",
                "support_fund_journal_check_shape_invalid()", "tc.ENFORCED='YES'",
                "GENERATION_EXPRESSION", "REFERENCED_TABLE_NAME IS NOT NULL",
                "EVENT_OBJECT_TABLE='nx_support_wallet_journal'",
                "IS_VISIBLE='YES'", "SUB_PART IS NULL", "GET_LOCK(", "RELEASE_LOCK(");
        assertThat(source.toUpperCase(Locale.ROOT)).doesNotContain("DROP TRIGGER", "DROP TABLE",
                "CREATE OR REPLACE", "INSERT IGNORE", "ON DUPLICATE KEY", "ALTER TABLE NX_EARNINGS");
    }

    @Test void capturesStayInWalletTransactionAndDoNotGuessFormalOperations() throws Exception {
        String source = sql();
        assertThat(source).contains("AFTER INSERT ON nx_user_wallet FOR EACH ROW",
                "BEFORE UPDATE ON nx_user_wallet FOR EACH ROW",
                "AFTER UPDATE ON nx_user_wallet FOR EACH ROW", ") ENGINE=InnoDB;",
                "UTC_TIMESTAMP(6)", "recorded_db_utc DATETIME(6) NOT NULL",
                "SHA2('SUPPORT_WALLET_JOURNAL_V1',256)");
        for (String suffix : List.of("ai", "bu", "au")) {
            assertThat(trigger(source, suffix).toUpperCase(Locale.ROOT)).doesNotContain("COMMIT",
                    "ROLLBACK", "START TRANSACTION", "ALLOCATION", "PAYMENT", "WITHDRAWAL_ORDER");
        }
        assertThat(source).contains("MySQL 8.0.29+", "DDL is not transactional",
                "complete formal operation legs");
        assertThat(source.toUpperCase(Locale.ROOT)).doesNotContain("FOREIGN KEY", "CASCADE DELETE",
                "UPDATE NX_SUPPORT_WALLET_JOURNAL", "DELETE FROM NX_SUPPORT_WALLET_JOURNAL");
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

    @Test void checkMetadataAllowsExternalFormattingButPreservesEveryLiteral() throws Exception {
        String source = sql(), revision = "ck_support_wallet_journal_revision";
        for (String valid : List.of("revision>=0", " ((`revision` >= 0)) ", "(revision) >= (0)")) {
            assertThat(checkMatches(source, revision, valid)).as(valid).isTrue();
        }
        for (String invalid : List.of("revision>=1", "revision>=0 or true", "revision>='0'", "revision>=0 + 0")) {
            assertThat(checkMatches(source, revision, invalid)).as(invalid).isFalse();
        }
        String observation = "ck_support_wallet_journal_observation";
        for (String valid : List.of("observation IN ('NEW_ZERO','NEW_UNPROVEN','CHANGE')",
                " ((`observation` in (_ascii'NEW_ZERO', _ascii'NEW_UNPROVEN', _ascii'CHANGE'))) ",
                "observation IN (_utf8mb4'NEW_ZERO', 'NEW_UNPROVEN', 'CHANGE')")) {
            assertThat(checkMatches(source, observation, valid)).as(valid).isTrue();
        }
        String valid = "observation IN ('NEW_ZERO','NEW_UNPROVEN','CHANGE')";
        for (String invalid : List.of(valid.toLowerCase(Locale.ROOT),
                valid.replace("NEW_ZERO", "NEW_ ZERO"), valid.replace("NEW_ZERO", "NEW_(ZERO)"),
                valid.replace("CHANGE", "CHANGE "), valid.replace("NEW_ZERO", "NEW_\\nZERO"),
                valid.replace("NEW_ZERO", "NEW_UNPROVEN").replace("'NEW_UNPROVEN','NEW_UNPROVEN'", "'NEW_UNPROVEN','NEW_ZERO'"),
                valid + " OR 1=1", valid.replace("'CHANGE'", "_binary'CHANGE'"))) {
            assertThat(checkMatches(source, observation, invalid)).as(invalid).isFalse();
        }
        assertThat(source).doesNotContain("REGEXP_REPLACE(cc.CHECK_CLAUSE", "LOWER(cc.CHECK_CLAUSE");
    }

    @Test void triggerFingerprintChecksPreserveProtocolAndObservationLiteralBytes() throws Exception {
        String source = sql();
        assertThat(source).doesNotContain("REGEXP_REPLACE(ACTION_STATEMENT");
        for (String suffix : List.of("ai", "bu", "au")) {
            assertThat(source).contains("BINARY ACTION_STATEMENT=BINARY @fund_expected_" + suffix);
            String body = trigger(source, suffix);
            if (!suffix.equals("bu")) {
                assertThat(body.replace("SUPPORT_WALLET_JOURNAL_V1", "SUPPORT_WALLET_ JOURNAL_V1")).isNotEqualTo(body);
            } else {
                assertThat(body.replace("METADATA_CHANGED", "METADATA_ CHANGED")).isNotEqualTo(body);
            }
        }
    }

    @Test void legacyIncarnationInitializationUsesReplayableBinlogSafeStagesWithoutHistoryReset() throws Exception {
        String source = sql();
        String add = "ADD COLUMN fund_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL";
        String seed = "UPDATE nx_user_wallet SET fund_incarnation=UUID(),updated_at=updated_at WHERE fund_incarnation IS NULL";
        String harden = "MODIFY COLUMN fund_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL";
        String defaultOnly = "ALTER COLUMN fund_incarnation SET DEFAULT (UUID())";
        assertThat(source).contains(add, seed, harden, defaultOnly,
                "@@GLOBAL.log_bin=0 OR @@SESSION.binlog_format IN ('ROW','MIXED')",
                "FAIL support_fund_journal_incarnation_transition_invalid",
                "FAIL support_fund_journal_incarnation_binlog_invalid",
                "@fund_existing_journal_rows=0", "@fund_existing_journal_triggers=0",
                "fund_revision<>0", "COLUMN_DEFAULT IS NULL", "EXTRA=''",
                "COALESCE(GENERATION_EXPRESSION,'')=''", "COLUMN_TYPE='char(36)'");
        assertThat(source.indexOf(add)).isLessThan(source.indexOf(seed));
        assertThat(source.indexOf(seed)).isLessThan(source.indexOf(harden));
        assertThat(source.indexOf(harden)).isLessThan(source.indexOf(defaultOnly));
        assertThat(source.replaceAll("\\s+", " ")).contains(
                "SET @fund_sql=IF(@fund_incarnation_missing=1 OR @fund_incarnation_transition=1, '"
                        + seed + "','SELECT 1');");
        assertThat(source).doesNotContain("NOT NULL DEFAULT (UUID())'", "SET SESSION binlog_format",
                "sql_log_bin=0", "SET fund_incarnation=UUID()'", "SET fund_revision=0");
        assertThat(source).contains("@fund_incarnation_transition=1", "@fund_incarnation_missing=1");
    }

    @Test void allWalletTriggersAndAllFourReadIndexesHaveExactInventories() throws Exception {
        String source = sql();
        assertThat(source).contains("FAIL support_fund_journal_wallet_trigger_inventory_invalid",
                "EVENT_OBJECT_TABLE='nx_user_wallet')=4",
                "FAIL support_fund_journal_read_index_shape_invalid", "NON_UNIQUE=1)=8",
                "NON_UNIQUE=1 AND SUB_PART IS NULL AND IS_VISIBLE='YES' AND INDEX_TYPE='BTREE'");
        for (String[] index : List.of(new String[]{"wallet", "new_id"}, new String[]{"customer", "new_user_id"},
                new String[]{"old_wallet", "old_id"}, new String[]{"old_customer", "old_user_id"})) {
            String prefix = "INDEX_NAME='idx_support_wallet_journal_" + index[0] + "' AND SEQ_IN_INDEX=";
            assertThat(source).contains(prefix + "1 AND COLUMN_NAME='" + index[1] + "'",
                    prefix + "2 AND COLUMN_NAME='id'");
        }
    }

    @Test void actualMysqlEscapedQuoteMetadataIsAcceptedWithoutChangingLiteralMeaning() throws Exception {
        String source = sql(), name = "ck_support_wallet_journal_observation";
        String actual = "(`observation` in (_utf8mb4\\'NEW_ZERO\\',_utf8mb4\\'NEW_UNPROVEN\\',_utf8mb4\\'CHANGE\\'))";
        assertThat(checkMatches(source, name, actual)).isTrue();
        for (String invalid : List.of(actual.replace("NEW_ZERO", "new_zero"),
                actual.replace("NEW_ZERO", "NEW_ ZERO"), actual.replace("CHANGE", "CH(ANGE)"),
                actual.replace("NEW_ZERO", "NEW_\\'ZERO"), actual.replace("NEW_ZERO", "NEW_\\ZERO"),
                actual.replace("\\'", "\\\\'"), actual + " OR 1=1")) {
            assertThat(checkMatches(source, name, invalid)).as(invalid).isFalse();
        }
        assertThat(source).contains("REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39))");
    }
}
