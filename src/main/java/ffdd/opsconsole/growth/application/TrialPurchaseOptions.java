package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.growth.mapper.TrialPurchaseOptionsMapper;
import ffdd.opsconsole.growth.mapper.TrialPurchaseOptionsMapper.Options;
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
    private static final BigDecimal CENT_USDT = new BigDecimal("0.01");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal HALF_MICRO = new BigDecimal("0.0000005");
    // The audit sanitizer's 4096-character limit includes fields added by the audit writer.
    private static final int AUDIT_DETAIL_BUDGET = 3500;
    private final TrialPurchaseOptionsMapper db;
    private final StorefrontProductReleasePolicy release;
    private final StorefrontPurchaseGatePolicy gatePolicy;
    private final CanonicalStateMapper commerce;
    private final AppOrderCommandMapper orderCommands;
    private final BusinessProductImages images;
    private final AuditLogService audit;
    private final ObjectMapper json;

    public record ReplaceRequest(List<String> productNos, Long version, String reason, String requestNo,
                                 Long confirmedActorId, List<PurchaseOption> items) {
        public ReplaceRequest(List<String> productNos, Long version, String reason, String requestNo,
                              Long confirmedActorId) {
            this(productNos, version, reason, requestNo, confirmedActorId, null);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PurchaseOption(String productNo, String discountRate, String discountCapUSD,
                                 String earlyPurchaseDiscountUSD) {
        public Map<String, String> effectivePolicy(Map<String, String> defaults) {
            var policy = new LinkedHashMap<>(defaults);
            if (discountRate != null) {
                policy.put("discountRate", discountRate);
                policy.put("discountCapUSD", discountCapUSD);
            }
            if (earlyPurchaseDiscountUSD != null) {
                policy.put("earlyPurchaseDiscountUSD", earlyPurchaseDiscountUSD);
            }
            return policy;
        }

        private Map<String, Object> values() {
            return fields("productNo", productNo, "discountRate", discountRate, "discountCapUSD", discountCapUSD,
                    "earlyPurchaseDiscountUSD", earlyPurchaseDiscountUSD);
        }
    }

    public Map<String, Object> adminView(String target) {
        var options = db.options();
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        for (Product product : db.products()) {
            candidates.put(product.productNo(), adminProduct(product, product.productNo()));
        }
        return fields("currentTrialProduct", target == null ? null :
                        candidates.getOrDefault(target, adminProduct(null, target)),
                "writePermissionName", WRITE_PERMISSION_NAME, "purchaseOptionsVersion", options.version(),
                "purchaseOptionsPricing", true,
                "otherPurchaseProducts", optionsByProduct(options).values().stream().map(option -> {
                    String no = option.productNo();
                    var product = new LinkedHashMap<>(candidates.getOrDefault(no, adminProduct(null, no)));
                    product.putAll(option.values());
                    return product;
                }).toList(),
                "purchaseCandidates", new ArrayList<>(candidates.values()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> replace(ReplaceRequest request) {
        String actor = AdminActorResolver.subjectId();
        if (actor == null || request == null || !actor.equals(String.valueOf(request.confirmedActorId()))) {
            throw new BizException(403, "TRIAL_OPTIONS_ACTOR_MISMATCH");
        }
        boolean pricingRequest = request.items() != null;
        boolean legacyRequest = request.productNos() != null;
        if (request.version() == null || request.version() < 0 || pricingRequest == legacyRequest
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500
                || request.requestNo() == null || !request.requestNo().matches("[A-Za-z0-9:_-]{1,128}")) {
            throw new BizException(422, "TRIAL_OPTIONS_REQUEST_INVALID");
        }
        var signature = fields(pricingRequest ? "items" : "productNos",
                pricingRequest ? request.items().stream().map(item -> item == null ? null : item.values()).toList()
                        : request.productNos(),
                "version", request.version().toString(), "reason", request.reason());
        var state = db.lockOptions();
        long actorId = Long.parseLong(actor);
        var previous = db.command(request.requestNo());
        if (previous != null) {
            if (previous.actorId() != actorId) throw new BizException(403, "TRIAL_OPTIONS_ACTOR_MISMATCH");
            if (!signature.equals(decode(previous.requestJson()))) {
                throw new BizException(409, "TRIAL_OPTIONS_REQUEST_CHANGED");
            }
            return ApiResult.ok(decode(previous.receiptJson()));
        }
        var requested = new LinkedHashMap<String, PurchaseOption>();
        var requestedItems = pricingRequest ? request.items()
                : request.productNos().stream().map(no -> new PurchaseOption(no, null, null, null)).toList();
        for (var item : requestedItems) {
            if (item == null || !validProductNo(item.productNo()) || requested.containsKey(item.productNo())) {
                throw new BizException(422, "TRIAL_OPTIONS_PRODUCT_INVALID");
            }
            if ((item.discountRate() == null) != (item.discountCapUSD() == null)) {
                throw new BizException(422, "TRIAL_OPTIONS_PRICING_INVALID");
            }
            requested.put(item.productNo(), new PurchaseOption(item.productNo(),
                    normalizePricing(item.discountRate(), "discountRate"),
                    normalizePricing(item.discountCapUSD(), "discountCapUSD"),
                    normalizePricing(item.earlyPurchaseDiscountUSD(), "earlyPurchaseDiscountUSD")));
        }
        var productNos = List.copyOf(requested.keySet());
        if (state.version() != request.version()) throw new BizException(409, "TRIAL_OPTIONS_VERSION_CONFLICT");
        var before = optionsByProduct(state);
        var after = new LinkedHashMap<String, PurchaseOption>();
        for (var item : requested.values()) {
            String no = item.productNo();
            var retained = before.get(no);
            var next = !pricingRequest && retained != null ? retained : item;
            if (!next.equals(retained)) {
                var product = db.product(no);
                String reason = otherListingBlock(product);
                if (reason == null && !product.productNo().equals(no)) reason = "PRODUCT_CODE_MISMATCH";
                if (reason != null) throw new TrialPurchaseConflict("TRIAL_OPTIONS_PRODUCT_UNAVAILABLE",
                        fields("productNo", no, "reason", reason));
            }
            after.put(no, next);
        }
        long newVersion = state.version() + 1;
        var receipt = fields("productNos", productNos, "version", newVersion);
        if (pricingRequest) receipt.put("items", after.values().stream().map(PurchaseOption::values).toList());
        var overrides = after.values().stream()
                .filter(item -> item.discountRate() != null || item.earlyPurchaseDiscountUSD() != null).toList();
        db.replace(encode(productNos), overrides.isEmpty() ? null : encode(overrides));
        db.record(request.requestNo(), actorId, encode(signature), encode(receipt));
        String reason = request.reason().trim();
        var auditDetail = fields("before", before.keySet(), "after", productNos,
                "beforeItems", before.values().stream().map(PurchaseOption::values).toList(),
                "afterItems", after.values().stream().map(PurchaseOption::values).toList(),
                "beforeVersion", state.version(), "afterVersion", newVersion, "reason", reason);
        var auditEntry = AuditLogWriteRequest.builder().action("H2_TRIAL_PURCHASE_OPTIONS_CHANGED")
                .resourceType("TRIAL_POLICY").resourceId("purchase-options").bizNo(request.requestNo())
                .actorId(actorId).actorType("ADMIN").actorUsername(AdminActorResolver.resolve("admin:" + actor))
                .result("SUCCESS");
        audit.recordRequired(auditEntry.detail(auditDetail).build());
        if (encode(auditDetail).length() > AUDIT_DETAIL_BUDGET) {
            var affected = new LinkedHashSet<>(before.keySet());
            affected.addAll(productNos);
            auditEntry.action("H2_TRIAL_PURCHASE_OPTIONS_ITEM");
            for (String no : affected) {
                var oldItem = before.get(no);
                var newItem = after.get(no);
                audit.recordRequired(auditEntry.detail(fields("productNo", no,
                        "before", oldItem == null ? null : oldItem.values(),
                        "after", newItem == null ? null : newItem.values(),
                        "beforeVersion", state.version(), "afterVersion", newVersion, "reason", reason)).build());
            }
        }
        return ApiResult.ok(receipt);
    }

    public List<String> selected() {
        return selected(db.options());
    }

    public List<PurchaseOption> selectedOptions() {
        return List.copyOf(optionsByProduct(db.options()).values());
    }

    static boolean validProductNo(String productNo) {
        return productNo != null && productNo.matches("[A-Za-z0-9._-]{1,64}");
    }

    public boolean containsForPurchase(String productNo) {
        return findForPurchase(productNo) != null;
    }

    public PurchaseOption findForPurchase(String productNo) {
        return optionsByProduct(db.shareOptions()).get(productNo);
    }

    private List<String> selected(Options options) {
        return read(options.productNos(), new TypeReference<List<String>>() {});
    }

    private Map<String, PurchaseOption> optionsByProduct(Options options) {
        var overrides = new LinkedHashMap<String, PurchaseOption>();
        if (options.pricingJson() != null) {
            for (var item : read(options.pricingJson(), new TypeReference<List<PurchaseOption>>() {})) {
                overrides.put(item.productNo(), item);
            }
        }
        var selected = new LinkedHashMap<String, PurchaseOption>();
        for (String no : selected(options)) {
            selected.put(no, overrides.getOrDefault(no, new PurchaseOption(no, null, null, null)));
        }
        return selected;
    }

    private static String normalizePricing(String raw, String key) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() > 120) throw new BizException(422, "TRIAL_OPTIONS_PRICING_INVALID");
        value = value.replace("%", "").replace(",", "");
        if (!"discountRate".equals(key)) value = value.replace("$", "");
        BigDecimal amount;
        try {
            amount = new BigDecimal(value.trim());
        } catch (NumberFormatException invalid) {
            throw new BizException(422, "TRIAL_OPTIONS_PRICING_INVALID");
        }
        BigDecimal max = "discountCapUSD".equals(key) ? MILLION : HUNDRED;
        if (amount.signum() < 0 || amount.compareTo(max) > 0
                || ("earlyPurchaseDiscountUSD".equals(key) && amount.stripTrailingZeros().scale() > 2)) {
            throw new BizException(422, "TRIAL_OPTIONS_PRICING_INVALID");
        }
        return amount.compareTo(HALF_MICRO) < 0 ? "0"
                : amount.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public Product product(String productNo) {
        return db.product(productNo);
    }

    public String listingBlock(Product product) {
        if (product == null || !Boolean.TRUE.equals(product.storeVisible())
                || product.status() == null || !Set.of("ACTIVE", "ON_SALE").contains(product.status().toUpperCase(Locale.ROOT))) {
            return "PRODUCT_NOT_AVAILABLE";
        }
        if (product.priceUsdt() == null || product.priceUsdt().signum() <= 0) return "PRODUCT_PRICE_INVALID";
        if (product.publishBlock() != null) return product.publishBlock();
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

    public static boolean isPositiveSubcent(BigDecimal value) {
        return value.signum() > 0 && value.compareTo(CENT_USDT) < 0;
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
