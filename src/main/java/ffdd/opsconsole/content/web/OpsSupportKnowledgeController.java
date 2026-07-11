package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsSupportKnowledgeService;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeOverview;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.content.dto.SupportFaqStatusRequest;
import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportKnowledgeDeleteRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/knowledge")
@RequiredArgsConstructor
public class OpsSupportKnowledgeController {
    private final OpsSupportKnowledgeService knowledgeService;

    // 知识库总览/FAQ内容池/SLA矩阵 — M4 知识库与SLA 读
    @PreAuthorize("hasAuthority('service_m4_read')")
    @GetMapping("/overview")
    public ApiResult<SupportKnowledgeOverview> overview() {
        return knowledgeService.overview();
    }

    // FAQ 新增 — M4 知识库与SLA 写
    @PreAuthorize("hasAuthority('service_m4_write')")
    @PostMapping("/faqs")
    public ApiResult<SupportFaqView> createFaq(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportFaqUpsertRequest request) {
        return knowledgeService.createFaq(idempotencyKey, request);
    }

    // FAQ 编辑 — M4 知识库与SLA 写
    @PreAuthorize("hasAuthority('service_m4_write')")
    @PatchMapping("/faqs/{faqId}")
    public ApiResult<SupportFaqView> updateFaq(
            @PathVariable String faqId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportFaqUpsertRequest request) {
        return knowledgeService.updateFaq(faqId, idempotencyKey, request);
    }

    // FAQ 发布/上下架 — M4 知识库与SLA 写
    @PreAuthorize("hasAuthority('service_m4_write')")
    @PatchMapping("/faqs/{faqId}/status")
    public ApiResult<SupportFaqView> updateFaqStatus(
            @PathVariable String faqId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportFaqStatusRequest request) {
        return knowledgeService.updateFaqStatus(faqId, idempotencyKey, request);
    }

    // FAQ 删除 — M4 知识库与SLA 写
    @PreAuthorize("hasAuthority('service_m4_write')")
    @DeleteMapping("/faqs/{faqId}")
    public ApiResult<Void> deleteFaq(
            @PathVariable String faqId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportKnowledgeDeleteRequest request) {
        return knowledgeService.deleteFaq(faqId, idempotencyKey, request);
    }

    // SLA 矩阵编辑 — M4 知识库与SLA 写
    @PreAuthorize("hasAuthority('service_m4_write')")
    @PatchMapping("/sla/{category}")
    public ApiResult<SupportSlaView> updateSla(
            @PathVariable String category,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportSlaUpdateRequest request) {
        return knowledgeService.updateSla(category, idempotencyKey, request);
    }
}
