package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppLearningObservationDeltaContractTest {
    @Test
    void productionDeltaQueriesAreRunWindowAndSandboxUserScopedNotHardcodedZeroes() throws Exception {
        Map<String, String> sourceByMethod = Map.ofEntries(
                Map.entry("productionLearningProgressDelta", "nx_learning_progress"),
                Map.entry("productionLearningEventDelta", "nx_learning_event"),
                Map.entry("productionLearningRewardDelta", "nx_learning_reward_ledger"),
                Map.entry("productionLearningEarningsReleaseDelta", "nx_earnings_release_entry"),
                Map.entry("productionLearningWalletLedgerDelta", "nx_wallet_ledger"),
                Map.entry("productionLearningOutboxDelta", "nx_event_outbox"),
                Map.entry("productionLearningAdminIdempotencyDelta", "nx_admin_idempotency_record"),
                Map.entry("productionLearningCatalogVersionDelta", "nx_learning_course_version"),
                Map.entry("productionLearningCatalogAdminIdempotencyDelta", "nx_admin_idempotency_record"),
                Map.entry("productionLearningCatalogAuditDelta", "nx_audit_log"),
                Map.entry("productionLearningCatalogOutboxDelta", "nx_event_outbox"));
        for (Map.Entry<String, String> expected : sourceByMethod.entrySet()) {
            String method = expected.getKey();
            Select query = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow().getAnnotation(Select.class);
            String sql = String.join(" ", query.value()).toLowerCase(Locale.ROOT);
            assertThat(sql).contains(expected.getValue()).contains("#{runid}").contains("#{fromat}").contains("#{toat}").contains("exists").doesNotContain("select 0");
        }
    }

    @Test
    void financialDeltasAreBoundToTheLearningRewardBusinessSource() throws Exception {
        Select earnings = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals("productionLearningEarningsReleaseDelta")).findFirst().orElseThrow().getAnnotation(Select.class);
        Select wallet = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals("productionLearningWalletLedgerDelta")).findFirst().orElseThrow().getAnnotation(Select.class);

        assertThat(String.join(" ", earnings.value()).toLowerCase(Locale.ROOT))
                .contains("source_ref like 'learn:%'")
                .doesNotContain("source_type=").doesNotContain("source_environment=").doesNotContain("is_deleted=");
        assertThat(String.join(" ", wallet.value()).toLowerCase(Locale.ROOT))
                .contains("biz_no like 'learn:%'")
                .doesNotContain("biz_type=").doesNotContain("is_deleted=");
    }

    @Test
    void causalWindowAndProductionDeltaNeverHideSoftDeletedWrongLabelOrLateWrites() throws Exception {
        Select window = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals("sandboxObservationWindow")).findFirst().orElseThrow().getAnnotation(Select.class);
        String windowSql = String.join(" ", window.value()).toLowerCase(Locale.ROOT);
        assertThat(windowSql).contains("date_sub(min(created_at),interval 1 minute)")
                .contains("date_add(max(updated_at),interval 1 minute)")
                .contains("nx_learning_sandbox_course").contains("nx_learning_sandbox_admin_idempotency");

        for (String method : new String[] {"productionLearningProgressDelta", "productionLearningEventDelta", "productionLearningRewardDelta", "productionLearningEarningsReleaseDelta", "productionLearningWalletLedgerDelta", "productionLearningOutboxDelta"}) {
            Select query = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow().getAnnotation(Select.class);
            String sql = String.join(" ", query.value()).toLowerCase(Locale.ROOT);
            assertThat(sql).as(method).doesNotContain("is_deleted=").doesNotContain("status='granted'")
                    .doesNotContain("source_environment='production'");
        }
    }

    @Test
    void catalogDeltaCatchesFormalI7DefinitionsAndAdministrativeWriteChain() throws Exception {
        for (String method : new String[] {"productionLearningCatalogVersionDelta", "productionLearningCatalogAdminIdempotencyDelta", "productionLearningCatalogAuditDelta", "productionLearningCatalogOutboxDelta"}) {
            Select query = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow().getAnnotation(Select.class);
            String sql = String.join(" ", query.value()).toLowerCase(Locale.ROOT);
            assertThat(sql).as(method).contains("nx_learning_sandbox_course").contains("#{runid}").contains("#{fromat}").contains("#{toat}")
                    .doesNotContain("is_deleted=").doesNotContain("select 0");
        }
    }

    @Test
    void sandboxCatalogUsesRevisionCasAndOnePublishedAuthorityPerRunCourse() throws Exception {
        Update save = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals("updateSandboxCourseDraft")).findFirst().orElseThrow().getAnnotation(Update.class);
        Update publish = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals("publishSandboxCourse")).findFirst().orElseThrow().getAnnotation(Update.class);
        Update delete = java.util.Arrays.stream(AppLearningMapper.class.getMethods()).filter(m -> m.getName().equals("deleteSandboxCourse")).findFirst().orElseThrow().getAnnotation(Update.class);
        assertThat(String.join(" ", save.value()).toLowerCase(Locale.ROOT)).contains("revision=#{row.revision}").contains("status='draft'");
        assertThat(String.join(" ", publish.value()).toLowerCase(Locale.ROOT)).contains("revision=#{expectedrevision}").contains("not exists").contains("published_authority");
        assertThat(String.join(" ", delete.value()).toLowerCase(Locale.ROOT)).contains("revision=#{expectedrevision}").contains("status='draft'");
    }
}
