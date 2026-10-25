package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.promotion.application.PromotionPublicService;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** H4 qualification and delivery reuse canonical users, products, positions and G7 ticket rights. */
@Service
@RequiredArgsConstructor
public class H4EventCommerceService {
    private final PromotionMapper db;
    private final PromotionPublicService promotions;
    private final Clock clock;
    private final GenesisRaffleService raffle;
    private final EventDeviceGiftService gifts;

    public List<String> readiness(String eventCode, Map<String, Object> rules, LocalDateTime startsAt, LocalDateTime endsAt) {
        if (rules.containsKey("giftProductNo")) {
            try {
                String productNo = text(rules.get("giftProductNo"));
                Map<String, Object> product = db.product(productNo, false);
                Map<String, Object> sku = db.requiredRow("SELECT * FROM nx_admin_device_sku WHERE sku_id=? AND is_deleted=0", productNo);
                return EventDeviceGiftService.readinessFailure(product,sku,Math.toIntExact(number(rules.get("giftQuantity"))))==null
                        ? List.of() : List.of("giftProductNo");
            } catch (BizException ex) { return List.of("giftProductNo"); }
        }
        if (rules.containsKey("promotionActivityId")) {
            if (startsAt == null || endsAt == null) return List.of("promotionActivityId");
            Map<String, Object> published = db.one("""
                    SELECT p.activity_id FROM nx_promotion p JOIN nx_promotion_version v
                      ON v.activity_id=p.activity_id AND v.version=p.active_version
                    WHERE p.activity_id=? AND p.status IN ('ACTIVE','SCHEDULED') AND v.status='PUBLISHED'
                      AND v.starts_at<=? AND v.ends_at>=?
                    """, text(rules.get("promotionActivityId")), stored(startsAt), stored(endsAt));
            return published == null ? List.of("promotionActivityId") : List.of();
        }
        return List.of();
    }

    public Map<String, Object> progress(Long userId, String eventCode, Map<String, Object> rules,
                                        LocalDateTime startsAt, LocalDateTime endsAt) {
        if (rules.containsKey("giftProductNo")) {
            long count = referralCount(userId, rules, startsAt, endsAt);
            long target = number(rules.get("targetInvites"));
            return values("current", count, "target", target, "eligible", count >= target,
                    "claimKeys", count >= target ? List.of("ONCE") : List.of());
        }
        if ("REINVEST_TICKETS".equals(rules.get("action"))) {
            List<Map<String, Object>> positions = db.list("""
                    SELECT position_no,amount_usdt,locked_at FROM nx_staking_position
                    WHERE user_id=? AND product_code='REPURCHASE_90D' AND is_deleted=0
                      AND status IN ('ACTIVE','MATURE_UNCLAIMED','CLAIMED') AND locked_at>=? AND locked_at<?
                    ORDER BY id
                    """, userId, stored(startsAt), stored(endsAt));
            List<String> claimKeys = new ArrayList<>();
            List<Map<String, Object>> orders = new ArrayList<>();
            BigDecimal principal = BigDecimal.ZERO;
            for (Map<String, Object> position : positions) {
                String key = text(position.get("position_no"));
                Map<String, Object> ticket = db.one("SELECT * FROM nx_g7_repurchase_ticket WHERE user_id=? AND position_no=? AND is_deleted=0", userId, key);
                long extra = extraTickets(position, ticket, rules, grantedReinvestTickets(userId, key));
                if (extra > 0) claimKeys.add(key);
                principal = principal.add(decimal(position.get("amount_usdt")));
                orders.add(values("claimKey", key, "amountUsdt", position.get("amount_usdt"), "extraTickets", extra));
            }
            return values("current", principal, "target", rules.get("thresholdUsdt"), "eligible", !claimKeys.isEmpty(),
                    "claimKeys", claimKeys, "orders", orders);
        }
        Map<String, Object> promotion = promotions.get(userId, required(rules.get("promotionActivityId"), "promotionActivityId"));
        boolean eligible = "ACTIVE".equals(promotion.get("state")) && "ESTIMATE_ELIGIBLE".equals(promotion.get("eligibility"));
        return values("current", 0, "target", 1, "eligible", eligible, "claimKeys", List.of(),
                "promotion", promotion, "promotionActivityId", promotion.get("activityId"));
    }

    /** Caller locks the event rule and account and owns the claim receipt and atomic event budget. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Map<String, Object> award(Long userId, String eventCode, Map<String, Object> rules,
                                     LocalDateTime startsAt, LocalDateTime endsAt, String claimKey) {
        LocalDateTime now = utcNow();
        require(startsAt != null && endsAt != null && !now.isBefore(startsAt) && now.isBefore(endsAt), "H4_EVENT_NOT_ACTIVE");
        Map<String, Object> user = db.user(userId, true);
        require("ACTIVE".equals(user.get("status")) && number(user.get("sandbox")) == 0, "H4_EVENT_ACCOUNT_UNAVAILABLE");
        if (rules.containsKey("giftProductNo")) {
            require("ONCE".equals(claimKey), "H4_EVENT_CLAIM_KEY_INVALID");
            require(referralCount(userId, rules, startsAt, endsAt) >= number(rules.get("targetInvites")), "H4_EVENT_NOT_ELIGIBLE");
            return gifts.award(userId, "H4:" + eventCode + ":ONCE", required(rules.get("giftProductNo"), "giftProductNo"), Math.toIntExact(number(rules.get("giftQuantity"))));
        }
        require("REINVEST_TICKETS".equals(rules.get("action")), "H4_EVENT_PURCHASE_REQUIRED");
        Map<String, Object> receipt = awardReinvestTickets(userId, eventCode, rules, startsAt, endsAt, claimKey);
        require(receipt != null, "H4_EVENT_NOT_ELIGIBLE");
        return receipt;
    }

    /** Caller has locked and verified the preserved qualification, claim receipt and event budget. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Map<String, Object> awardQualifiedReferral(Long userId, String eventCode, Map<String, Object> rules) {
        return gifts.award(userId, "H4:" + eventCode + ":ONCE",
                required(rules.get("giftProductNo"), "giftProductNo"), Math.toIntExact(number(rules.get("giftQuantity"))));
    }

    private long referralCount(Long userId, Map<String, Object> rules, LocalDateTime startsAt, LocalDateTime endsAt) {
        LocalDateTime now = utcNow();
        LocalDateTime until = endsAt.isBefore(now) ? endsAt : now;
        // Any qualifying window inside this event remains qualified after its first seven days pass.
        return db.count("""
                SELECT COALESCE(MAX(window_count),0) FROM (
                  SELECT candidate.id,COUNT(next_user.id) AS window_count FROM nx_user candidate JOIN nx_user next_user
                    ON next_user.sponsor_user_id=candidate.sponsor_user_id
                    AND next_user.created_at>=candidate.created_at
                    AND next_user.created_at<DATE_ADD(candidate.created_at,INTERVAL ? DAY)
                    AND next_user.created_at<? AND next_user.status='ACTIVE' AND next_user.sandbox=0
                    AND next_user.is_deleted=0 AND next_user.id<>?
                  WHERE candidate.sponsor_user_id=? AND candidate.id<>? AND candidate.status='ACTIVE'
                    AND candidate.sandbox=0 AND candidate.is_deleted=0 AND candidate.created_at>=? AND candidate.created_at<?
                  GROUP BY candidate.id
                ) qualifying_windows
                """, number(rules.get("windowDays")), stored(until), userId, userId, userId, stored(startsAt), stored(until));
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Map<String, Object> awardReinvestTickets(Long userId, String eventCode, Map<String, Object> rules,
                                                   LocalDateTime startsAt, LocalDateTime endsAt, String claimKey) {
        Map<String, Object> position = db.one("""
                SELECT position_no,amount_usdt,locked_at FROM nx_staking_position WHERE user_id=? AND position_no=?
                  AND product_code='REPURCHASE_90D' AND status IN ('ACTIVE','MATURE_UNCLAIMED','CLAIMED')
                  AND is_deleted=0 AND locked_at>=? AND locked_at<? FOR UPDATE
                """, userId, claimKey, stored(startsAt), stored(endsAt));
        if (position == null || position.isEmpty()) return null;
        Map<String, Object> ticket = db.one("SELECT * FROM nx_g7_repurchase_ticket WHERE user_id=? AND position_no=? AND is_deleted=0 FOR UPDATE", userId, claimKey);
        if (ticket != null && ticket.isEmpty()) ticket = null;
        long extra = extraTickets(position, ticket, rules, grantedReinvestTickets(userId, claimKey));
        if (extra <= 0) return null;
        Map<String, Object> grant = raffle.grant(userId, "H4_REINVEST", eventCode + ":" + claimKey, extra, claimKey);
        return values("rewardType", "GENESIS_TICKET", "quantity", extra, "receiptId", grant.get("receiptId"), "positionNo", claimKey);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Map<String, Object> awardPurchaseTickets(Long userId, String eventCode, Map<String, Object> rules,
                                                   LocalDateTime startsAt, LocalDateTime endsAt, String orderNo) {
        long quantity = number(rules.getOrDefault("extraGenesisTickets", 0));
        if (quantity <= 0) return null;
        Map<String, Object> order = db.one("""
                SELECT o.order_no FROM nx_order o WHERE o.order_no=? AND o.user_id=? AND o.is_deleted=0
                  AND o.payment_status='PAID' AND o.order_status NOT IN ('REFUNDED','CHARGEBACK','CANCELLED')
                  AND o.paid_at>=? AND o.paid_at<?
                  AND EXISTS(SELECT 1 FROM nx_promotion_reservation r WHERE r.order_no=o.order_no
                    AND r.buyer_id=o.user_id AND r.activity_id=? AND r.status='COMMITTED')
                  AND NOT EXISTS(SELECT 1 FROM nx_promotion_refund_hold h WHERE h.order_no=o.order_no
                    AND h.status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED')) FOR UPDATE
                """, orderNo, userId, stored(startsAt), stored(endsAt), text(rules.get("promotionActivityId")));
        if (order == null || order.isEmpty()) return null;
        Map<String, Object> grant = raffle.grant(userId, "H4_PROMOTION", eventCode + ":" + orderNo, quantity, null, orderNo);
        return values("rewardType", "GENESIS_TICKET", "quantity", quantity, "receiptId", grant.get("receiptId"), "orderNo", orderNo);
    }

    private long grantedReinvestTickets(Long userId, String positionNo) {
        return db.count("SELECT COALESCE(SUM(quantity),0) FROM nx_genesis_raffle_ticket WHERE user_id=? AND source_type='H4_REINVEST' AND position_no=?", userId, positionNo);
    }

    private static long extraTickets(Map<String, Object> position, Map<String, Object> ticket, Map<String, Object> rules, long supplemented) {
        BigDecimal threshold = decimal(rules.get("thresholdUsdt"));
        long perThreshold = number(rules.get("ticketsPerThreshold"));
        require(threshold.signum() > 0 && perThreshold > 0, "H4_EVENT_TICKET_RULE_INVALID");
        if (ticket != null && !"AVAILABLE".equals(ticket.get("status"))) return 0;
        long total = Math.multiplyExact(decimal(position.get("amount_usdt")).divide(threshold, 0, RoundingMode.DOWN).longValueExact(), perThreshold);
        require(total <= Integer.MAX_VALUE, "H4_EVENT_TICKET_QUANTITY_INVALID");
        return Math.max(0, total - (ticket == null ? 0 : number(ticket.get("quantity"))) - supplemented);
    }

    private LocalDateTime utcNow() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static LocalDateTime stored(LocalDateTime utc) {
        return LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC), DateTimeFormatConfig.BUSINESS_ZONE);
    }
}
