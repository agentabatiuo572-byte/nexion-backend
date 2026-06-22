package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsSessionTemplateService;
import ffdd.opsconsole.content.dto.SessionAdvisorPolicyUpdateRequest;
import ffdd.opsconsole.content.dto.SessionCategoryToggleRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateCreateRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateStatusRequest;
import ffdd.opsconsole.content.dto.SessionScriptAudienceRequest;
import ffdd.opsconsole.content.dto.SessionScriptCreateRequest;
import ffdd.opsconsole.content.dto.SessionScriptStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import org.junit.jupiter.api.Test;

class OpsSessionTemplateControllerTest {
    private final OpsSessionTemplateService templateService = mock(OpsSessionTemplateService.class);
    private final OpsSessionTemplateController controller = new OpsSessionTemplateController(templateService);

    @Test
    void overviewDelegates() {
        when(templateService.overview()).thenReturn(ApiResult.ok(null));

        assertThat(controller.overview().getCode()).isZero();

        verify(templateService).overview();
    }

    @Test
    void categoryDelegatesWithIdempotencyHeader() {
        SessionCategoryToggleRequest request = new SessionCategoryToggleRequest(false, "Marina K.", "暂停入口");
        when(templateService.updateCategory("advisor", "idem-m5-cat", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateCategory("advisor", "idem-m5-cat", request).getCode()).isZero();

        verify(templateService).updateCategory("advisor", "idem-m5-cat", request);
    }

    @Test
    void policyDelegatesWithIdempotencyHeader() {
        SessionAdvisorPolicyUpdateRequest request = new SessionAdvisorPolicyUpdateRequest("48", "Marina K.", "调整冷却");
        when(templateService.updateAdvisorPolicy("cooldownHours", "idem-m5-policy", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateAdvisorPolicy("cooldownHours", "idem-m5-policy", request).getCode()).isZero();

        verify(templateService).updateAdvisorPolicy("cooldownHours", "idem-m5-policy", request);
    }

    @Test
    void scriptCreateDelegatesWithIdempotencyHeader() {
        SessionScriptCreateRequest request = new SessionScriptCreateRequest("升级", "升级话术", "/store", "全量", "draft", "Marina K.", "新增话术");
        when(templateService.createScript("idem-m5-script", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createScript("idem-m5-script", request).getCode()).isZero();

        verify(templateService).createScript("idem-m5-script", request);
    }

    @Test
    void scriptStatusDelegatesWithIdempotencyHeader() {
        SessionScriptStatusRequest request = new SessionScriptStatusRequest("draft", "Marina K.", "下架话术");
        when(templateService.updateScriptStatus("AS-001", "idem-m5-status", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateScriptStatus("AS-001", "idem-m5-status", request).getCode()).isZero();

        verify(templateService).updateScriptStatus("AS-001", "idem-m5-status", request);
    }

    @Test
    void scriptAudienceDelegatesWithIdempotencyHeader() {
        SessionScriptAudienceRequest request = new SessionScriptAudienceRequest("注册 ≤14 天", "Marina K.", "圈定新户");
        when(templateService.updateScriptAudience("AS-001", "idem-m5-audience", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateScriptAudience("AS-001", "idem-m5-audience", request).getCode()).isZero();

        verify(templateService).updateScriptAudience("AS-001", "idem-m5-audience", request);
    }

    @Test
    void templateCreateAndStatusDelegateWithIdempotencyHeader() {
        SessionReplyTemplateCreateRequest create = new SessionReplyTemplateCreateRequest("support", "收到,我先核对。", "draft", "Marina K.", "新增模板");
        SessionReplyTemplateStatusRequest status = new SessionReplyTemplateStatusRequest("published", "Marina K.", "发布模板");
        when(templateService.createReplyTemplate("idem-m5-template", create)).thenReturn(ApiResult.ok(null));
        when(templateService.updateReplyTemplateStatus("RT-S1", "idem-m5-template-status", status)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createReplyTemplate("idem-m5-template", create).getCode()).isZero();
        assertThat(controller.updateReplyTemplateStatus("RT-S1", "idem-m5-template-status", status).getCode()).isZero();

        verify(templateService).createReplyTemplate("idem-m5-template", create);
        verify(templateService).updateReplyTemplateStatus("RT-S1", "idem-m5-template-status", status);
    }
}
