package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppSupportService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppConversationReceiptControllerTest {
    private final AppSupportService service = mock(AppSupportService.class);
    private final ProductionSupportPathGuard productionPathGuard = mock(ProductionSupportPathGuard.class);
    private final AppConversationReceiptController controller = new AppConversationReceiptController(service, productionPathGuard);

    @Test
    void authenticatedUserMarksOwnConversationReadAndPublishesReceipt() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1001", null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        when(service.markConversationRead(1001L, "CV-1", 1L, "OPEN", 3L)).thenReturn(ApiResult.ok(null));

        ApiResult<Void> result = controller.markReadReceipt("CV-1", new AppConversationReceiptRequest(1L, "OPEN", 3L), authentication);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNull();

        verify(service).markConversationRead(1001L, "CV-1", 1L, "OPEN", 3L);
    }

    @Test
    void adminSubjectCannotForgeAUserReceipt() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        assertThat(controller.markReadReceipt("CV-1", new AppConversationReceiptRequest(1L, "OPEN", 3L), authentication).getCode()).isEqualTo(403);

        verify(service, never()).markConversationRead(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void isolatedProfileBlocksReceiptBeforeTheSharedSupportService() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken("1001", null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        org.mockito.Mockito.doThrow(new ffdd.opsconsole.shared.exception.BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionPathGuard).requireAllowed(1001L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.markReadReceipt("CV-1", null, authentication))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        verify(service, never()).markConversationRead(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
