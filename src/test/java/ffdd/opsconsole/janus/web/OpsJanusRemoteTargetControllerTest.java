package ffdd.opsconsole.janus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.janus.application.OpsJanusRemoteTargetService;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetCreateRequest;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetDisableRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsJanusRemoteTargetControllerTest {
    private final OpsJanusRemoteTargetService service = mock(OpsJanusRemoteTargetService.class);
    private final OpsJanusRemoteTargetController controller = new OpsJanusRemoteTargetController(service);

    @Test
    void delegatesStableIdempotencyAndCasFields() {
        JanusRemoteTargetCreateRequest create = new JanusRemoteTargetCreateRequest(
                "finance-main", "财务主站", "https://approved.example/app",
                "risk-owner", 2, "批准新版本用于灰度切换", "影响与迁移安排均已确认");
        JanusRemoteTargetDisableRequest disable = new JanusRemoteTargetDisableRequest(
                7L, 9L, "目标证书异常需要停用", "未领取命令必须取消且策略待迁移");

        controller.list();
        controller.create("idem-create", create);
        controller.disable("finance-main", 3, "idem-disable", disable);

        verify(service).list();
        verify(service).create("idem-create", create);
        verify(service).disable("finance-main", 3, "idem-disable", disable);
    }

    @Test
    void readAndWriteAuthoritiesAreSeparated() throws Exception {
        Method list = OpsJanusRemoteTargetController.class.getMethod("list");
        Method create = OpsJanusRemoteTargetController.class.getMethod(
                "create", String.class, JanusRemoteTargetCreateRequest.class);
        Method disable = OpsJanusRemoteTargetController.class.getMethod(
                "disable", String.class, int.class, String.class, JanusRemoteTargetDisableRequest.class);

        assertThat(list.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('risk_k6_read')");
        assertThat(create.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('risk_k6_target_manage')");
        assertThat(disable.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('risk_k6_target_manage')");
    }
}
