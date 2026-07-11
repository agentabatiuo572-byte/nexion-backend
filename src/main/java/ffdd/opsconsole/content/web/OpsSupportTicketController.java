package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsSupportTicketService;
import ffdd.opsconsole.content.domain.SupportTicketDetail;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.SupportTicketAssigneeRequest;
import ffdd.opsconsole.content.dto.SupportTicketCreateRequest;
import ffdd.opsconsole.content.dto.SupportTicketPriorityRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.content.dto.SupportTicketReplyRequest;
import ffdd.opsconsole.content.dto.SupportTicketStatusRequest;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.Map;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/tickets")
@RequiredArgsConstructor
public class OpsSupportTicketController {
    private final OpsSupportTicketService ticketService;

    // 工单总览 — M2 工单台 读
    @PreAuthorize("hasAuthority('service_m2_read')")
    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ticketService.overview();
    }

    // 工单负载配置查询 — M2 工单台 读
    @PreAuthorize("hasAuthority('service_m2_read')")
    @GetMapping("/load-config")
    public ApiResult<Map<String, Object>> loadConfig() {
        return ticketService.loadConfig();
    }

    // 工单负载配置更新 — M2 工单台 写
    @PreAuthorize("hasAuthority('service_m2_write')")
    @PatchMapping("/load-config")
    public ApiResult<Map<String, Object>> updateLoadConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportLoadConfigUpdateRequest request) {
        return ticketService.updateLoadConfig(idempotencyKey, request);
    }

    // 工单负载手动均衡 — M2 工单台 写
    @PreAuthorize("hasAuthority('service_m2_write')")
    @PostMapping("/load-config/rebalance")
    public ApiResult<Map<String, Object>> rebalanceLoad(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportLoadRebalanceRequest request) {
        return ticketService.rebalanceLoad(idempotencyKey, request);
    }

    // 工单队列/搜索/分页 — M2 工单台 读
    @PreAuthorize("hasAuthority('service_m2_read')")
    @GetMapping
    public ApiResult<PageResult<SupportTicketView>> tickets(SupportTicketQueryRequest request) {
        return ticketService.tickets(request);
    }

    // 新建工单 — M2 工单台 写
    @PreAuthorize("hasAuthority('service_m2_write')")
    @PostMapping
    public ApiResult<SupportTicketDetail> create(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportTicketCreateRequest request) {
        return ticketService.create(idempotencyKey, request);
    }

    // 工单详情 — M2 工单台 读
    @PreAuthorize("hasAuthority('service_m2_read')")
    @GetMapping("/{ticketNo}")
    public ApiResult<SupportTicketDetail> detail(@PathVariable String ticketNo) {
        return ticketService.detail(ticketNo);
    }

    // 工单回复 — M2 工单台 写
    @PreAuthorize("hasAuthority('service_m2_write')")
    @PostMapping("/{ticketNo}/replies")
    public ApiResult<SupportTicketDetail> reply(
            @PathVariable String ticketNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportTicketReplyRequest request) {
        return ticketService.reply(ticketNo, idempotencyKey, request);
    }

    // 工单状态流转/关闭/重开 — M2 工单台 写
    @PreAuthorize("hasAuthority('service_m2_write')")
    @PatchMapping("/{ticketNo}/status")
    public ApiResult<SupportTicketDetail> updateStatus(
            @PathVariable String ticketNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportTicketStatusRequest request) {
        return ticketService.updateStatus(ticketNo, idempotencyKey, request);
    }

    // 工单优先级调整 — M2 工单台 写
    @PreAuthorize("hasAuthority('service_m2_write')")
    @PatchMapping("/{ticketNo}/priority")
    public ApiResult<SupportTicketDetail> updatePriority(
            @PathVariable String ticketNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportTicketPriorityRequest request) {
        return ticketService.updatePriority(ticketNo, idempotencyKey, request);
    }

    // 工单转交 — M2 工单台 写
    @PreAuthorize("hasAuthority('service_m2_write')")
    @PatchMapping("/{ticketNo}/assignee")
    public ApiResult<SupportTicketDetail> assign(
            @PathVariable String ticketNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportTicketAssigneeRequest request) {
        return ticketService.assign(ticketNo, idempotencyKey, request);
    }
}
