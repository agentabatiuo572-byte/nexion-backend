package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsNotificationCampaignService;
import ffdd.opsconsole.content.domain.NotificationCampaignOverview;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
import ffdd.opsconsole.content.dto.NotificationCampaignActionRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/campaigns")
@RequiredArgsConstructor
public class OpsNotificationCampaignController {
    private final OpsNotificationCampaignService campaignService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('content_i3_read')")
    public ApiResult<NotificationCampaignOverview> overview() {
        return campaignService.overview();
    }

    @PostMapping
    // 新建 campaign：常规写
    @PreAuthorize("hasAuthority('content_i3_write')")
    public ApiResult<NotificationCampaignRow> createCampaign(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NotificationCampaignCreateRequest request) {
        return campaignService.createCampaign(idempotencyKey, request);
    }

    @PatchMapping("/{campaignNo}/draft")
    @PreAuthorize("hasAuthority('content_i3_write')")
    public ApiResult<NotificationCampaignRow> updateDraft(
            @PathVariable String campaignNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NotificationCampaignDraftRequest request) {
        return campaignService.updateDraft(campaignNo, idempotencyKey, request);
    }

    @PostMapping("/{campaignNo}/schedule")
    @PreAuthorize("hasAuthority('content_i3_write')")
    public ApiResult<NotificationCampaignRow> scheduleCampaign(
            @PathVariable String campaignNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NotificationCampaignActionRequest request) {
        return campaignService.scheduleCampaign(campaignNo, idempotencyKey, request);
    }

    @PostMapping("/{campaignNo}/send-now")
    @PreAuthorize("hasAuthority('content_i3_write')")
    public ApiResult<NotificationCampaignRow> sendNow(
            @PathVariable String campaignNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NotificationCampaignActionRequest request) {
        return campaignService.sendNow(campaignNo, idempotencyKey, request);
    }

    @PostMapping("/{campaignNo}/cancel")
    @PreAuthorize("hasAuthority('content_i3_write')")
    public ApiResult<NotificationCampaignRow> cancelScheduled(
            @PathVariable String campaignNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NotificationCampaignActionRequest request) {
        return campaignService.cancelScheduled(campaignNo, idempotencyKey, request);
    }

    @PatchMapping("/caps/{tier}")
    // HIGH：CAP 容量闸调整，影响合规通知可见性
    @PreAuthorize("hasAuthority('content_i3_cap_adjust')")
    public ApiResult<NotificationCapRuleView> updateCapRule(
            @PathVariable String tier,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NotificationCapUpdateRequest request) {
        return campaignService.updateCapRule(tier, idempotencyKey, request);
    }
}
