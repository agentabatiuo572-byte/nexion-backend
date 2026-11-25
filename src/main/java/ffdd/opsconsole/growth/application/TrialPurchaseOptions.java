package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.growth.mapper.TrialPurchaseOptionsMapper;
import ffdd.opsconsole.growth.mapper.TrialPurchaseOptionsMapper.Product;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.shared.canonical.BusinessProductImages;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.StorefrontPurchaseGatePolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.canonical.mapper.PurchaseGateQuotaMapper.GateQuota;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TrialPurchaseOptions {
    public static final String WRITE_PERMISSION_NAME = "免费试用设置修改";
    private final TrialPurchaseOptionsMapper db;
    private final StorefrontProductReleasePolicy release;
    private final StorefrontPurchaseGatePolicy gatePolicy;
    private final CanonicalStateMapper commerce;
    private final AppOrderCommandMapper orderCommands;
    private final BusinessProductImages images;
    private final AuditLogService audit;
    private final ObjectMapper json;

    public record ReplaceRequest(List<String> productNos, Long version, String reason, String requestNo,
                                 Long confirmedActorId) {}

    public Map<String, Object> adminView(String target) {
        var options = db.options();
        var selected = selected(options);
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        for (Product product : db.products()) {
            candidates.put(product.productNo(), adminProduct(product, product.productNo()));
        }
        return fields("currentTrialProduct", target == null ? null :
                        candidates.getOrDefault(target, adminProduct(null, target)),
                "writePermissionName", WRITE_PERMISSION_NAME, "purchaseOptionsVersion", options.version(),
                "otherPurchaseProducts", selected.stream().map(no -> candidates.getOrDefault(no, adminProduct(null, no))).toList(),
                "purchaseCandidates", new ArrayList<>(candidates.values()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> replace(ReplaceRequest request) {
        String actor = AdminActorResolver.subjectId();
        if (actor == null || request == null || !actor.equals(String.valueOf(request.confirmedActorId()))) {
            throw new BizException(403, "TRIAL_OPTIONS_ACTOR_MISMATCH");
        }
        if (request.version() == null || request.version() < 0 || request.productNos() == null
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500
                || request.requestNo() == null || !request.requestNo().matches("[A-Za-z0-9:_-]{1,128}")) {
            throw new BizException(422, "TRIAL_OPTIONS_REQUEST_INVALID");
        }
        var requested = new LinkedHashSet<String>();
        for (String no : request.productNos()) {
            if (no == null || !no.matches("[A-Za-z0-9._-]{2,64}") || !requested.add(no)) {
                throw new BizException(422, "TRIAL_OPTIONS_PRODUCT_INVALID");
            }
        }
        var state = db.lockOptions();
        long actorId = Long.parseLong(actor);
        var signature = fields("productNos", request.productNos(), "version", request.version().toString(),
                "reason", request.reason());
        var previous = db.command(request.requestNo());
        if (previous != null) {
            if (previous.actorId() != actorId) throw new BizException(403, "TRIAL_OPTIONS_ACTOR_MISMATCH");
            if (!signature.equals(decode(previous.requestJson()))) {
                throw new BizException(409, "TRIAL_OPTIONS_REQUEST_CHANGED");
            }
            return ApiResult.ok(decode(previous.receiptJson()));
        }
        if (state.version() != request.version()) throw new BizException(409, "TRIAL_OPTIONS_VERSION_CONFLICT");
        var before = selected(state);
        for (String no : requested) {
            if (!before.contains(no)) {
                var product = db.product(no);
                String reason = otherListingBlock(product);
                if (reason == null && !product.productNo().equals(no)) reason = "PRODUCT_CODE_MISMATCH";
                if (reason != null) throw new TrialPurchaseConflict("TRIAL_OPTIONS_PRODUCT_UNAVAILABLE",
                        fields("productNo", no, "reason", reason));
            }
        }
        var receipt = fields("productNos", request.productNos(), "version", state.version() + 1);
        db.replace(encode(request.productNos()));
        db.record(request.requestNo(), actorId, encode(signature), encode(receipt));
        audit.recordRequired(AuditLogWriteRequest.builder().action("H2_TRIAL_PURCHASE_OPTIONS_CHANGED")
                .resourceType("TRIAL_POLICY").resourceId("purchase-options").bizNo(request.requestNo())
                .actorId(actorId).actorType("ADMIN").actorUsername(AdminActorResolver.resolve("admin:" + actor))
                .result("SUCCESS").detail(fields("before", before, "after", requested, "reason", request.reason().trim()))
                .build());
        return ApiResult.ok(receipt);
    }

    public List<String> selected() {
        return selected(db.options());
    }

    public boolean containsForPurchase(String productNo) {
        return selected(db.shareOptions()).contains(productNo);
    }

    private List<String> selected(TrialPurchaseOptionsMapper.Options options) {
        return read(options.productNos(), new TypeReference<List<String>>() {});
    }

    public Product product(String productNo) {
        return db.product(productNo);
    }

    public String listingBlock(Product product) {
        if (product == null || Boolean.FALSE.equals(product.storeVisible())
                || product.status() == null || !Set.of("ACTIVE", "ON_SALE").contains(product.status().toUpperCase(Locale.ROOT))) {
            return "PRODUCT_NOT_AVAILABLE";
        }
        if (product.priceUsdt() == null || product.priceUsdt().signum() <= 0) return "PRODUCT_PRICE_INVALID";
        var decision = release.evaluate(product.productNo(), product.unlockPhase());
        return decision.available() ? null : decision.reason();
    }

    public String otherListingBlock(Product product) {
        String reason = listingBlock(product);
        if (reason != null) return reason;
        return product.productType() != null && Set.of("DEVICE", "SERVER", "SHARE").contains(product.productType().toUpperCase(Locale.ROOT))
                ? null : "PRODUCT_TYPE_UNSUPPORTED";
    }

    private Map<String, Object> adminProduct(Product product, String code) {
        String reason = otherListingBlock(product);
        return fields("productNo", code, "name", product == null ? null : product.name(),
                "productType", product == null ? null : product.productType(),
                "inventoryMode", product == null ? null : product.inventoryMode(),
                "priceUsdt", product == null ? null : amount(product.priceUsdt()), "stock", product == null ? null : product.stock(),
                "status", product == null ? null : product.status(), "listingAllowed", reason == null,
                "visibleToUser", reason == null, "selectable", reason == null, "unavailableReason", reason);
    }

    public String stockOrConfigurationBlock(Product product) {
        String reason = AppCanonicalBoundaryService.storefrontConfigurationBlock(product.productType(),
                product.inventoryMode(), product.gpuModel(), product.vramTotalGb(), product.power());
        if (reason != null) return reason;
        return ProductInventoryMode.parse(product.inventoryMode()) == ProductInventoryMode.FINITE
                && (product.stock() == null || product.stock() < 1) ? "PRODUCT_OUT_OF_STOCK" : null;
    }

    public GateQuota checkGate(long owner, String productNo, boolean lock) {
        var gate = lock ? commerce.lockGateQuota(productNo) : commerce.findGateQuota(productNo);
        if (gate == null || gate.gateJson() == null || gate.gateJson().isBlank()) return null;
        var user = commerce.purchaseFacts(owner);
        var facts = user == null || user.rank() == null || user.activeDirect() == null || user.teamComputePoints() == null
                ? null : new StorefrontPurchaseGatePolicy.Facts(user.rank(), user.activeDirect(), user.teamComputePoints());
        var decision = gatePolicy.evaluate(gate.gateJson(), facts, gate.held());
        if (!decision.allowed()) throw new BizException(409, decision.code());
        if (!gatePolicy.hasQuota(gate.gateJson())) return null;
        if (gate.generation() < 1 || gatePolicy.lifetimeQuotaExhausted(gate.gateJson(), 1, gate.held())) {
            throw new BizException(409, "PURCHASE_GATE_SOLD_OUT");
        }
        return gate;
    }

    public void consumeGate(GateQuota gate, String orderNo, Long productId) {
        if (gate != null && (orderCommands.consumePaymentQuota(gate.skuId(), 1, gate.generation()) != 1
                || orderCommands.recordPaymentQuota(orderNo, productId, gate.generation()) != 1)) {
            throw new BizException(409, "PURCHASE_GATE_SOLD_OUT");
        }
    }

    public Map<String, Object> balance(long owner, String claim) {
        String pending = db.pendingBalance(owner, claim);
        if (pending == null) return null;
        var snapshot = decode(pending);
        snapshot.put("imageUrl", snapshotImage((String) snapshot.remove("imageSku"),
                (String) snapshot.remove("imageAssetId"), (String) snapshot.remove("imageObjectKey")));
        return snapshot;
    }

    public String balanceClaim(long owner, String key) {
        return db.balanceClaim(owner, key);
    }

    public void acceptBalance(long owner, String claim, String key, Map<String, Object> snapshot) {
        var artwork = artwork((String) snapshot.get("productNo"));
        snapshot.remove("imageUrl");
        if (artwork != null) {
            snapshot.put("imageSku", artwork.sku());
            snapshot.put("imageAssetId", artwork.assetId());
            snapshot.put("imageObjectKey", artwork.objectKey());
        }
        db.acceptBalance(owner, claim, key, encode(snapshot));
    }

    public Map<String, Object> device(String productNo) {
        var product = db.product(productNo);
        return fields("productNo", productNo, "name", product == null ? null : product.name(),
                "productType", product == null ? null : product.productType(), "imageUrl", image(productNo));
    }

    public String image(String productNo) {
        return images.url(artwork(productNo));
    }

    public BusinessProductImages.Artwork artwork(String productNo) {
        return productNo == null ? null : images.artwork(List.of(productNo)).get(productNo);
    }

    public String snapshotImage(String sku, String assetId, String objectKey) {
        return sku == null ? null : images.url(new BusinessProductImages.Artwork(sku, assetId, objectKey));
    }

    public static String amount(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.DOWN).toPlainString();
    }

    public static Map<String, Object> fields(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("TRIAL_OPTIONS_JSON_INVALID", invalid);
        }
    }

    private Map<String, Object> decode(String value) {
        return read(value, new TypeReference<Map<String, Object>>() {});
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("TRIAL_OPTIONS_JSON_INVALID", invalid);
        }
    }
}
