package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.security.AuthHeaders;
import ffdd.system.dto.SupportTicketCreateRequest;
import ffdd.system.dto.SupportTicketReplyRequest;
import ffdd.system.dto.SupportTicketResponse;
import ffdd.system.service.SupportTicketService;
import jakarta.validation.Valid;
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
@RequestMapping("/system/support/tickets")
public class SupportTicketController {
    private final SupportTicketService supportTicketService;

    public SupportTicketController(SupportTicketService supportTicketService) {
        this.supportTicketService = supportTicketService;
    }

    @PostMapping
    public ApiResult<SupportTicketResponse> create(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @Valid @RequestBody SupportTicketCreateRequest request) {
        return ApiResult.ok(supportTicketService.create(userId, request));
    }

    @GetMapping
    public ApiResult<PageResult<SupportTicketResponse>> page(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(supportTicketService.pageForUser(userId, status, pageNum, pageSize));
    }

    @GetMapping("/{ticketNo}")
    public ApiResult<SupportTicketResponse> detail(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable String ticketNo) {
        return ApiResult.ok(supportTicketService.detailForUser(userId, ticketNo));
    }

    @PostMapping("/{ticketNo}/messages")
    public ApiResult<SupportTicketResponse> reply(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable String ticketNo,
            @Valid @RequestBody SupportTicketReplyRequest request) {
        return ApiResult.ok(supportTicketService.userReply(userId, ticketNo, request));
    }

    @PatchMapping("/{ticketNo}/close")
    public ApiResult<SupportTicketResponse> close(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable String ticketNo) {
        return ApiResult.ok(supportTicketService.closeByUser(userId, ticketNo));
    }

    @PatchMapping("/{ticketNo}/reopen")
    public ApiResult<SupportTicketResponse> reopen(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable String ticketNo) {
        return ApiResult.ok(supportTicketService.reopenByUser(userId, ticketNo));
    }
}
