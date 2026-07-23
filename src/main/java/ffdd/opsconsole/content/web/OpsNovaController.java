package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsNovaService;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaOverview;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialEventPage;
import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaSocialRenderedEventView;
import ffdd.opsconsole.content.domain.NovaSocialSyncResult;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.dto.NovaChannelStatusRequest;
import ffdd.opsconsole.content.dto.NovaChannelUpsertRequest;
import ffdd.opsconsole.content.dto.NovaDeleteRequest;
import ffdd.opsconsole.content.dto.NovaDistributionUpdateRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventStatusRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventSyncRequest;
import ffdd.opsconsole.content.dto.NovaTemplateCreateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/nova")
@RequiredArgsConstructor
public class OpsNovaController {
    private final OpsNovaService novaService;
    private final AdminIdempotencyService idempotencyService;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> executeCommand(
            String scope,
            String idempotencyKey,
            Object request,
            Supplier<ApiResult<T>> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        return (ApiResult<T>) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                requestHash(scope + ":" + String.valueOf(request)),
                ApiResult.class,
                (Supplier) action);
    }

    private String requestHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('content_i2_read')")
    public ApiResult<NovaOverview> overview() {
        return novaService.overview();
    }

    @PostMapping("/channels")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaChannelView> createChannel(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaChannelUpsertRequest request) {
        return executeCommand("I2_NOVA_CHANNEL_CREATE", idempotencyKey, request,
                () -> novaService.createChannel(idempotencyKey, request));
    }

    @PatchMapping("/channels/{key}")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaChannelView> updateChannel(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaChannelUpsertRequest request) {
        return executeCommand("I2_NOVA_CHANNEL_UPDATE:" + key, idempotencyKey, request,
                () -> novaService.updateChannel(key, idempotencyKey, request));
    }

    @PatchMapping("/channels/{key}/status")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaChannelView> updateChannelStatus(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaChannelStatusRequest request) {
        return executeCommand("I2_NOVA_CHANNEL_STATUS:" + key, idempotencyKey, request,
                () -> novaService.updateChannelStatus(key, idempotencyKey, request));
    }

    @DeleteMapping("/channels/{key}")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<Void> deleteChannel(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaDeleteRequest request) {
        return executeCommand("I2_NOVA_CHANNEL_DELETE:" + key, idempotencyKey, request,
                () -> novaService.deleteChannel(key, idempotencyKey, request));
    }

    @PostMapping("/templates")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaTemplateView> createTemplate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaTemplateCreateRequest request) {
        return executeCommand("I2_NOVA_TEMPLATE_CREATE", idempotencyKey, request,
                () -> novaService.createTemplate(idempotencyKey, request));
    }

    @PatchMapping("/templates/{channel}")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaTemplateView> updateTemplate(
            @PathVariable String channel,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaTemplateCreateRequest request) {
        return executeCommand("I2_NOVA_TEMPLATE_UPDATE:" + channel, idempotencyKey, request,
                () -> novaService.updateTemplate(channel, idempotencyKey, request));
    }

    @DeleteMapping("/templates/{channel}")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<Void> deleteTemplate(
            @PathVariable String channel,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaDeleteRequest request) {
        return executeCommand("I2_NOVA_TEMPLATE_DELETE:" + channel, idempotencyKey, request,
                () -> novaService.deleteTemplate(channel, idempotencyKey, request));
    }

    @PatchMapping("/templates/{channel}/status")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaTemplateView> updateTemplateStatus(
            @PathVariable String channel,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaTemplateStatusRequest request) {
        return executeCommand("I2_NOVA_TEMPLATE_STATUS:" + channel, idempotencyKey, request,
                () -> novaService.updateTemplateStatus(channel, idempotencyKey, request));
    }

    @PatchMapping("/social-distribution")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<List<NovaSocialDistributionItem>> updateDistribution(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaDistributionUpdateRequest request) {
        return executeCommand("I2_NOVA_SOCIAL_DISTRIBUTION", idempotencyKey, request,
                () -> novaService.updateDistribution(idempotencyKey, request));
    }

    @GetMapping("/social-events")
    @PreAuthorize("hasAuthority('content_i2_read')")
    public ApiResult<NovaSocialEventPage> socialEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return novaService.socialEventPage(eventType, status, page, pageSize);
    }

    @PostMapping("/social-events/sync")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaSocialSyncResult> syncSocialEvents(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaSocialEventSyncRequest request) {
        return executeCommand("I2_NOVA_SOCIAL_SYNC", idempotencyKey, request,
                () -> novaService.syncSocialEvents(idempotencyKey, request));
    }

    @PatchMapping("/social-events/{id}/status")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<NovaSocialEventView> updateSocialEventStatus(
            @PathVariable long id,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaSocialEventStatusRequest request) {
        return executeCommand("I2_NOVA_SOCIAL_EVENT_STATUS:" + id, idempotencyKey, request,
                () -> novaService.updateSocialEventStatus(id, idempotencyKey, request));
    }

    @DeleteMapping("/social-events/{id}")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<Void> deleteSocialEvent(
            @PathVariable long id,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaDeleteRequest request) {
        return executeCommand("I2_NOVA_SOCIAL_EVENT_DELETE:" + id, idempotencyKey, request,
                () -> novaService.deleteSocialEvent(id, idempotencyKey, request));
    }

    @PostMapping("/social-events/expire")
    @PreAuthorize("hasAuthority('content_i2_write')")
    public ApiResult<Map<String, Integer>> expireSocialEvents(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NovaDeleteRequest request) {
        return executeCommand("I2_NOVA_SOCIAL_EXPIRE", idempotencyKey, request,
                () -> novaService.expireSocialEvents(idempotencyKey, request));
    }

    @GetMapping("/social-events/sample")
    @PreAuthorize("hasAuthority('content_i2_read')")
    public ApiResult<NovaSocialRenderedEventView> sampleSocialEvent(
            @RequestParam(defaultValue = "ZH") String language) {
        return novaService.sampleSocialEvent(language);
    }
}
