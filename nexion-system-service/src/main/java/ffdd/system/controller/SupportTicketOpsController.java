package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.security.AuthHeaders;
import ffdd.system.dto.SupportTicketAttachmentResponse;
import ffdd.system.dto.SupportTicketOpsUpdateRequest;
import ffdd.system.dto.SupportTicketReplyRequest;
import ffdd.system.dto.SupportTicketResponse;
import ffdd.system.service.SupportTicketService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/system/support/ops/tickets")
public class SupportTicketOpsController {
    private final SupportTicketService supportTicketService;

    public SupportTicketOpsController(SupportTicketService supportTicketService) {
        this.supportTicketService = supportTicketService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<PageResult<SupportTicketResponse>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long assignedAdminId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(supportTicketService.pageForOps(
                status, category, priority, userId, assignedAdminId, pageNum, pageSize));
    }

    @GetMapping("/{ticketNo}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<SupportTicketResponse> detail(@PathVariable String ticketNo) {
        return ApiResult.ok(supportTicketService.detailForOps(ticketNo));
    }

    @PostMapping("/attachments")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<SupportTicketAttachmentResponse> uploadAttachment(@RequestPart("file") MultipartFile file) {
        return ApiResult.ok(supportTicketService.uploadAttachment(file));
    }

    @PostMapping("/{ticketNo}/messages")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<SupportTicketResponse> reply(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long adminId,
            @RequestHeader(value = AuthHeaders.USERNAME, required = false) String adminName,
            @PathVariable String ticketNo,
            @Valid @RequestBody SupportTicketReplyRequest request) {
        return ApiResult.ok(supportTicketService.opsReply(adminId, adminName, ticketNo, request));
    }

    @PatchMapping("/{ticketNo}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<SupportTicketResponse> update(
            @PathVariable String ticketNo,
            @Valid @RequestBody SupportTicketOpsUpdateRequest request) {
        return ApiResult.ok(supportTicketService.updateByOps(ticketNo, request));
    }
}
