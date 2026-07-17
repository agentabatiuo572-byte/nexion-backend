package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class RiskOpsMapperK5ContractTest {
    @Test
    void decisionUpdateIsVersionedAndDoesNotDuplicateC4KycTruth() throws Exception {
        Update update = RiskOpsMapper.class.getMethod("updateKycReviewTicketStatus",
                String.class, String.class, long.class, String.class, String.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value());

        assertThat(sql).contains("status='in-review'", "version=#{expectedVersion}", "version = version + 1");
        assertThat(sql).contains("history_json", "JSON_ARRAY_APPEND", "reasonCode", "operator");
        assertThat(sql).contains("CASE WHEN #{status}='passed' THEN '' ELSE 'bad' END");
        assertThat(sql).contains("操作人", "#{operator}");
        assertThat(sql).doesNotContain("#{reason}),#{operator})");
        assertThat(sql).doesNotContain("kyc_text", "APPROVED", "REJECTED");
    }

    @Test
    void mergeHistoryUsesWarnToneAndKeepsOperatorInsideEventText() throws Exception {
        Update update = RiskOpsMapper.class.getMethod(
                        "mergeOpenKycReviewTicket", String.class, long.class, String.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value());

        assertThat(sql).contains("操作人", "#{operator}", ",'warn')");
        assertThat(sql).contains(
                "info_json = JSON_ARRAY_APPEND", "JSON_ARRAY('触发原因',#{reason})");
        assertThat(sql).doesNotContain("#{reason}),#{operator})");
    }

    @Test
    void ticketProjectionFailsClosedWhenC4UserIsUnavailable() throws Exception {
        for (String sql : kycTicketProjectionSql()) {
            assertThat(sql)
                    .contains("LEFT JOIN nx_user", "CASE WHEN u.id IS NULL THEN 'USER_UNAVAILABLE'", "END AS kyc")
                    .doesNotContain("t.kyc_text AS kyc");
        }
    }

    @Test
    void ticketProjectionKeepsPendingSemanticForExistingUserWhoseKycIsNull() throws Exception {
        for (String sql : kycTicketProjectionSql()) {
            assertThat(sql).contains("ELSE COALESCE(u.kyc_status,'PENDING') END AS kyc");
        }
    }

    private static java.util.List<String> kycTicketProjectionSql() throws Exception {
        var page = RiskOpsMapper.class.getMethod("pageKycReviewTickets", String.class, int.class, int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var detail = RiskOpsMapper.class.getMethod("findKycReviewTicket", String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var open = RiskOpsMapper.class.getMethod("findOpenKycReviewTicketByUser", String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        return java.util.List.of(
                String.join(" ", page.value()),
                String.join(" ", detail.value()),
                String.join(" ", open.value()));
    }

    @Test
    void ticketQueryFiltersByAnyExactProducedTicketType() throws Exception {
        var count = RiskOpsMapper.class.getMethod("countKycTicketsByFilter", String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var page = RiskOpsMapper.class.getMethod(
                        "pageKycReviewTickets", String.class, int.class, int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        assertThat(String.join(" ", count.value())).contains("ticket_type = #{filter}");
        assertThat(String.join(" ", page.value())).contains("t.ticket_type = #{filter}");
    }

    @Test
    void openTicketUniquenessIsBackedByGeneratedUserKey() {
        String sql = Arrays.stream(RiskOpsMapper.class.getMethods())
                .filter(method -> method.getName().equals("createKycReviewTicketTable"))
                .findFirst().orElseThrow().getAnnotation(Update.class).value()[0];
        assertThat(sql).contains("open_user_key", "UNIQUE KEY uk_admin_risk_kyc_open_user", "triggered", "in-review");
    }

    @Test
    void alertsAreSubscriptionFilterableBoundedAndNewestFirst() throws Exception {
        var select = RiskOpsMapper.class.getMethod("kycAlerts", java.util.List.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        String sql = String.join(" ", select.value());
        assertThat(sql).contains("event_key LIKE", "created_at DESC", "LIMIT 100", "DATE_FORMAT(created_at");
    }

    @Test
    void slaChangesRecomputeEveryOpenTicketFromItsCreationTime() throws Exception {
        Update update = RiskOpsMapper.class.getMethod("recomputeOpenKycDueAt", int.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value());
        assertThat(sql).contains("WITH RECURSIVE", "created_at", "WEEKDAY", "workingDays", "version=t.version+1");
    }

    @Test
    void subscriptionFirstInsertAdvancesVersionToOne() throws Exception {
        var insert = RiskOpsMapper.class.getMethod(
                        "insertKycAlertSubscription", String.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class);
        assertThat(String.join(" ", insert.value())).contains("version", ",1)");
    }

    @Test
    void everyMergedBusinessSourceHasAUniqueDurableTicketLink() throws Exception {
        Update table = RiskOpsMapper.class.getMethod("createKycReviewSourceTable")
                .getAnnotation(Update.class);
        String ddl = String.join(" ", table.value());
        assertThat(ddl).contains("nx_admin_risk_kyc_review_source", "ticket_id", "source_domain", "source_no",
                "UNIQUE KEY uk_admin_risk_kyc_review_source");

        Insert link = RiskOpsMapper.class.getMethod(
                        "insertKycReviewSource", String.class, String.class, String.class)
                .getAnnotation(Insert.class);
        assertThat(String.join(" ", link.value())).contains("INSERT IGNORE", "ticket_id", "source_domain", "source_no");
    }

    @Test
    void largeWithdrawalBurstAlertUsesFiveD2SourcesInOneRollingHour() throws Exception {
        Insert insert = RiskOpsMapper.class.getMethod("insertLargeWithdrawalBurstKycAlert")
                .getAnnotation(Insert.class);
        String sql = String.join(" ", insert.value());
        assertThat(sql).contains("large-withdraw-burst:", "source_domain='D2'", "INTERVAL 1 HOUR", "COUNT(*) >= 5");
    }

    @Test
    void frozenAmountReadsAuthoritativeD2HoldStateInsteadOfTicketAmount() throws Exception {
        var select = RiskOpsMapper.class.getMethod("sumFrozenWithdrawalUsdt")
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        String sql = String.join(" ", select.value());
        assertThat(sql).contains("nx_withdrawal_order", "status='FROZEN'", "failure_reason LIKE 'K5_REVIEW:%'");
        assertThat(sql).doesNotContain("nx_admin_risk_kyc_review_ticket");
    }

    @Test
    void supportReceivesOnlyK5ReadPermissionAndMenuInSeedAndMigration() throws Exception {
        String permissions = Files.readString(Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"));
        String menus = Files.readString(Path.of("scripts/rbac-classic-seed/01-menu-seed.sql"));
        String migration = Files.readString(Path.of("scripts/migrations/20260717_k5_kyc_review_closure.sql"));

        assertThat(permissions).contains("r.role_code='SUPPORT'", "p.permission_code='risk_k5_read'");
        assertThat(menus).contains("r.role_code='SUPPORT'", "m.menu_code IN ('K','K5')");
        assertThat(migration).contains("risk_k5_read", "role_code='SUPPORT'", "menu_code IN ('K','K5')");
    }

    @Test
    void migrationNormalizesLegacyHistoryOperatorIntoEventAndLegalToneIdempotently() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260717_k5_kyc_review_closure.sql"));

        assertThat(migration).contains(
                "WITH RECURSIVE k5_history_rebuild",
                "JSON_ARRAY_APPEND",
                "'·操作人:'",
                "IN ('','warn','bad')",
                "THEN 'bad'",
                "THEN 'warn'");
    }
}
