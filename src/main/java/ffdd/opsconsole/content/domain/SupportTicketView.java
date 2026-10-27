package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

public record SupportTicketView(
        Long id,
        String ticketNo,
        Long userId,
        String category,
        String priority,
        String status,
        String title,
        String lastMessage,
        Long assignedAdminId,
        String assignedAdminName,
        Integer userUnreadCount,
        Integer opsUnreadCount,
        Integer messageCount,
        LocalDateTime lastMessageAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Boolean archived,
        LocalDateTime archivedAt,
        Long version,
        Boolean userExists,
        String sourceConversationNo,
        boolean contentRestricted,
        String customerName,
        /* M-53: filled for console list rows only; App rows and detail omit it. */
        @JsonInclude(JsonInclude.Include.NON_NULL) SupportTicketSlaTarget slaTarget) {
    public static final String DIRECT_SOURCE = "DIRECT";
    public static final String RESTRICTED_TEXT = "私聊内容仅当前顾问和主管可阅";
    public SupportTicketView(Long id, String ticketNo, Long userId, String category, String priority,
            String status, String title, String lastMessage, Long assignedAdminId, String assignedAdminName,
            Integer userUnreadCount, Integer opsUnreadCount, Integer messageCount, LocalDateTime lastMessageAt,
            LocalDateTime closedAt, LocalDateTime createdAt, LocalDateTime updatedAt, Boolean archived,
            LocalDateTime archivedAt, Long version, Boolean userExists, String sourceConversationNo,
            boolean contentRestricted, String customerName) {
        this(id,ticketNo,userId,category,priority,status,title,lastMessage,assignedAdminId,assignedAdminName,
                userUnreadCount,opsUnreadCount,messageCount,lastMessageAt,closedAt,createdAt,updatedAt,
                archived,archivedAt,version,userExists,sourceConversationNo,contentRestricted,customerName,null);
    }
    public SupportTicketView withSlaTarget(SupportTicketSlaTarget target) {
        return new SupportTicketView(id,ticketNo,userId,category,priority,status,title,lastMessage,assignedAdminId,
                assignedAdminName,userUnreadCount,opsUnreadCount,messageCount,lastMessageAt,closedAt,createdAt,
                updatedAt,archived,archivedAt,version,userExists,sourceConversationNo,contentRestricted,customerName,target);
    }
    public SupportTicketView(Long id, String ticketNo, Long userId, String category, String priority,
            String status, String title, String lastMessage, Long assignedAdminId, String assignedAdminName,
            Integer userUnreadCount, Integer opsUnreadCount, Integer messageCount, LocalDateTime lastMessageAt,
            LocalDateTime closedAt, LocalDateTime createdAt, LocalDateTime updatedAt, Boolean archived,
            LocalDateTime archivedAt, Long version, Boolean userExists, String sourceConversationNo,
            boolean contentRestricted) {
        this(id,ticketNo,userId,category,priority,status,title,lastMessage,assignedAdminId,assignedAdminName,
                userUnreadCount,opsUnreadCount,messageCount,lastMessageAt,closedAt,createdAt,updatedAt,
                archived,archivedAt,version,userExists,sourceConversationNo,contentRestricted,null);
    }
    public SupportTicketView(Long id, String ticketNo, Long userId, String category, String priority,
            String status, String title, String lastMessage, Long assignedAdminId, String assignedAdminName,
            Integer userUnreadCount, Integer opsUnreadCount, Integer messageCount, LocalDateTime lastMessageAt,
            LocalDateTime closedAt, LocalDateTime createdAt, LocalDateTime updatedAt, Boolean archived,
            LocalDateTime archivedAt, Long version, Boolean userExists) {
        this(id,ticketNo,userId,category,priority,status,title,lastMessage,assignedAdminId,assignedAdminName,
                userUnreadCount,opsUnreadCount,messageCount,lastMessageAt,closedAt,createdAt,updatedAt,
                archived,archivedAt,version,userExists,null,false);
    }
    public SupportTicketView(
            Long id,
            String ticketNo,
            Long userId,
            String category,
            String priority,
            String status,
            String title,
            String lastMessage,
            Long assignedAdminId,
            String assignedAdminName,
            Integer userUnreadCount,
            Integer opsUnreadCount,
            Integer messageCount,
            LocalDateTime lastMessageAt,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Boolean archived,
            LocalDateTime archivedAt) {
        this(id, ticketNo, userId, category, priority, status, title, lastMessage,
                assignedAdminId, assignedAdminName, userUnreadCount, opsUnreadCount,
                messageCount, lastMessageAt, closedAt, createdAt, updatedAt, archived,
                archivedAt, 0L, userId != null && userId > 0);
    }
}
