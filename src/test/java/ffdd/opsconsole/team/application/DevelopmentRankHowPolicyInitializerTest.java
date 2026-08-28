package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        new DevelopmentRankHowPolicyInitializer(config).run(null);

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

        new DevelopmentRankHowPolicyInitializer(config).run(null);

        verify(config).insertAdminValueIfMissing(
                org.mockito.ArgumentMatchers.eq(PublishedRankHowPolicyService.CONFIG_KEY), anyString(),
                org.mockito.ArgumentMatchers.eq("JSON"), org.mockito.ArgumentMatchers.eq("published_content"), anyString());
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
