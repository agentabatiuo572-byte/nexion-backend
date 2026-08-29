package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublishedDeveloperDocsServiceTest {
    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @Test
    void resolvesPublishedLocaleAndNeverReturnsDraft() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("developer.docs.published")).thenReturn(Optional.of("""
                {"version":"2026.08.17","status":"PUBLISHED","locales":{"en":{"example":{"request":"POST /v1/jobs","response":"200"},"endpoints":[{"method":"POST","path":"/v1/jobs"}],"events":["job.completed"]},"zh":{"example":{"request":"POST /v1/jobs","response":"200"},"endpoints":[{"method":"POST","path":"/v1/jobs"}],"events":["job.completed"]}}}
                """));
        var result = new PublishedDeveloperDocsService(config, productionEnvironment(), mock(AuditLogService.class)).publicDocument("zh");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("version", "2026.08.17").containsEntry("locale", "zh");
        assertThat(result.getData().get("endpoints")).asList().hasSize(1);
    }

    @Test
    void unknownRuntimeProfileCannotReadPublishedProductionContent() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("developer.docs.published")).thenReturn(Optional.of("""
                {"version":"2026.08.17","status":"PUBLISHED","locales":{"en":{"example":{"request":"GET /v1","response":"200"},"endpoints":[{"method":"GET","path":"/v1"}],"events":["job.completed"]}}}
                """));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        var result = new PublishedDeveloperDocsService(config, environment, mock(AuditLogService.class))
                .publicDocument("en");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_DOCS_UNAVAILABLE");
    }

    @Test
    void missingOrDraftContentFailsClosedWithUnavailable() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("developer.docs.published")).thenReturn(Optional.of("{\"version\":\"draft\",\"status\":\"DRAFT\",\"locales\":{}}"));
        var result = new PublishedDeveloperDocsService(config, new MockEnvironment(), mock(AuditLogService.class)).publicDocument("en");
        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_DOCS_UNAVAILABLE");
    }

    @Test
    void adminUpdateValidatesStructuredEndpointsAndLocales() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        AuditLogService audit=mock(AuditLogService.class);
        when(config.activeValueForUpdate("developer.docs.published")).thenReturn(Optional.empty());
        var service = new PublishedDeveloperDocsService(config, new MockEnvironment(), audit);
        var result = service.update("2026.08.17", "PUBLISHED", java.util.Map.of(
                "en", java.util.Map.of("example", java.util.Map.of("request", "POST /v1/jobs", "response", "200"),
                        "endpoints", java.util.List.of(java.util.Map.of("method", "POST", "path", "/v1/jobs")),
                        "events", java.util.List.of("job.completed"))),0L,"Publish reviewed developer docs");
        assertThat(result.getCode()).isZero();
        verify(config).upsertAdminValue(eq("developer.docs.published"), contains("2026.08.17"), eq("JSON"), eq("published_content"), anyString());
        verify(audit).recordRequired(any());
    }

    @Test
    void staleAdminRevisionCannotOverwritePublishedDocument() {
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(config.activeValueForUpdate("developer.docs.published")).thenReturn(Optional.of("{\"revision\":2}"));
        AuditLogService audit=mock(AuditLogService.class);
        var service=new PublishedDeveloperDocsService(config,new MockEnvironment(),audit);
        var locales=java.util.Map.<String,Object>of("en",java.util.Map.of(
                "example",java.util.Map.of("request","GET /v1","response","200"),
                "endpoints",java.util.List.of(java.util.Map.of("method","GET","path","/v1")),
                "events",java.util.List.of("job.completed")));
        assertThat(service.update("v3","PUBLISHED",locales,1L,"Publish reviewed content").getCode()).isEqualTo(409);
        verify(config,never()).upsertAdminValue(anyString(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(audit);
    }
}
