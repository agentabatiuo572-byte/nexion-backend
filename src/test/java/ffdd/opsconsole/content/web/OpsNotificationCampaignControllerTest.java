package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsNotificationCampaignService;
import ffdd.opsconsole.content.dto.NotificationCampaignActionRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OpsNotificationCampaignControllerTest {
    private final OpsNotificationCampaignService campaignService = mock(OpsNotificationCampaignService.class);
    private final OpsNotificationCampaignController controller = new OpsNotificationCampaignController(campaignService);

    @Test
    void overviewDelegatesToService() {
        when(campaignService.overview()).thenReturn(ApiResult.ok(null));

        assertThat(controller.overview().getCode()).isZero();

        verify(campaignService).overview();
    }

    @Test
    void createDelegatesWithIdempotencyHeader() {
        NotificationCampaignCreateRequest request = createRequest();
        when(campaignService.createCampaign("idem-i3-create", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createCampaign("idem-i3-create", request).getCode()).isZero();

        verify(campaignService).createCampaign("idem-i3-create", request);
    }

    @Test
    void updateDraftDelegatesWithIdempotencyHeader() {
        NotificationCampaignDraftRequest request = draftRequest();
        when(campaignService.updateDraft("CMP-2619", "idem-i3-draft", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateDraft("CMP-2619", "idem-i3-draft", request).getCode()).isZero();

        verify(campaignService).updateDraft("CMP-2619", "idem-i3-draft", request);
    }

    @Test
    void scheduleDelegatesWithIdempotencyHeader() {
        NotificationCampaignActionRequest request = actionRequest();
        when(campaignService.scheduleCampaign("CMP-2619", "idem-i3-schedule", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.scheduleCampaign("CMP-2619", "idem-i3-schedule", request).getCode()).isZero();

        verify(campaignService).scheduleCampaign("CMP-2619", "idem-i3-schedule", request);
    }

    @Test
    void sendNowDelegatesWithIdempotencyHeader() {
        NotificationCampaignActionRequest request = actionRequest();
        when(campaignService.sendNow("CMP-2618", "idem-i3-send", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.sendNow("CMP-2618", "idem-i3-send", request).getCode()).isZero();

        verify(campaignService).sendNow("CMP-2618", "idem-i3-send", request);
    }

    @Test
    void cancelDelegatesWithIdempotencyHeader() {
        NotificationCampaignActionRequest request = actionRequest();
        when(campaignService.cancelScheduled("CMP-2618", "idem-i3-cancel", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.cancelScheduled("CMP-2618", "idem-i3-cancel", request).getCode()).isZero();

        verify(campaignService).cancelScheduled("CMP-2618", "idem-i3-cancel", request);
    }

    @Test
    void updateCapDelegatesWithIdempotencyHeader() {
        NotificationCapUpdateRequest request = new NotificationCapUpdateRequest("180 条", "Marina K.", "调整普通容量");
        when(campaignService.updateCapRule("normal", "idem-i3-cap", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateCapRule("normal", "idem-i3-cap", request).getCode()).isZero();

        verify(campaignService).updateCapRule("normal", "idem-i3-cap", request);
    }

    private static NotificationCampaignCreateRequest createRequest() {
        return new NotificationCampaignCreateRequest(
                "July fee notice",
                "7 月费率说明",
                "7 月提现费率将按链上成本动态调整。",
                "normal",
                "全量",
                new BigDecimal("500"),
                "Marina K.",
                "新增通知草稿");
    }

    private static NotificationCampaignDraftRequest draftRequest() {
        return new NotificationCampaignDraftRequest(
                "7 月费率说明公告",
                "费率说明已补充链上成本解释。",
                "high",
                "全量",
                "06-21 09:00 排期",
                new BigDecimal("1200"),
                "Marina K.",
                "更新通知草稿");
    }

    private static NotificationCampaignActionRequest actionRequest() {
        return new NotificationCampaignActionRequest("06-20 10:00 排期", "Marina K.", "通知状态变更");
    }
}
