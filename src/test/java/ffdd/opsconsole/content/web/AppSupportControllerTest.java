package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppSupportService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppSupportControllerTest {
    private final AppSupportService service = mock(AppSupportService.class);
    private final ProductionSupportPathGuard productionPathGuard = mock(ProductionSupportPathGuard.class);
    private final AppSupportController controller = new AppSupportController(service, productionPathGuard);

    @Test
    void adminSubjectCannotReadAnotherUsersSupportData() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        auth.setDetails(Map.of("subjectType", "ADMIN"));

        assertThat(controller.tickets(null, null, null, auth).getCode()).isEqualTo(403);
        verify(service, never()).tickets(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticatedUserCanReadOnlyTheirTicketProjection() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("42", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        when(service.tickets(42L, "open", 1L, 50L))
                .thenReturn(ApiResult.ok(new PageResult<>(0, 1, 50, List.of())));

        assertThat(controller.tickets("open", 1L, 50L, auth).getCode()).isZero();
        verify(service).tickets(42L, "open", 1L, 50L);
    }

    @Test
    void authenticatedUserAcknowledgesOnlyTheirTicketHeader() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("42", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        AppSupportService.CloseRequest request = new AppSupportService.CloseRequest("OPEN", 3L);
        when(service.markTicketRead(42L, "TK-1", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.markTicketRead("TK-1", request, auth).getCode()).isZero();
        verify(service).markTicketRead(42L, "TK-1", request);
    }

    @Test
    void authenticatedUserCanReadOnlyTheirOwnCommandRecoveryProjection() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("42", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        when(service.commandResult(42L, "support-command-123"))
                .thenReturn(ApiResult.ok(Map.of("resultType", "ticket", "result", Map.of())));

        assertThat(controller.commandResult("support-command-123", auth).getCode()).isZero();
        verify(service).commandResult(42L, "support-command-123");
    }

    @Test
    void authenticatedUserCanReadM5ConversationCategoryAvailability() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("42", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        var rows = List.of(new AppSupportService.ConversationCategoryAvailability("advisor", false));
        when(service.conversationCategories(42L)).thenReturn(ApiResult.ok(rows));

        assertThat(controller.conversationCategories(auth).getData()).isEqualTo(rows);
        verify(service).conversationCategories(42L);
    }

    @Test
    void isolatedProfileRejectsAProductionSupportPathBeforeAnySharedServiceReadOrWrite() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("42", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        org.mockito.Mockito.doThrow(new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionPathGuard).requireAllowed(42L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.tickets(null, null, null, auth))
                .isInstanceOf(BizException.class);
        verify(service, never()).tickets(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
