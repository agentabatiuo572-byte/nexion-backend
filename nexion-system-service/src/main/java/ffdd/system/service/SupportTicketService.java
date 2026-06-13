package ffdd.system.service;

import ffdd.common.api.PageResult;
import ffdd.system.dto.SupportTicketCreateRequest;
import ffdd.system.dto.SupportTicketAttachmentResponse;
import ffdd.system.dto.SupportTicketOpsUpdateRequest;
import ffdd.system.dto.SupportTicketReplyRequest;
import ffdd.system.dto.SupportTicketResponse;
import org.springframework.web.multipart.MultipartFile;

public interface SupportTicketService {
    SupportTicketResponse create(Long userId, SupportTicketCreateRequest request);

    PageResult<SupportTicketResponse> pageForUser(Long userId, String status, long pageNum, long pageSize);

    SupportTicketResponse detailForUser(Long userId, String ticketNo);

    SupportTicketResponse userReply(Long userId, String ticketNo, SupportTicketReplyRequest request);

    SupportTicketResponse closeByUser(Long userId, String ticketNo);

    SupportTicketResponse reopenByUser(Long userId, String ticketNo);

    PageResult<SupportTicketResponse> pageForOps(
            String status,
            String category,
            String priority,
            Long userId,
            Long assignedAdminId,
            long pageNum,
            long pageSize);

    SupportTicketResponse detailForOps(String ticketNo);

    SupportTicketResponse opsReply(Long adminId, String adminName, String ticketNo, SupportTicketReplyRequest request);

    SupportTicketResponse updateByOps(String ticketNo, SupportTicketOpsUpdateRequest request);

    SupportTicketAttachmentResponse uploadAttachment(MultipartFile file);
}
