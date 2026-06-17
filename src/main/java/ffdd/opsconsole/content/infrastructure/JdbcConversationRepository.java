package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcConversationRepository implements ConversationRepository {
    private static final String CONVERSATION_SELECT = """
            SELECT
              c.id,c.conversation_no,c.user_id,c.conversation_type,c.status,
              c.owner_agent_id,c.owner_agent_name,c.unread_count,c.last_message,c.last_message_at,
              t.from_agent_id,t.from_agent_name,t.to_type,t.to_id,t.to_name,t.reason,t.transferred_at,
              c.updated_at
            FROM nx_conversation c
            LEFT JOIN nx_conversation_transfer t
              ON t.conversation_no=c.conversation_no AND t.status='PENDING' AND t.is_deleted=0
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> counters() {
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("open", count("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='OPEN'"));
        counters.put("incomingPending", count("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='TRANSFERRED'"));
        counters.put("unread", count("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND unread_count>0 AND status<>'CLOSED'"));
        counters.put("resolved", count("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='RESOLVED'"));
        counters.put("closed", count("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='CLOSED'"));
        return counters;
    }

    @Override
    public PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request) {
        QueryParts parts = queryParts(request);
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM nx_conversation c " + parts.where(), Long.class, parts.params().toArray());
        List<Object> dataParams = new ArrayList<>(parts.params());
        dataParams.add(pageSize);
        dataParams.add(offset);
        List<ContentConversationView> records = jdbcTemplate.query(
                CONVERSATION_SELECT + parts.where() + " ORDER BY COALESCE(c.last_message_at,c.updated_at,c.created_at) DESC LIMIT ? OFFSET ?",
                this::mapConversation,
                dataParams.toArray());
        return new PageResult<>(total == null ? 0L : total, pageNum, pageSize, records);
    }

    @Override
    public Optional<ContentConversationView> findByConversationNo(String conversationNo) {
        List<ContentConversationView> rows = jdbcTemplate.query(
                CONVERSATION_SELECT + " WHERE c.is_deleted=0 AND c.conversation_no=?",
                this::mapConversation,
                conversationNo);
        return rows.stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> transferTargets() {
        List<Map<String, Object>> targets = new ArrayList<>();
        targets.add(target("queue", "support", "Support queue"));
        targets.add(target("queue", "advisor", "Advisor queue"));
        targets.add(target("standby", "standby-pool", "Standby pool"));
        try {
            targets.addAll(jdbcTemplate.queryForList("""
                    SELECT account_id AS targetId,display_name AS targetName,'agent' AS targetType
                    FROM nx_admin_account
                    WHERE is_deleted=0 AND status='ENABLED' AND role IN ('support','content','super')
                    ORDER BY role,display_name
                    LIMIT 100
                    """));
        } catch (RuntimeException ignored) {
            return targets;
        }
        return targets;
    }

    @Override
    public void transferToPending(
            ContentConversationView conversation,
            String targetType,
            String targetId,
            String targetName,
            String reason,
            String operator,
            LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE nx_conversation
                SET status='TRANSFERRED', owner_agent_id=?, owner_agent_name=?, updated_at=?
                WHERE conversation_no=? AND is_deleted=0
                """, targetId, targetName, now, conversation.conversationNo());
        jdbcTemplate.update("""
                INSERT INTO nx_conversation_transfer
                  (conversation_no,from_agent_id,from_agent_name,to_type,to_id,to_name,reason,status,operator,transferred_at,is_deleted,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'PENDING',?,?,0,?,?)
                """,
                conversation.conversationNo(),
                conversation.ownerAgentId(),
                conversation.ownerAgentName(),
                targetType,
                targetId,
                targetName,
                reason,
                operator,
                now,
                now,
                now);
    }

    @Override
    public void acceptTransfer(ContentConversationView conversation, String operator, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE nx_conversation
                SET status='OPEN', owner_agent_id=?, owner_agent_name=?, updated_at=?
                WHERE conversation_no=? AND is_deleted=0
                """, operator, operator, now, conversation.conversationNo());
        jdbcTemplate.update("""
                UPDATE nx_conversation_transfer
                SET status='ACCEPTED', accepted_by=?, accepted_at=?, updated_at=?
                WHERE conversation_no=? AND status='PENDING' AND is_deleted=0
                """, operator, now, now, conversation.conversationNo());
    }

    @Override
    public void returnTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE nx_conversation
                SET status='OPEN', owner_agent_id=?, owner_agent_name=?, updated_at=?
                WHERE conversation_no=? AND is_deleted=0
                """, conversation.transferFromAgentId(), conversation.transferFromAgentName(), now, conversation.conversationNo());
        jdbcTemplate.update("""
                UPDATE nx_conversation_transfer
                SET status='RETURNED', return_reason=?, returned_by=?, returned_at=?, updated_at=?
                WHERE conversation_no=? AND status='PENDING' AND is_deleted=0
                """, reason, operator, now, now, conversation.conversationNo());
    }

    private Map<String, Object> target(String type, String id, String name) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("targetType", type);
        target.put("targetId", id);
        target.put("targetName", name);
        return target;
    }

    private QueryParts queryParts(ConversationQueryRequest request) {
        StringBuilder where = new StringBuilder(" WHERE c.is_deleted=0");
        List<Object> params = new ArrayList<>();
        if (request != null && StringUtils.hasText(request.status())) {
            where.append(" AND c.status=?");
            params.add(request.status().trim().toUpperCase());
        }
        if (request != null && StringUtils.hasText(request.type())) {
            where.append(" AND c.conversation_type=?");
            params.add(request.type().trim().toLowerCase());
        }
        if (request != null && StringUtils.hasText(request.ownerAgentId())) {
            where.append(" AND c.owner_agent_id=?");
            params.add(request.ownerAgentId().trim());
        }
        if (request != null && StringUtils.hasText(request.keyword())) {
            where.append(" AND (c.conversation_no LIKE ? OR c.owner_agent_name LIKE ? OR c.last_message LIKE ?)");
            String like = "%" + request.keyword().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return new QueryParts(where.toString(), params);
    }

    private long count(String sql) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    private long normalizePage(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizeSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private ContentConversationView mapConversation(ResultSet rs, int rowNum) throws SQLException {
        return new ContentConversationView(
                rs.getLong("id"),
                rs.getString("conversation_no"),
                nullableLong(rs, "user_id"),
                rs.getString("conversation_type"),
                rs.getString("status"),
                rs.getString("owner_agent_id"),
                rs.getString("owner_agent_name"),
                rs.getObject("unread_count", Integer.class),
                rs.getString("last_message"),
                toLocalDateTime(rs.getTimestamp("last_message_at")),
                rs.getString("from_agent_id"),
                rs.getString("from_agent_name"),
                rs.getString("to_type"),
                rs.getString("to_id"),
                rs.getString("to_name"),
                rs.getString("reason"),
                toLocalDateTime(rs.getTimestamp("transferred_at")),
                toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record QueryParts(String where, List<Object> params) {
    }
}
