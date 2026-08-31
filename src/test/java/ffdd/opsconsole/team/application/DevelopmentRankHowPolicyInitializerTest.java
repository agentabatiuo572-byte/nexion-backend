package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;

class DevelopmentRankHowPolicyInitializerTest {
    @Test
    void seedsAnEditablePublishedDocumentOnlyWhenThePcConfigIsMissing() throws Exception {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.insertAdminValueIfMissing(
                org.mockito.ArgumentMatchers.eq(PublishedRankHowPolicyService.CONFIG_KEY),
                anyString(),
                org.mockito.ArgumentMatchers.eq("JSON"),
                org.mockito.ArgumentMatchers.eq("published_content"),
                anyString())).thenReturn(true);

        PublishedRankHowPolicyService policyService = mock(PublishedRankHowPolicyService.class);
        new DevelopmentRankHowPolicyInitializer(config, policyService).run(null);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(config).insertAdminValueIfMissing(
                org.mockito.ArgumentMatchers.eq(PublishedRankHowPolicyService.CONFIG_KEY), value.capture(),
                org.mockito.ArgumentMatchers.eq("JSON"), org.mockito.ArgumentMatchers.eq("published_content"), anyString());
        var document = new ObjectMapper().readTree(value.getValue());
        assertThat(document.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(document.path("revision").asLong()).isPositive();
        assertThat(document.path("locales").has("zh")).isTrue();
        assertThat(document.path("locales").has("en")).isTrue();
        assertThat(document.path("locales").has("vi")).isTrue();
        assertThat(document.path("locales").path("zh").path("sections")).hasSize(29);
        assertThat(document.path("locales").path("en").path("sections")).hasSize(29);
        assertThat(document.path("locales").path("vi").path("sections")).hasSize(29);
        assertThat(document.path("locales").path("en").path("sections").findValuesAsText("id"))
                .containsExactly("overview", "overview-detail", "ladder", "promotion", "requirement-self",
                        "requirement-direct", "requirement-team", "requirement-legs", "stepwise", "protection",
                        "rewards", "unlock-network", "unlock-peer", "unlock-leadership", "unlock-cultivation",
                        "example", "example-start", "example-self", "example-team", "example-legs",
                        "example-trigger", "example-results", "result-network", "result-peer", "result-leadership",
                        "result-cultivation", "faq-members", "faq-rank", "faq-rewards");
        verifyNoInteractions(policyService);
    }

    @Test
    void preservesActiveDisabledAndSoftDeletedPcAuthoredDocuments() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.insertAdminValueIfMissing(
                org.mockito.ArgumentMatchers.eq(PublishedRankHowPolicyService.CONFIG_KEY),
                anyString(),
                org.mockito.ArgumentMatchers.eq("JSON"),
                org.mockito.ArgumentMatchers.eq("published_content"),
                anyString())).thenReturn(false);

        PublishedRankHowPolicyService policyService = mock(PublishedRankHowPolicyService.class);
        new DevelopmentRankHowPolicyInitializer(config, policyService).run(null);

        verify(config).insertAdminValueIfMissing(
                org.mockito.ArgumentMatchers.eq(PublishedRankHowPolicyService.CONFIG_KEY), anyString(),
                org.mockito.ArgumentMatchers.eq("JSON"), org.mockito.ArgumentMatchers.eq("published_content"), anyString());
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(policyService);
    }

    @Test
    void upgradesOnlyTheExactOriginalPublishedInitializerDocumentThroughAuditedCasUpdate() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        PublishedRankHowPolicyService policyService = mock(PublishedRankHowPolicyService.class);
        when(config.insertAdminValueIfMissing(
                org.mockito.ArgumentMatchers.eq(PublishedRankHowPolicyService.CONFIG_KEY), anyString(),
                org.mockito.ArgumentMatchers.eq("JSON"), org.mockito.ArgumentMatchers.eq("published_content"), anyString()))
                .thenReturn(false);
        when(policyService.update(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq(1L), anyString()))
                .thenReturn(ffdd.opsconsole.shared.api.ApiResult.ok(java.util.Map.of()));
        when(config.activeValueForUpdate(PublishedRankHowPolicyService.CONFIG_KEY)).thenReturn(java.util.Optional.of("""
                {"version":"2026.08.22","status":"PUBLISHED","revision":1,"locales":{"zh":{"hero":"V-Rank 由真实购买、有效直推、团队业绩和下级等级共同决定。","sections":[{"id":"promotion","title":"等级如何晋升","body":"达到 PC 管理端 F1 已发布的全部正数门槛后，服务端按阶逐级晋升。","order":1},{"id":"rewards","title":"奖励如何发放","body":"等级权益、票权和培育奖励以服务端结算与佣金事件为准。","order":2}]},"en":{"hero":"V-Rank is calculated from verified purchases, active referrals, team volume and qualified legs.","sections":[{"id":"promotion","title":"How promotion works","body":"The server promotes one step at a time after every positive F1 threshold is met.","order":1},{"id":"rewards","title":"How rewards are paid","body":"Benefits, votes and cultivation rewards follow server settlement and commission events.","order":2}]},"vi":{"hero":"V-Rank dựa trên giao dịch hợp lệ, tuyến giới thiệu hoạt động, doanh số đội nhóm và nhánh đạt chuẩn.","sections":[{"id":"promotion","title":"Cách thăng hạng","body":"Máy chủ thăng từng bậc sau khi đáp ứng toàn bộ ngưỡng dương đã công bố trong F1.","order":1},{"id":"rewards","title":"Cách trả thưởng","body":"Quyền lợi, phiếu bầu và thưởng đào tạo dựa trên quyết toán và sự kiện hoa hồng của máy chủ.","order":2}]}}}
                """));

        new DevelopmentRankHowPolicyInitializer(config, policyService).run(null);

        verify(policyService).update(
                org.mockito.ArgumentMatchers.eq("2026.08.31"),
                org.mockito.ArgumentMatchers.eq("PUBLISHED"),
                org.mockito.ArgumentMatchers.argThat(locales -> locales.size() == 3),
                org.mockito.ArgumentMatchers.eq(1L), anyString());
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void neverUpgradesAnEditedOrDraftDocumentThatOnlyResemblesTheOriginal() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        PublishedRankHowPolicyService policyService = mock(PublishedRankHowPolicyService.class);
        when(config.insertAdminValueIfMissing(
                org.mockito.ArgumentMatchers.eq(PublishedRankHowPolicyService.CONFIG_KEY), anyString(),
                org.mockito.ArgumentMatchers.eq("JSON"), org.mockito.ArgumentMatchers.eq("published_content"), anyString()))
                .thenReturn(false);
        when(config.activeValueForUpdate(PublishedRankHowPolicyService.CONFIG_KEY)).thenReturn(java.util.Optional.of("""
                {"version":"2026.08.22","status":"DRAFT","revision":1,"locales":{}}
                """));

        new DevelopmentRankHowPolicyInitializer(config, policyService).run(null);

        verifyNoInteractions(policyService);
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void exactCheckAndCasRunWithinTheSameTransaction() throws Exception {
        assertThat(DevelopmentRankHowPolicyInitializer.class
                .getMethod("run", org.springframework.boot.ApplicationArguments.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }
}
