package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsTrustDisclosureService;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureOverview;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.TrustSectionVersionView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.content.dto.TrustSectionDraftRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/trust-disclosure")
@RequiredArgsConstructor
public class OpsTrustDisclosureController {
    private final OpsTrustDisclosureService trustDisclosureService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('content_i4_read')")
    public ApiResult<TrustDisclosureOverview> overview() {
        return trustDisclosureService.overview();
    }

    @PostMapping("/trust-sections/{sectionKey}/publish")
    // HIGH：信任版块发布
    @PreAuthorize("hasAuthority('content_i4_trust_section_manage')")
    public ApiResult<TrustSectionView> publishSection(
            @PathVariable String sectionKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TrustSectionPublishRequest request) {
        return trustDisclosureService.publishSection(sectionKey, idempotencyKey, request);
    }

    @PostMapping("/trust-sections/{sectionKey}/versions")
    @PreAuthorize("hasAuthority('content_i4_trust_section_manage')")
    public ApiResult<TrustSectionVersionView> createSectionDraft(
            @PathVariable String sectionKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TrustSectionDraftRequest request) {
        return trustDisclosureService.createSectionDraft(sectionKey, idempotencyKey, request);
    }

    @PatchMapping("/trust-sections/{sectionKey}/versions/{version}")
    @PreAuthorize("hasAuthority('content_i4_trust_section_manage')")
    public ApiResult<TrustSectionVersionView> updateSectionDraft(
            @PathVariable String sectionKey,
            @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TrustSectionDraftRequest request) {
        return trustDisclosureService.updateSectionDraft(sectionKey, version, idempotencyKey, request);
    }

    @DeleteMapping("/trust-sections/{sectionKey}/versions/{version}")
    @PreAuthorize("hasAuthority('content_i4_trust_section_manage')")
    public ApiResult<Void> deleteSectionDraft(
            @PathVariable String sectionKey,
            @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TrustDisclosureActionRequest request) {
        return trustDisclosureService.deleteSectionDraft(sectionKey, version, idempotencyKey, request);
    }

    @PostMapping("/trust-sections/{sectionKey}/rollback")
    // HIGH：信任版块回滚
    @PreAuthorize("hasAuthority('content_i4_trust_section_manage')")
    public ApiResult<TrustSectionView> rollbackSection(
            @PathVariable String sectionKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TrustSectionRollbackRequest request) {
        return trustDisclosureService.rollbackSection(sectionKey, idempotencyKey, request);
    }

    @PostMapping("/trust-sections/{sectionKey}/archive")
    // HIGH：信任版块下架
    @PreAuthorize("hasAuthority('content_i4_trust_section_manage')")
    public ApiResult<TrustSectionView> archiveSection(
            @PathVariable String sectionKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TrustDisclosureActionRequest request) {
        return trustDisclosureService.archiveSection(sectionKey, idempotencyKey, request);
    }

    @PatchMapping("/disclosures/{jurisdiction}/draft")
    @PreAuthorize("hasAuthority('content_i4_write')")
    public ApiResult<DisclosureDraftView> saveDisclosureDraft(
            @PathVariable String jurisdiction,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DisclosureDraftRequest request) {
        return trustDisclosureService.saveDisclosureDraft(jurisdiction, idempotencyKey, request);
    }

    @PostMapping("/disclosures/{jurisdiction}/publish")
    // HIGH：披露版本发布，触发用户 re-ack 重签字
    @PreAuthorize("hasAuthority('content_i4_disclosure_publish')")
    public ApiResult<DisclosureJurisdictionView> publishDisclosure(
            @PathVariable String jurisdiction,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DisclosureDraftRequest request) {
        return trustDisclosureService.publishDisclosure(jurisdiction, idempotencyKey, request);
    }

    @PutMapping("/disclosures/matrix/{jurisdiction}")
    @PreAuthorize("hasAuthority('content_i4_write')")
    public ApiResult<TrustDisclosureOverview> configureMatrix(
            @PathVariable String jurisdiction,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DisclosureMatrixRequest request) {
        return trustDisclosureService.configureMatrix(jurisdiction, idempotencyKey, request);
    }

    @DeleteMapping("/disclosures/matrix/{jurisdiction}")
    @PreAuthorize("hasAuthority('content_i4_write')")
    public ApiResult<TrustDisclosureOverview> archiveMatrix(
            @PathVariable String jurisdiction,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TrustDisclosureActionRequest request) {
        return trustDisclosureService.archiveMatrix(jurisdiction, idempotencyKey, request);
    }

    @PatchMapping("/disclosures/gated-actions")
    // HIGH：受限动作范围调整，放松资金类合规拦截（amplifies）
    @PreAuthorize("hasAuthority('content_i4_gate_adjust')")
    public ApiResult<TrustDisclosureOverview> updateGateScope(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DisclosureGateUpdateRequest request) {
        return trustDisclosureService.updateGateScope(idempotencyKey, request);
    }
}
