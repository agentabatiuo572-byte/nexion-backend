package ffdd.opsconsole.finance.purchase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.shared.canonical.HardwareQuotaPurchaseGuard;
import ffdd.opsconsole.shared.canonical.StorefrontPurchaseGatePolicy;
import ffdd.opsconsole.shared.config.HardwareQuotaPeriod;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static ffdd.opsconsole.finance.purchase.PurchaseQuotaReservation.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Component
@RequiredArgsConstructor
class PurchaseQuotaHolds {
    private final PurchasePaymentMapper db;
    private final ObjectMapper mapper;
    private final StorefrontPurchaseGatePolicy policy;

    record Item(long productId, String productNo, int quantity, boolean inventoryReserved,
                boolean quotaReserved, Long generation) { }

    List<Item> orderItems(long owner, String orderNo) {
        var order = db.one("""
                SELECT product_id,quantity,item_count,order_type,CAST(inventory_reserved AS SIGNED) inventory_reserved
                  FROM nx_order WHERE order_no=? AND user_id=? AND is_deleted=0
                """, orderNo, owner);
        if (order == null) throw new BizException(404, "ORDER_NOT_FOUND");
        var rows = db.rows("""
                SELECT i.product_id,i.product_no,i.quantity,CAST(i.lifetime_quota_reserved AS SIGNED) quota_reserved,
                       i.lifetime_quota_gate_generation generation,p.id canonical_id,p.product_no canonical_no
                  FROM nx_order_item i LEFT JOIN nx_product p ON p.id=i.product_id
                 WHERE i.order_no=? AND i.is_deleted=0 ORDER BY i.sort_order,i.id
                """, orderNo);
        int quantity = (int) number(order.get("quantity"));
        if (quantity < 1 || quantity > 100) throw invalidOrder();
        boolean inventoryReserved = order.get("inventory_reserved") == null || number(order.get("inventory_reserved")) == 1;
        if ("BUNDLE".equalsIgnoreCase(text(order.get("order_type")))) {
            if (rows.size() < 2 || rows.size() > 8 || rows.size() != number(order.get("item_count"))) throw invalidOrder();
            var items = rows.stream().map(row -> orderItem(row, inventoryReserved)).toList();
            if (items.stream().mapToInt(Item::quantity).sum() != quantity
                    || items.stream().map(Item::productId).distinct().count() != items.size()
                    || items.stream().noneMatch(item -> item.productId() == number(order.get("product_id")))) {
                throw invalidOrder();
            }
            return items;
        }
        if (!text(order.get("order_type")).isBlank() && !"SINGLE".equalsIgnoreCase(text(order.get("order_type")))) {
            throw invalidOrder();
        }
        long productId = number(order.get("product_id"));
        if (rows.isEmpty()) {
            var product = db.one("SELECT product_no FROM nx_product WHERE id=?", productId);
            if (product == null || !inventoryReserved) throw invalidOrder();
            return List.of(new Item(productId, text(product.get("product_no")), quantity, true, false, null));
        }
        if (rows.size() != 1) throw invalidOrder();
        Item item = orderItem(rows.get(0), inventoryReserved);
        if (item.productId() != productId || item.quantity() != quantity) throw invalidOrder();
        return List.of(item);
    }

    private static Item orderItem(Map<String, Object> row, boolean inventoryReserved) {
        long productId = number(row.get("product_id"));
        String productNo = text(row.get("canonical_no"));
        int quantity = (int) number(row.get("quantity"));
        if (productId < 1 || row.get("canonical_id") == null || number(row.get("canonical_id")) != productId || productNo.isBlank()
                || quantity < 1 || quantity > 100 || !productNo.equals(text(row.get("product_no")))) {
            throw invalidOrder();
        }
        return new Item(productId, productNo, quantity, inventoryReserved, number(row.get("quota_reserved")) == 1,
                row.get("generation") == null ? null : number(row.get("generation")));
    }

    private static BizException invalidOrder() {
        return new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
    }

    void requireOrder(PurchaseQuotaReservation reservation, long owner, String orderNo) {
        var actual = orderItems(owner, orderNo);
        if (actual.size() != reservation.lines().size()) throw conflict();
        for (int i = 0; i < actual.size(); i++) {
            var item = actual.get(i);
            var line = reservation.lines().get(i);
            if (item.productId() != line.productId() || !item.productNo().equals(line.productNo())
                    || item.quantity() != line.quantity() || item.quotaReserved() != (line.source() == Source.ORDER_PRESERVED)
                    || item.quotaReserved() && !Objects.equals(item.generation(), line.generation())) {
                throw conflict();
            }
        }
    }

    String reserve(String no, long owner, String kind, String businessId, LocalDateTime at, List<Item> items) {
        var lines = new ArrayList<Line>();
        for (var item : items) {
            Source source = Source.NONE;
            Long generation = null;
            String skuId = null;
            if (item.quotaReserved()) {
                source = Source.ORDER_PRESERVED;
                generation = item.generation();
                skuId = item.productNo();
            } else if (!item.inventoryReserved() && !"TRIAL_CONVERT".equals(kind)) {
                var gate = db.lockGateQuota(item.productNo());
                if (gate != null && policy.hasQuota(gate.gateJson())) {
                    if (gate.generation() < 1 || policy.lifetimeQuotaExhausted(gate.gateJson(), item.quantity(), gate.held())) {
                        throw new BizException(409, "PURCHASE_GATE_SOLD_OUT");
                    }
                    source = Source.PPI_NEW;
                    generation = gate.generation();
                    skuId = gate.skuId();
                }
                // ORDER_PAY checks the full gate under participant/product locks as part of its normal precheck.
            }
            lines.add(new Line(item.productId(), item.productNo(), item.quantity(), List.of(), source, skuId, generation));
        }
        var quantities = new LinkedHashMap<String, Integer>();
        for (var line : lines) quantities.put(line.productNo(), line.quantity());
        var tiers = HardwareQuotaPurchaseGuard.holdForIntent(db, owner, no, quantities, at);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            var tierIds = tiers.stream().filter(t -> line.productNo().equals(t.productNo())).map(t -> t.id()).toList();
            lines.set(i, new Line(line.productId(), line.productNo(), line.quantity(), tierIds,
                    line.source(), line.skuId(), line.generation()));
            if (line.source() == Source.PPI_NEW) {
                requireOne(db.write("""
                        INSERT INTO nx_purchase_e1_hold(intent_no,line_no,product_no,sku_id,gate_generation,quantity,status)
                        VALUES(?,?,?,?,?,?,'HELD')
                        """, no, i + 1, line.productNo(), line.skuId(), line.generation(), line.quantity()));
            }
        }
        return encode(new PurchaseQuotaReservation(no, owner, kind, businessId, State.HELD,
                at.toString(), HardwareQuotaPeriod.atUtc(at).fromUtc().toString(), lines));
    }

    PurchaseQuotaReservation read(Map<String, Object> intent) {
        Object raw = intent.get("quota_reservation_json");
        if (raw == null) return null;
        try {
            String json = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : text(raw);
            var snapshot = mapper.readValue(json, PurchaseQuotaReservation.class);
            if (snapshot == null || !text(intent.get("intent_no")).equals(snapshot.intentNo())
                    || number(intent.get("user_id")) != snapshot.owner() || !text(intent.get("kind")).equals(snapshot.kind())
                    || snapshot.businessId() == null || snapshot.businessId().isBlank() || snapshot.state() == null
                    || snapshot.lines() == null || snapshot.lines().isEmpty()) throw conflict();
            var at = LocalDateTime.parse(snapshot.decidedAtUtc());
            if (!HardwareQuotaPeriod.atUtc(at).fromUtc().toString().equals(snapshot.monthStartUtc())) throw conflict();
            var products = new HashSet<Long>();
            var tiers = new HashSet<Long>();
            for (var line : snapshot.lines()) {
                if (line.productId() < 1 || !products.add(line.productId()) || line.productNo().isBlank()
                        || line.quantity() < 1 || line.quantity() > 100 || line.source() == null
                        || line.source() == Source.NONE && (line.generation() != null || line.skuId() != null)
                        || line.source() != Source.NONE && !line.productNo().equals(line.skuId())
                        || line.source() == Source.PPI_NEW && (line.generation() == null || line.generation() < 1)
                        || line.tierIds().stream().anyMatch(id -> id < 1 || !tiers.add(id))) throw conflict();
            }
            return snapshot;
        } catch (JsonProcessingException | IllegalArgumentException | java.time.DateTimeException | NullPointerException ex) {
            throw conflict();
        }
    }

    void finish(PurchaseQuotaReservation reservation, State next, String orderNo, String reason) {
        if (reservation == null) return;
        if (reservation.state() == State.RELEASED && next == State.RELEASED) return;
        if (reservation.state() != State.HELD) throw conflict();
        boolean consumed = next == State.CONSUMED;
        String no = reservation.intentNo();

        var items = reservation.lines().stream().sorted(Comparator.comparing(Line::productNo)).toList();
        for (var line : items) {
            // Reuse the product/SKU ordering of purchases and E1 writes, including release paths.
            db.one("SELECT id FROM nx_product WHERE product_no=? FOR UPDATE", line.productNo());
            db.one("SELECT id FROM nx_admin_device_sku WHERE sku_id=? FOR UPDATE", line.productNo());
        }
        var tiers = new java.util.TreeMap<Long, Line>();
        for (var line : items) for (var id : line.tierIds()) tiers.put(id, line);
        for (long tier : tiers.keySet()) db.one("SELECT id FROM nx_team_hardware_quota_tier WHERE id=? FOR UPDATE", tier);
        if (db.rows("SELECT id FROM nx_team_hardware_quota_usage WHERE purchase_intent_no=?", no).size() != tiers.size()
                || db.rows("SELECT line_no FROM nx_purchase_e1_hold WHERE intent_no=?", no).size()
                    != items.stream().filter(line -> line.source() == Source.PPI_NEW).count()) throw conflict();

        for (var entry : tiers.entrySet()) {
            var line = entry.getValue();
            requireOne(db.write("""
                    UPDATE nx_team_hardware_quota_usage SET usage_type=?,status=?,order_no=?,remark=?
                     WHERE purchase_intent_no=? AND quota_tier_id=? AND user_id=? AND product_no=? AND quantity=?
                       AND occurred_at=? AND usage_type='RESERVED' AND status='ACTIVE' AND order_no IS NULL AND is_deleted=0
                    """, consumed ? "SOLD" : "RESERVED", consumed ? "ACTIVE" : "RELEASED", orderNo, reason,
                    no, entry.getKey(), reservation.owner(), line.productNo(), line.quantity(),
                    LocalDateTime.parse(reservation.decidedAtUtc())));
        }
        for (int i = 0; i < reservation.lines().size(); i++) {
            var line = reservation.lines().get(i);
            if (line.source() != Source.PPI_NEW) continue;
            requireOne(db.write("""
                    UPDATE nx_purchase_e1_hold SET status=? WHERE intent_no=? AND line_no=? AND product_no=? AND sku_id=?
                      AND gate_generation=? AND quantity=? AND status='HELD'
                    """, next.name(), no, i + 1, line.productNo(), line.skuId(), line.generation(), line.quantity()));
            if (!consumed) continue;
            var current = db.one("""
                    SELECT purchase_gate_generation generation FROM nx_admin_device_sku
                     WHERE sku_id=? AND is_deleted=0 FOR UPDATE
                    """, line.skuId());
            if (current != null && line.generation() == number(current.get("generation"))) {
                requireOne(db.write("""
                        UPDATE nx_admin_device_sku SET purchase_gate_json=JSON_SET(purchase_gate_json,'$.quotaSold',
                          CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)+?),updated_at=NOW()
                         WHERE sku_id=? AND purchase_gate_generation=? AND is_deleted=0
                           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)+?
                               <= CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaCap')) AS UNSIGNED)
                        """, line.quantity(), line.skuId(), line.generation(), line.quantity()));
            }
            requireOne(db.write("""
                    UPDATE nx_order_item SET lifetime_quota_reserved=1,lifetime_quota_gate_generation=?
                     WHERE order_no=? AND product_id=? AND product_no=? AND quantity=? AND is_deleted=0
                       AND lifetime_quota_reserved=0
                    """, line.generation(), orderNo, line.productId(), line.productNo(), line.quantity()));
        }
        requireOne(db.write("UPDATE nx_purchase_payment_intent SET quota_reservation_json=? WHERE intent_no=?",
                encode(reservation.withState(next)), no));
    }

    private static void requireOne(int rows) {
        if (rows != 1) throw conflict();
    }

    private String encode(PurchaseQuotaReservation snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw conflict();
        }
    }
}
