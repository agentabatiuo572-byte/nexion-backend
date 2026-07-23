package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppI18nService;
import ffdd.opsconsole.content.domain.AppI18nBundle;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppI18nControllerTest {
    private final AppI18nService service = mock(AppI18nService.class);
    private final AppI18nController controller = new AppI18nController(service);

    @Test
    void delegatesCanonicalAndAliasPathsToSameService() {
        var bundle = new AppI18nBundle("home", "zh-CN", Map.of("home.title", "首页"), true);
        when(service.namespace("home", "zh")).thenReturn(ApiResult.ok(bundle));

        assertThat(controller.namespace("home", "zh").getData()).isEqualTo(bundle);
        verify(service).namespace("home", "zh");
    }
}
