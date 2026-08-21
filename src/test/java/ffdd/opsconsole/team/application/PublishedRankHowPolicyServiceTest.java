package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublishedRankHowPolicyServiceTest {
    @Test
    void returnsPublishedStructuredPolicyForRequestedLocale() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("team.rank_how.published")).thenReturn(Optional.of("""
                {"version":"r3","status":"PUBLISHED","locales":{"en":{"hero":"Rank policy","sections":[{"id":"qualification","title":"Qualification","body":"Server evaluates each step.","order":1}]},"vi":{"hero":"Chinh sach","sections":[{"id":"qualification","title":"Dieu kien","body":"May chu danh gia.","order":1}]}}}
                """));
        var result = new PublishedRankHowPolicyService(config, new MockEnvironment(), mock(AuditLogService.class)).publicPolicy("vi");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("version", "r3").containsEntry("locale", "vi");
        assertThat(result.getData().get("sections")).asList().hasSize(1);
    }

    @Test
    void missingPolicyFailsClosedInsteadOfFallingBackToLocalNarrative() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("team.rank_how.published")).thenReturn(Optional.empty());
        var result = new PublishedRankHowPolicyService(config, new MockEnvironment(), mock(AuditLogService.class)).publicPolicy("en");
        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("RANK_HOW_POLICY_UNAVAILABLE");
    }

    @Test
    void adminUpdateRejectsUnstructuredSections() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        var service = new PublishedRankHowPolicyService(config, new MockEnvironment(), mock(AuditLogService.class));
        var result = service.update("r3", "PUBLISHED", java.util.Map.of("en", java.util.Map.of("hero", "x", "sections", java.util.List.of(java.util.Map.of("title", "missing id")))),0L,"Publish reviewed rank policy");
        assertThat(result.getCode()).isEqualTo(422);
        verifyNoInteractions(config);
    }

    @Test
    void adminUpdateRejectsDuplicateIdsAndUnsafeOrders() {
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        var service=new PublishedRankHowPolicyService(config,new MockEnvironment(),mock(AuditLogService.class));
        var duplicate=java.util.List.of(
                java.util.Map.of("id","same","title","A","body","A","order",1),
                java.util.Map.of("id","same","title","B","body","B","order",2));
        assertThat(service.update("r4","PUBLISHED",java.util.Map.of("en",java.util.Map.of("hero","h","sections",duplicate)),0L,"Publish reviewed rank policy").getCode()).isEqualTo(422);
        var unsafe=java.util.List.of(java.util.Map.of("id","x","title","A","body","A","order",1.5));
        assertThat(service.update("r4","PUBLISHED",java.util.Map.of("en",java.util.Map.of("hero","h","sections",unsafe)),0L,"Publish reviewed rank policy").getCode()).isEqualTo(422);
        verifyNoInteractions(config);
    }
}
