package ffdd.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.common.storage.ObjectStorageService;
import ffdd.common.storage.StoredObject;
import ffdd.system.client.NotificationClient;
import ffdd.system.domain.SupportTicket;
import ffdd.system.domain.SupportTicketAttachment;
import ffdd.system.domain.SupportTicketMessage;
import ffdd.system.dto.NotificationCreateRequest;
import ffdd.system.dto.SupportTicketAttachmentRequest;
import ffdd.system.dto.SupportTicketAttachmentResponse;
import ffdd.system.dto.SupportTicketCreateRequest;
import ffdd.system.dto.SupportTicketMessageResponse;
import ffdd.system.dto.SupportTicketOpsUpdateRequest;
import ffdd.system.dto.SupportTicketReplyRequest;
import ffdd.system.dto.SupportTicketResponse;
import ffdd.system.mapper.SupportTicketAttachmentMapper;
import ffdd.system.mapper.SupportTicketMapper;
import ffdd.system.mapper.SupportTicketMessageMapper;
import ffdd.system.service.SupportTicketService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportTicketServiceImpl implements SupportTicketService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 50L * 1024L * 1024L;
    private static final int ATTACHMENT_PREVIEW_EXPIRY_SECONDS = 900;
    private static final String SENDER_USER = "USER";
    private static final String SENDER_AGENT = "AGENT";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_WAITING_AGENT = "WAITING_AGENT";
    private static final String STATUS_WAITING_USER = "WAITING_USER";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String TYPE_SUPPORT = "SUPPORT";
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final DateTimeFormatter ATTACHMENT_KEY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ALLOWED_ATTACHMENT_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "video/mp4",
            "video/webm",
            "video/quicktime");
    private static final Map<String, String> DEFAULT_ATTACHMENT_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "video/mp4", ".mp4",
            "video/webm", ".webm",
            "video/quicktime", ".mov");
    private static final Map<String, Set<String>> ALLOWED_ATTACHMENT_EXTENSIONS = Map.of(
            "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png"),
            "image/webp", Set.of(".webp"),
            "video/mp4", Set.of(".mp4", ".m4v"),
            "video/webm", Set.of(".webm"),
            "video/quicktime", Set.of(".mov", ".qt"));

    private final SupportTicketMapper ticketMapper;
    private final SupportTicketMessageMapper messageMapper;
    private final SupportTicketAttachmentMapper attachmentMapper;
    private final NotificationClient notificationClient;
    private final ObjectStorageService storageService;

    @Override
    @Transactional
    public SupportTicketResponse create(Long userId, SupportTicketCreateRequest request) {
        requireUserId(userId);
        if (request == null) {
            throw new BizException("Support ticket request is required");
        }
        LocalDateTime now = LocalDateTime.now();
        SupportTicket ticket = new SupportTicket();
        ticket.setTicketNo(nextTicketNo(now));
        ticket.setUserId(userId);
        ticket.setCategory(normalizeCode(request.getCategory(), "category", 32));
        ticket.setPriority(normalizePriority(request.getPriority()));
        ticket.setStatus(STATUS_OPEN);
        ticket.setTitle(requireText(request.getTitle(), "title", 160));
        ticket.setLastMessage(requireText(request.getContent(), "content", 4000));
        ticket.setUserUnreadCount(0);
        ticket.setOpsUnreadCount(1);
        ticket.setMessageCount(0);
        ticket.setLastMessageAt(now);
        ticket.setIsDeleted(0);
        ticketMapper.insert(ticket);
        addMessage(ticket, userId, SENDER_USER, "User", request.getContent(), request.getAttachments(), now);
        return detailForUser(userId, ticket.getTicketNo());
    }

    @Override
    public PageResult<SupportTicketResponse> pageForUser(Long userId, String status, long pageNum, long pageSize) {
        requireUserId(userId);
        LambdaQueryWrapper<SupportTicket> wrapper = baseTicketQuery()
                .eq(SupportTicket::getUserId, userId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(SupportTicket::getStatus, normalizeStatus(status));
        }
        wrapper.orderByDesc(SupportTicket::getLastMessageAt)
                .orderByDesc(SupportTicket::getId);
        Page<SupportTicket> page = ticketMapper.selectPage(new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize)),
                wrapper);
        return toPage(page, ticket -> toResponse(ticket, List.of()));
    }

    @Override
    public SupportTicketResponse detailForUser(Long userId, String ticketNo) {
        SupportTicket ticket = requireOwnedTicket(userId, ticketNo);
        return detail(ticket);
    }

    @Override
    @Transactional
    public SupportTicketResponse userReply(Long userId, String ticketNo, SupportTicketReplyRequest request) {
        SupportTicket ticket = requireOwnedTicket(userId, ticketNo);
        if (STATUS_CLOSED.equals(ticket.getStatus()) || STATUS_RESOLVED.equals(ticket.getStatus())) {
            throw new BizException("Closed support ticket cannot be replied");
        }
        addMessage(ticket, userId, SENDER_USER, "User", requireReply(request).getContent(), request.getAttachments(), LocalDateTime.now());
        patchAfterMessage(ticket, STATUS_WAITING_AGENT, request.getContent(), 0, 1, null, null);
        return detailForUser(userId, ticketNo);
    }

    @Override
    @Transactional
    public SupportTicketResponse closeByUser(Long userId, String ticketNo) {
        SupportTicket ticket = requireOwnedTicket(userId, ticketNo);
        patchStatus(ticket, STATUS_CLOSED, null, null, LocalDateTime.now());
        return detailForUser(userId, ticketNo);
    }

    @Override
    @Transactional
    public SupportTicketResponse reopenByUser(Long userId, String ticketNo) {
        SupportTicket ticket = requireOwnedTicket(userId, ticketNo);
        if (!STATUS_CLOSED.equals(ticket.getStatus()) && !STATUS_RESOLVED.equals(ticket.getStatus())) {
            throw new BizException("Only closed support ticket can be reopened");
        }
        patchStatus(ticket, STATUS_OPEN, null, null, null);
        return detailForUser(userId, ticketNo);
    }

    @Override
    public PageResult<SupportTicketResponse> pageForOps(
            String status,
            String category,
            String priority,
            Long userId,
            Long assignedAdminId,
            long pageNum,
            long pageSize) {
        LambdaQueryWrapper<SupportTicket> wrapper = baseTicketQuery();
        if (StringUtils.hasText(status)) {
            wrapper.eq(SupportTicket::getStatus, normalizeStatus(status));
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(SupportTicket::getCategory, normalizeCode(category, "category", 32));
        }
        if (StringUtils.hasText(priority)) {
            wrapper.eq(SupportTicket::getPriority, normalizePriority(priority));
        }
        if (userId != null) {
            wrapper.eq(SupportTicket::getUserId, userId);
        }
        if (assignedAdminId != null) {
            wrapper.eq(SupportTicket::getAssignedAdminId, assignedAdminId);
        }
        wrapper.orderByDesc(SupportTicket::getLastMessageAt)
                .orderByDesc(SupportTicket::getId);
        Page<SupportTicket> page = ticketMapper.selectPage(new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize)), wrapper);
        return toPage(page, ticket -> toResponse(ticket, List.of()));
    }

    @Override
    public SupportTicketResponse detailForOps(String ticketNo) {
        return detail(requireTicket(ticketNo));
    }

    @Override
    @Transactional
    public SupportTicketResponse opsReply(Long adminId, String adminName, String ticketNo, SupportTicketReplyRequest request) {
        SupportTicket ticket = requireTicket(ticketNo);
        if (STATUS_CLOSED.equals(ticket.getStatus())) {
            throw new BizException("Closed support ticket cannot be replied");
        }
        SupportTicketReplyRequest normalized = requireReply(request);
        String senderName = StringUtils.hasText(adminName) ? adminName.trim() : "Support";
        addMessage(ticket, adminId, SENDER_AGENT, senderName, normalized.getContent(), normalized.getAttachments(), LocalDateTime.now());
        patchAfterMessage(ticket, STATUS_WAITING_USER, normalized.getContent(), 1, 0, adminId, senderName);
        notifyUser(ticket.getUserId(), "Support ticket updated", "Your ticket " + ticket.getTicketNo() + " has a new reply.",
                "SupportTicketReply:" + ticket.getTicketNo() + ":" + ticket.getMessageCount());
        return detailForOps(ticketNo);
    }

    @Override
    @Transactional
    public SupportTicketResponse updateByOps(String ticketNo, SupportTicketOpsUpdateRequest request) {
        if (request == null) {
            throw new BizException("Support ticket update request is required");
        }
        SupportTicket ticket = requireTicket(ticketNo);
        String oldStatus = ticket.getStatus();
        UpdateWrapper<SupportTicket> update = new UpdateWrapper<SupportTicket>()
                .eq("id", ticket.getId());
        boolean changed = false;
        if (request.getStatus() != null) {
            String status = normalizeStatus(request.getStatus());
            update.set("status", status);
            update.set("closed_at",
                    STATUS_CLOSED.equals(status) || STATUS_RESOLVED.equals(status) ? LocalDateTime.now() : null);
            ticket.setStatus(status);
            changed = true;
        }
        if (request.getPriority() != null) {
            String priority = normalizePriority(request.getPriority());
            update.set("priority", priority);
            ticket.setPriority(priority);
            changed = true;
        }
        if (request.getCategory() != null) {
            String category = normalizeCode(request.getCategory(), "category", 32);
            update.set("category", category);
            ticket.setCategory(category);
            changed = true;
        }
        if (request.getAssignedAdminId() != null) {
            update.set("assigned_admin_id", request.getAssignedAdminId());
            ticket.setAssignedAdminId(request.getAssignedAdminId());
            changed = true;
        }
        if (request.getAssignedAdminName() != null) {
            String assignedAdminName = optionalText(request.getAssignedAdminName(), 64);
            update.set("assigned_admin_name", assignedAdminName);
            ticket.setAssignedAdminName(assignedAdminName);
            changed = true;
        }
        if (!changed) {
            throw new BizException("No support ticket fields to update");
        }
        ticketMapper.update(null, update);
        if (request.getStatus() != null && !ticket.getStatus().equals(oldStatus)) {
            notifyUser(ticket.getUserId(), "Support ticket status changed",
                    "Your ticket " + ticket.getTicketNo() + " is now " + ticket.getStatus() + ".",
                    "SupportTicketStatus:" + ticket.getTicketNo() + ":" + ticket.getStatus());
        }
        return detailForOps(ticketNo);
    }

    @Override
    public SupportTicketAttachmentResponse uploadAttachment(MultipartFile file) {
        AttachmentPayload payload = readAndValidateAttachment(file);
        String objectKey = supportObjectKey(payload.extension());
        StoredObject storedObject = storageService.put(
                objectKey,
                payload.contentType(),
                new ByteArrayInputStream(payload.bytes()),
                payload.sizeBytes());
        storageService.presignGet(storedObject.getObjectKey(), Duration.ofSeconds(ATTACHMENT_PREVIEW_EXPIRY_SECONDS));
        return new SupportTicketAttachmentResponse(
                null,
                storedObject.getObjectKey(),
                payload.fileName(),
                storedObject.getContentType(),
                storedObject.getSizeBytes(),
                LocalDateTime.now());
    }

    private SupportTicketReplyRequest requireReply(SupportTicketReplyRequest request) {
        if (request == null) {
            throw new BizException("Support ticket reply request is required");
        }
        requireText(request.getContent(), "content", 4000);
        return request;
    }

    private void addMessage(
            SupportTicket ticket,
            Long senderId,
            String senderType,
            String senderName,
            String content,
            List<SupportTicketAttachmentRequest> attachments,
            LocalDateTime now) {
        SupportTicketMessage message = new SupportTicketMessage();
        message.setTicketId(ticket.getId());
        message.setTicketNo(ticket.getTicketNo());
        message.setSenderId(senderId);
        message.setSenderType(senderType);
        message.setSenderName(optionalText(senderName, 64));
        message.setContent(requireText(content, "content", 4000));
        message.setIsDeleted(0);
        messageMapper.insert(message);
        if (attachments != null) {
            for (SupportTicketAttachmentRequest request : attachments) {
                if (request == null) {
                    continue;
                }
                SupportTicketAttachment attachment = new SupportTicketAttachment();
                attachment.setTicketId(ticket.getId());
                attachment.setMessageId(message.getId());
                attachment.setObjectKey(validateAttachmentObjectKey(request.getObjectKey()));
                attachment.setFileName(optionalText(request.getFileName(), 255));
                attachment.setContentType(optionalText(request.getContentType(), 96));
                attachment.setFileSize(normalizeFileSize(request.getFileSize()));
                attachment.setIsDeleted(0);
                attachmentMapper.insert(attachment);
            }
        }
        ticket.setMessageCount(safe(ticket.getMessageCount()) + 1);
        ticket.setLastMessageAt(now);
    }

    private void patchAfterMessage(
            SupportTicket ticket,
            String status,
            String lastMessage,
            int userUnreadDelta,
            int opsUnreadDelta,
            Long assignedAdminId,
            String assignedAdminName) {
        ticketMapper.update(null, new UpdateWrapper<SupportTicket>()
                .eq("id", ticket.getId())
                .set("status", status)
                .set("last_message", truncate(lastMessage, 512))
                .set("message_count", safe(ticket.getMessageCount()))
                .set("last_message_at", ticket.getLastMessageAt())
                .set("user_unread_count", safe(ticket.getUserUnreadCount()) + userUnreadDelta)
                .set("ops_unread_count", safe(ticket.getOpsUnreadCount()) + opsUnreadDelta)
                .set(assignedAdminId != null, "assigned_admin_id", assignedAdminId)
                .set(StringUtils.hasText(assignedAdminName), "assigned_admin_name", optionalText(assignedAdminName, 64))
                .set("closed_at", null));
    }

    private void patchStatus(
            SupportTicket ticket,
            String status,
            Long assignedAdminId,
            String assignedAdminName,
            LocalDateTime closedAt) {
        ticketMapper.update(null, new UpdateWrapper<SupportTicket>()
                .eq("id", ticket.getId())
                .set("status", status)
                .set(assignedAdminId != null, "assigned_admin_id", assignedAdminId)
                .set(StringUtils.hasText(assignedAdminName), "assigned_admin_name", optionalText(assignedAdminName, 64))
                .set("closed_at", closedAt));
    }

    private SupportTicketResponse detail(SupportTicket ticket) {
        List<SupportTicketMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<SupportTicketMessage>()
                .eq(SupportTicketMessage::getTicketId, ticket.getId())
                .eq(SupportTicketMessage::getIsDeleted, 0)
                .orderByAsc(SupportTicketMessage::getCreatedAt)
                .orderByAsc(SupportTicketMessage::getId));
        List<Long> messageIds = messages.stream().map(SupportTicketMessage::getId).toList();
        Map<Long, List<SupportTicketAttachmentResponse>> attachmentsByMessage = messageIds.isEmpty()
                ? Map.of()
                : attachmentMapper.selectList(new LambdaQueryWrapper<SupportTicketAttachment>()
                                .in(SupportTicketAttachment::getMessageId, messageIds)
                                .eq(SupportTicketAttachment::getIsDeleted, 0)
                                .orderByAsc(SupportTicketAttachment::getId))
                        .stream()
                        .collect(Collectors.groupingBy(
                                SupportTicketAttachment::getMessageId,
                                Collectors.mapping(this::toAttachmentResponse, Collectors.toList())));
        List<SupportTicketMessageResponse> responses = messages.stream()
                .map(message -> toMessageResponse(message, attachmentsForMessage(message.getId(), attachmentsByMessage)))
                .toList();
        return toResponse(ticket, responses);
    }

    private List<SupportTicketAttachmentResponse> attachmentsForMessage(
            Long messageId,
            Map<Long, List<SupportTicketAttachmentResponse>> attachmentsByMessage) {
        return attachmentsByMessage.getOrDefault(messageId, List.of());
    }

    private SupportTicket requireOwnedTicket(Long userId, String ticketNo) {
        requireUserId(userId);
        SupportTicket ticket = requireTicket(ticketNo);
        if (!userId.equals(ticket.getUserId())) {
            throw new BizException(404, "Support ticket not found");
        }
        return ticket;
    }

    private SupportTicket requireTicket(String ticketNo) {
        String normalizedNo = normalizeCode(ticketNo, "ticketNo", 40);
        SupportTicket ticket = ticketMapper.selectOne(baseTicketQuery()
                .eq(SupportTicket::getTicketNo, normalizedNo)
                .last("LIMIT 1"));
        if (ticket == null) {
            throw new BizException(404, "Support ticket not found");
        }
        return ticket;
    }

    private LambdaQueryWrapper<SupportTicket> baseTicketQuery() {
        return new LambdaQueryWrapper<SupportTicket>().eq(SupportTicket::getIsDeleted, 0);
    }

    private void notifyUser(Long userId, String title, String body, String bizNo) {
        try {
            notificationClient.create(new NotificationCreateRequest(bizNo, userId, TYPE_SUPPORT, title, body));
        } catch (RuntimeException ex) {
            log.warn("Failed to create support ticket notification, userId={}, bizNo={}", userId, bizNo, ex);
        }
    }

    private String nextTicketNo(LocalDateTime now) {
        return "TK" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private PageResult<SupportTicketResponse> toPage(Page<SupportTicket> page, Function<SupportTicket, SupportTicketResponse> mapper) {
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords().stream().map(mapper).toList());
    }

    private SupportTicketResponse toResponse(SupportTicket ticket, List<SupportTicketMessageResponse> messages) {
        return new SupportTicketResponse(
                ticket.getId(),
                ticket.getTicketNo(),
                ticket.getUserId(),
                ticket.getCategory(),
                ticket.getPriority(),
                ticket.getStatus(),
                ticket.getTitle(),
                ticket.getLastMessage(),
                ticket.getAssignedAdminId(),
                ticket.getAssignedAdminName(),
                safe(ticket.getUserUnreadCount()),
                safe(ticket.getOpsUnreadCount()),
                safe(ticket.getMessageCount()),
                ticket.getLastMessageAt(),
                ticket.getClosedAt(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                messages);
    }

    private SupportTicketMessageResponse toMessageResponse(
            SupportTicketMessage message,
            List<SupportTicketAttachmentResponse> attachments) {
        return new SupportTicketMessageResponse(
                message.getId(),
                message.getTicketNo(),
                message.getSenderId(),
                message.getSenderType(),
                message.getSenderName(),
                message.getContent(),
                message.getCreatedAt(),
                attachments);
    }

    private SupportTicketAttachmentResponse toAttachmentResponse(SupportTicketAttachment attachment) {
        return new SupportTicketAttachmentResponse(
                attachment.getId(),
                attachment.getObjectKey(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getFileSize(),
                attachment.getCreatedAt());
    }

    private long normalizePageNum(long pageNum) {
        return Math.max(1, pageNum);
    }

    private long normalizePageSize(long pageSize) {
        return Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("User id is required");
        }
    }

    private String normalizeStatus(String status) {
        String normalized = normalizeCode(status, "status", 32).toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_OPEN, STATUS_WAITING_AGENT, STATUS_WAITING_USER, STATUS_PROCESSING, STATUS_RESOLVED, STATUS_CLOSED)
                .contains(normalized)) {
            throw new BizException("Unsupported support ticket status");
        }
        return normalized;
    }

    private String normalizePriority(String priority) {
        if (!StringUtils.hasText(priority)) {
            return "NORMAL";
        }
        String normalized = normalizeCode(priority, "priority", 32).toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "NORMAL", "HIGH", "URGENT").contains(normalized)) {
            throw new BizException("Unsupported support ticket priority");
        }
        return normalized;
    }

    private String normalizeCode(String value, String fieldName, int maxLength) {
        String normalized = requireText(value, fieldName, maxLength).toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9._:-]+$")) {
            throw new BizException("Invalid " + fieldName);
        }
        return normalized;
    }

    private String requireText(String value, String fieldName, int maxLength) {
        String normalized = optionalText(value, maxLength);
        if (!StringUtils.hasText(normalized)) {
            throw new BizException(fieldName + " is required");
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BizException("Field length must be <= " + maxLength);
        }
        return normalized;
    }

    private Long normalizeFileSize(Long fileSize) {
        if (fileSize == null) {
            return null;
        }
        if (fileSize < 0) {
            throw new BizException("Invalid fileSize");
        }
        return fileSize;
    }

    private AttachmentPayload readAndValidateAttachment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("Support ticket attachment is required");
        }
        try {
            byte[] bytes = file.getBytes();
            String contentType = normalizeAttachmentContentType(file.getContentType());
            if (bytes.length <= 0 || bytes.length > MAX_ATTACHMENT_SIZE_BYTES) {
                throw new BizException("Support ticket attachment size is invalid");
            }
            String fileName = safeAttachmentFileName(file.getOriginalFilename());
            String extension = attachmentExtension(fileName, contentType);
            return new AttachmentPayload(fileName, contentType, extension, bytes.length, bytes);
        } catch (IOException ex) {
            throw new BizException("Support ticket attachment cannot be read");
        }
    }

    private String supportObjectKey(String extension) {
        return "support/tickets/"
                + ATTACHMENT_KEY_TIME_FORMAT.format(LocalDateTime.now())
                + "-"
                + UUID.randomUUID().toString().replace("-", "")
                + extension;
    }

    private String validateAttachmentObjectKey(String objectKey) {
        String key = requireText(objectKey, "objectKey", 512);
        String lower = key.toLowerCase(Locale.ROOT);
        if (!key.startsWith("support/tickets/")
                || key.startsWith("/")
                || key.endsWith("/")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.contains("..")
                || key.indexOf('\\') >= 0
                || containsControlCharacters(key)
                || !SAFE_TOKEN.matcher(key.substring(key.lastIndexOf('/') + 1)).matches()) {
            throw new BizException("Support ticket attachment objectKey must use support/tickets object storage key");
        }
        return key;
    }

    private String normalizeAttachmentContentType(String contentType) {
        String normalized = requireText(contentType, "contentType", 128).toLowerCase(Locale.ROOT);
        if (!ALLOWED_ATTACHMENT_CONTENT_TYPES.contains(normalized) || containsControlCharacters(normalized)) {
            throw new BizException("Unsupported support ticket attachment content type");
        }
        return normalized;
    }

    private String safeAttachmentFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "support-attachment";
        }
        String normalized = fileName.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        if (!StringUtils.hasText(normalized) || normalized.length() > 255 || containsControlCharacters(normalized)) {
            throw new BizException("Support ticket attachment file name contains invalid characters");
        }
        return normalized;
    }

    private String attachmentExtension(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.length() <= 16
                    && extension.matches("\\.[a-z0-9]+")
                    && ALLOWED_ATTACHMENT_EXTENSIONS.getOrDefault(contentType, Set.of()).contains(extension)) {
                return extension;
            }
            throw new BizException("Unsupported support ticket attachment file extension");
        }
        return DEFAULT_ATTACHMENT_EXTENSIONS.getOrDefault(contentType, ".bin");
    }

    private boolean containsControlCharacters(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private record AttachmentPayload(String fileName, String contentType, String extension, long sizeBytes, byte[] bytes) {
    }
}
