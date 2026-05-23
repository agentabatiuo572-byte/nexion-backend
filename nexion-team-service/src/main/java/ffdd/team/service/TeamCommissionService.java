package ffdd.team.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.team.client.CommerceOutboxClient;
import ffdd.team.client.WalletClient;
import ffdd.team.dto.EventConsumerDelivery;
import ffdd.team.dto.OrderPaidPayload;
import ffdd.team.dto.TeamCommissionConsumeResult;
import ffdd.team.dto.TeamCommissionUnlockResult;
import ffdd.team.dto.WalletCreditRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TeamCommissionService {
    private static final String EVENT_ORDER_PAID = "OrderPaid";
    private static final String COMMISSION_UNILEVEL = "UNILEVEL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_POSTED = "POSTED";
    private static final String ASSET_USDT = "USDT";
    private static final String ASSET_NEX = "NEX";
    private static final String BIZ_TYPE_TEAM_COMMISSION = "TEAM_COMMISSION";
    private static final String BIZ_NO_TEAM_COMMISSION_PREFIX = "TEAM-COMMISSION-";
    private static final String HTTP_OUTBOX_CONSUMER_GROUP = "nexion-team-http-outbox";
    private static final String HTTP_OUTBOX_TOPIC = "commerce-outbox-http";
    private static final int MAX_DEPTH = 7;

    private final CommerceOutboxClient outboxClient;
    private final WalletClient walletClient;
    private final EventConsumerDeliveryService consumerDeliveryService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TeamCommissionService(
            CommerceOutboxClient outboxClient,
            WalletClient walletClient,
            EventConsumerDeliveryService consumerDeliveryService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.outboxClient = outboxClient;
        this.walletClient = walletClient;
        this.consumerDeliveryService = consumerDeliveryService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public TeamCommissionConsumeResult consumeOrderPaid(int limit) {
        ApiResult<List<EventOutboxMessage>> response = outboxClient.pending(Math.max(1, Math.min(limit, 100)));
        ensureSuccess(response);

        TeamCommissionConsumeResult result = new TeamCommissionConsumeResult();
        List<EventOutboxMessage> messages = response.getData() == null ? List.of() : response.getData();
        result.setScanned(messages.size());

        for (EventOutboxMessage message : messages) {
            EventConsumerDeliveryService.ConsumerClaim claim = consumerDeliveryService.claim(
                    message, HTTP_OUTBOX_CONSUMER_GROUP, HTTP_OUTBOX_TOPIC, null, 0);
            if (!claim.claimed()) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            if (!EVENT_ORDER_PAID.equals(message.getEventType())) {
                consumerDeliveryService.markSkipped(
                        HTTP_OUTBOX_CONSUMER_GROUP, claim.eventId(), "Unsupported event type " + message.getEventType());
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            try {
                int created = settleOrderPaid(message);
                ensureSuccess(outboxClient.markPublished(message.getEventId()));
                consumerDeliveryService.markSuccess(HTTP_OUTBOX_CONSUMER_GROUP, claim.eventId(), created);
                result.setProcessed(result.getProcessed() + created);
                result.getEventIds().add(message.getEventId());
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
                String errorMessage = errorMessage(ex);
                consumerDeliveryService.markFailure(HTTP_OUTBOX_CONSUMER_GROUP, claim.eventId(), 0, errorMessage);
                ensureSuccess(outboxClient.markFailed(message.getEventId(), Map.of("error", errorMessage)));
            }
        }
        return result;
    }

    public int consumeBrokerOrderPaid(EventOutboxMessage message) {
        return consumeBrokerOrderPaid(message, "nexion-team-order-paid", "nexion-order-paid", null, 0).created();
    }

    public BrokerConsumeDecision consumeBrokerOrderPaid(
            EventOutboxMessage message,
            String consumerGroup,
            String topic,
            String msgId,
            int rocketmqReconsumeTimes) {
        EventOutboxMessage normalized = normalizeBrokerMessage(message, msgId);
        EventConsumerDeliveryService.ConsumerClaim claim = consumerDeliveryService.claim(
                normalized, consumerGroup, topic, msgId, rocketmqReconsumeTimes);
        if (!claim.claimed()) {
            return new BrokerConsumeDecision(
                    true, false, true, false, 0, claim.eventId(), claim.status(), claim.attemptCount());
        }
        if (!EVENT_ORDER_PAID.equals(normalized.getEventType())) {
            consumerDeliveryService.markSkipped(
                    consumerGroup, claim.eventId(), "Unsupported event type " + normalized.getEventType());
            return new BrokerConsumeDecision(
                    true, false, false, false, 0, claim.eventId(), "SKIPPED", claim.attemptCount());
        }
        try {
            int created = settleOrderPaid(normalized);
            consumerDeliveryService.markSuccess(consumerGroup, claim.eventId(), created);
            return new BrokerConsumeDecision(
                    true, false, false, false, created, claim.eventId(), "SUCCESS", claim.attemptCount());
        } catch (RuntimeException ex) {
            EventConsumerDeliveryService.ConsumerFailure failure = consumerDeliveryService.markFailure(
                    consumerGroup, claim.eventId(), rocketmqReconsumeTimes, errorMessage(ex));
            return new BrokerConsumeDecision(
                    false, !failure.dead(), false, failure.dead(), 0,
                    failure.eventId(), failure.status(), failure.attemptCount());
        }
    }

    public BrokerConsumeDecision recordBrokerOrderPaidFailure(
            String eventId,
            String consumerGroup,
            String topic,
            String msgId,
            int rocketmqReconsumeTimes,
            String errorMessage) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(StringUtils.hasText(eventId) ? eventId : msgId);
        message.setEventType("UNKNOWN");
        message.setAggregateType("ROCKETMQ");
        message.setAggregateId(msgId);
        EventConsumerDeliveryService.ConsumerClaim claim = consumerDeliveryService.claim(
                message, consumerGroup, topic, msgId, rocketmqReconsumeTimes);
        if (!claim.claimed()) {
            return new BrokerConsumeDecision(
                    true, false, true, false, 0, claim.eventId(), claim.status(), claim.attemptCount());
        }
        EventConsumerDeliveryService.ConsumerFailure failure = consumerDeliveryService.markFailure(
                consumerGroup, claim.eventId(), rocketmqReconsumeTimes, errorMessage);
        return new BrokerConsumeDecision(
                false, !failure.dead(), false, failure.dead(), 0,
                failure.eventId(), failure.status(), failure.attemptCount());
    }

    public TeamCommissionUnlockResult unlockDueCommissions(int limit, LocalDateTime unlockBefore, String orderNo) {
        int normalizedLimit = Math.max(1, Math.min(limit, 500));
        LocalDateTime cutoff = unlockBefore == null ? LocalDateTime.now() : unlockBefore;
        TeamCommissionUnlockResult result = new TeamCommissionUnlockResult();
        List<CommissionCandidate> candidates = dueCommissions(normalizedLimit, cutoff, orderNo);
        result.setScanned(candidates.size());

        for (CommissionCandidate candidate : candidates) {
            try {
                int walletPosts = 0;
                walletPosts += postCommissionAsset(candidate, ASSET_USDT, candidate.amountUsdt());
                walletPosts += postCommissionAsset(candidate, ASSET_NEX, candidate.amountNex());
                if (walletPosts == 0) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                int updated = markCommissionPosted(candidate.id());
                result.setWalletPosts(result.getWalletPosts() + walletPosts);
                if (updated > 0) {
                    result.setPosted(result.getPosted() + 1);
                    result.getCommissionIds().add(candidate.id());
                } else {
                    result.setSkipped(result.getSkipped() + 1);
                }
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public int settleOrderPaid(EventOutboxMessage message) {
        OrderPaidPayload payload = readPayload(message.getPayload());
        if (payload.getOrderNo() == null || payload.getUserId() == null || payload.getAmountUsdt() == null) {
            throw new BizException("Invalid OrderPaid payload");
        }
        if (hasCommissionForOrder(payload.getOrderNo())) {
            return 0;
        }

        List<CommissionRule> rules = rules();
        Long currentUserId = payload.getUserId();
        int created = 0;
        for (CommissionRule rule : rules) {
            Sponsor sponsor = sponsorOf(currentUserId);
            if (sponsor == null) {
                break;
            }
            BigDecimal amountUsdt = payload.getAmountUsdt()
                    .multiply(rule.usdtRate())
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal amountNex = payload.getAmountUsdt()
                    .multiply(rule.nexPerUsd())
                    .add(rule.fixedNex())
                    .setScale(6, RoundingMode.HALF_UP);
            insertCommission(payload, sponsor, rule, amountUsdt, amountNex);
            upsertTeamMember(sponsor.userId(), payload.getUserId(), rule.layerNo(), payload.getAmountUsdt());
            currentUserId = sponsor.userId();
            created++;
        }
        return created;
    }

    public Map<String, Object> overview(Long userId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("userId", userId);
        summary.put("directCount", countTeamMembers(userId, 1));
        summary.put("teamCount", countTeamMembers(userId, null));
        summary.put("commissionCount", countCommissions(userId));
        summary.put("pendingUsdt", sumCommission(userId, "amount_usdt"));
        summary.put("pendingNex", sumCommission(userId, "amount_nex"));
        summary.put("recentCommissions", recentCommissions(userId, 1, 10).getRecords());
        return summary;
    }

    public PageResult<Map<String, Object>> pageCommissions(Long userId, long pageNum, long pageSize) {
        return recentCommissions(userId, pageNum, pageSize);
    }

    public List<EventConsumerDelivery> listConsumerDead(String consumerGroup, int limit) {
        return consumerDeliveryService.listByStatus(consumerGroup, "DEAD", limit);
    }

    public EventConsumerDelivery getConsumerDelivery(String consumerGroup, String eventId) {
        return consumerDeliveryService.getByEvent(consumerGroup, eventId);
    }

    public List<EventConsumerDelivery> listConsumerDeliveriesByAggregate(
            String aggregateType, String aggregateId, int limit) {
        return consumerDeliveryService.listByAggregate(aggregateType, aggregateId, limit);
    }

    public List<Map<String, Object>> consumerDeliverySummary(String consumerGroup) {
        return consumerDeliveryService.summary(consumerGroup);
    }

    private OrderPaidPayload readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, OrderPaidPayload.class);
        } catch (JsonProcessingException ex) {
            throw new BizException("Unable to read OrderPaid payload");
        }
    }

    private EventOutboxMessage normalizeBrokerMessage(EventOutboxMessage message, String msgId) {
        if (message == null) {
            throw new BizException("Invalid outbox message");
        }
        if (!StringUtils.hasText(message.getEventId())) {
            message.setEventId(msgId);
        }
        if (!StringUtils.hasText(message.getEventId())) {
            throw new BizException("Invalid outbox message");
        }
        return message;
    }

    private String errorMessage(RuntimeException ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private List<CommissionRule> rules() {
        return jdbcTemplate.query("""
                SELECT layer_no, usdt_rate, nex_per_usd, fixed_nex, cooldown_days
                  FROM nx_commission_rule
                 WHERE commission_type = 'UNILEVEL'
                   AND status = 1
                   AND is_deleted = 0
                 ORDER BY layer_no ASC
                 LIMIT ?
                """, (rs, rowNum) -> new CommissionRule(
                rs.getInt("layer_no"),
                rs.getBigDecimal("usdt_rate"),
                rs.getBigDecimal("nex_per_usd"),
                rs.getBigDecimal("fixed_nex"),
                rs.getInt("cooldown_days")), MAX_DEPTH);
    }

    private List<CommissionCandidate> dueCommissions(int limit, LocalDateTime cutoff, String orderNo) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, commission_type, layer_no, order_no, amount_usdt, amount_nex
                  FROM nx_commission_event
                 WHERE status = ?
                   AND is_deleted = 0
                   AND unlock_at <= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(STATUS_PENDING);
        args.add(cutoff);
        if (StringUtils.hasText(orderNo)) {
            sql.append("   AND order_no = ?\n");
            args.add(orderNo);
        }
        sql.append(" ORDER BY unlock_at ASC, id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapCommissionCandidate, args.toArray());
    }

    private CommissionCandidate mapCommissionCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new CommissionCandidate(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("commission_type"),
                rs.getInt("layer_no"),
                rs.getString("order_no"),
                rs.getBigDecimal("amount_usdt"),
                rs.getBigDecimal("amount_nex"));
    }

    private int postCommissionAsset(CommissionCandidate candidate, String asset, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        WalletCreditRequest request = new WalletCreditRequest();
        request.setUserId(candidate.userId());
        request.setBizNo(BIZ_NO_TEAM_COMMISSION_PREFIX + candidate.id());
        request.setBizType(BIZ_TYPE_TEAM_COMMISSION);
        request.setAsset(asset);
        request.setAmount(amount);
        request.setRemark("Team commission " + candidate.commissionType()
                + " order " + candidate.orderNo()
                + " layer " + candidate.layerNo());
        ensureSuccess(walletClient.postCredit(request));
        return 1;
    }

    private int markCommissionPosted(Long id) {
        return jdbcTemplate.update("""
                UPDATE nx_commission_event
                   SET status = ?,
                       updated_at = NOW()
                 WHERE id = ?
                   AND status = ?
                   AND is_deleted = 0
                """, STATUS_POSTED, id, STATUS_PENDING);
    }

    private Sponsor sponsorOf(Long userId) {
        List<Sponsor> sponsors = jdbcTemplate.query("""
                SELECT u.sponsor_user_id AS user_id, s.nickname, s.v_rank
                  FROM nx_user u
                  JOIN nx_user s ON s.id = u.sponsor_user_id AND s.is_deleted = 0
                 WHERE u.id = ?
                   AND u.is_deleted = 0
                   AND u.sponsor_user_id IS NOT NULL
                 LIMIT 1
                """, (rs, rowNum) -> new Sponsor(
                rs.getLong("user_id"),
                rs.getString("nickname"),
                rs.getString("v_rank")), userId);
        return sponsors.isEmpty() ? null : sponsors.get(0);
    }

    private boolean hasCommissionForOrder(String orderNo) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_commission_event
                 WHERE commission_type = 'UNILEVEL'
                   AND order_no = ?
                   AND is_deleted = 0
                """, Integer.class, orderNo);
        return count != null && count > 0;
    }

    private void insertCommission(
            OrderPaidPayload payload,
            Sponsor sponsor,
            CommissionRule rule,
            BigDecimal amountUsdt,
            BigDecimal amountNex) {
        jdbcTemplate.update("""
                INSERT INTO nx_commission_event (
                  user_id, commission_type, source_user_id, source_user_name, layer_no,
                  order_no, order_amount_usd, amount_usdt, amount_nex, currency,
                  status, unlock_at, remark, created_at, updated_at, is_deleted
                ) VALUES (?, 'UNILEVEL', ?, ?, ?, ?, ?, ?, ?, 'USDT_NEX', ?, ?, ?, NOW(), NOW(), 0)
                """,
                sponsor.userId(),
                payload.getUserId(),
                "User#" + payload.getUserId(),
                rule.layerNo(),
                payload.getOrderNo(),
                payload.getAmountUsdt(),
                amountUsdt,
                amountNex,
                STATUS_PENDING,
                LocalDateTime.now().plusDays(rule.cooldownDays()),
                "OrderPaid outbox " + payload.getOrderNo());
    }

    private void upsertTeamMember(Long sponsorUserId, Long memberUserId, int level, BigDecimal volume) {
        jdbcTemplate.update("""
                INSERT INTO nx_team_member (
                  user_id, member_user_id, member_no, nickname, v_rank, level, volume,
                  created_at, updated_at, is_deleted
                )
                SELECT ?, u.id, CONCAT('U', u.id), COALESCE(u.nickname, CONCAT('User#', u.id)), u.v_rank, ?, ?, NOW(), NOW(), 0
                  FROM nx_user u
                 WHERE u.id = ?
                ON DUPLICATE KEY UPDATE
                  level = LEAST(level, VALUES(level)),
                  volume = volume + VALUES(volume),
                  nickname = VALUES(nickname),
                  v_rank = VALUES(v_rank),
                  updated_at = NOW(),
                  is_deleted = 0
                """, sponsorUserId, level, volume, memberUserId);
    }

    private long countTeamMembers(Long userId, Integer level) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_team_member
                 WHERE user_id = ?
                   AND is_deleted = 0
                   AND (? IS NULL OR level = ?)
                """, Long.class, userId, level, level);
        return count == null ? 0 : count;
    }

    private long countCommissions(Long userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_commission_event
                 WHERE user_id = ?
                   AND is_deleted = 0
                """, Long.class, userId);
        return count == null ? 0 : count;
    }

    private BigDecimal sumCommission(Long userId, String column) {
        BigDecimal amount = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(%s), 0)
                  FROM nx_commission_event
                 WHERE user_id = ?
                   AND status = 'PENDING'
                   AND is_deleted = 0
                """.formatted(column), BigDecimal.class, userId);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private PageResult<Map<String, Object>> recentCommissions(Long userId, long pageNum, long pageSize) {
        long normalizedPageNum = pageNum < 1 ? 1 : pageNum;
        long normalizedPageSize = pageSize < 1 ? 10 : Math.min(pageSize, 100);
        long offset = (normalizedPageNum - 1) * normalizedPageSize;
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_commission_event
                 WHERE user_id = ?
                   AND is_deleted = 0
                """, Long.class, userId);
        List<Map<String, Object>> records = jdbcTemplate.query("""
                SELECT id, user_id, commission_type, source_user_id, source_user_name,
                       layer_no, order_no, order_amount_usd, amount_usdt, amount_nex,
                       currency, status, unlock_at, remark, created_at
                  FROM nx_commission_event
                 WHERE user_id = ?
                   AND is_deleted = 0
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, this::mapCommission, userId, normalizedPageSize, offset);
        return new PageResult<>(total == null ? 0 : total, normalizedPageNum, normalizedPageSize, records);
    }

    private Map<String, Object> mapCommission(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("commissionType", rs.getString("commission_type"));
        row.put("sourceUserId", rs.getLong("source_user_id"));
        row.put("sourceUserName", rs.getString("source_user_name"));
        row.put("layerNo", rs.getInt("layer_no"));
        row.put("orderNo", rs.getString("order_no"));
        row.put("orderAmountUsd", rs.getBigDecimal("order_amount_usd"));
        row.put("amountUsdt", rs.getBigDecimal("amount_usdt"));
        row.put("amountNex", rs.getBigDecimal("amount_nex"));
        row.put("currency", rs.getString("currency"));
        row.put("status", rs.getString("status"));
        row.put("unlockAt", rs.getTimestamp("unlock_at").toLocalDateTime());
        row.put("remark", rs.getString("remark"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        return row;
    }

    private void ensureSuccess(ApiResult<?> result) {
        if (result == null || result.getCode() != 0) {
            throw new BizException(result == null ? 500 : result.getCode(),
                    result == null ? "Empty service response" : result.getMessage());
        }
    }

    private record Sponsor(Long userId, String nickname, String vRank) {
    }

    private record CommissionRule(
            int layerNo,
            BigDecimal usdtRate,
            BigDecimal nexPerUsd,
            BigDecimal fixedNex,
            int cooldownDays) {
    }

    private record CommissionCandidate(
            Long id,
            Long userId,
            String commissionType,
            int layerNo,
            String orderNo,
            BigDecimal amountUsdt,
            BigDecimal amountNex) {
    }

    public record BrokerConsumeDecision(
            boolean success,
            boolean retry,
            boolean duplicate,
            boolean dead,
            int created,
            String eventId,
            String status,
            int attemptCount) {
    }
}
