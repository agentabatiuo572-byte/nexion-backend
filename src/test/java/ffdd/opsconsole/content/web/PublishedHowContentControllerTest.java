package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.application.PublishedHowContentService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublishedHowContentControllerTest {
    private final PublishedHowContentService service = mock(PublishedHowContentService.class);
    private final PublishedHowContentController controller = new PublishedHowContentController(service);

    @Test
    void publicRouteForwardsContentKeyAndLocale() {
        when(service.publicContent("team-binary-how", "zh-CN")).thenReturn(ApiResult.ok(Map.of("contentKey", "team-binary-how")));
        assertThat(controller.published("team-binary-how", "zh-CN").getData()).containsEntry("contentKey", "team-binary-how");
        verify(service).publicContent("team-binary-how", "zh-CN");
    }

    @Test
    void missingRequestIsRejectedBeforeUpdate() {
        assertThat(controller.update(null).getCode()).isEqualTo(422);
        verifyNoInteractions(service);
    }
}
