package ffdd.opsconsole.finance.purchase;

import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.device.application.HostingCenterService;
import ffdd.opsconsole.device.application.TestCloudActivationAdmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.device.application.AppTradeinService;
import ffdd.opsconsole.device.dto.*;
import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.growth.application.TrialPurchaseConflict;
import ffdd.opsconsole.growth.application.TrialPurchaseOptions;
import ffdd.opsconsole.finance.application.VietnamPaymentPolicy;
import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.canonical.HardwareQuotaPurchaseGuard;
import java.time.temporal.ChronoUnit;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static ffdd.opsconsole.finance.purchase.PurchasePaymentPolicyService.money;

/** Durable direct purchase intents. No wallet principal transaction belongs to this rail. */
@Service @RequiredArgsConstructor @Slf4j
public class PurchasePaymentService {
    @org.springframework.beans.factory.annotation.Autowired
    private HostingCenterService hostingCenters;

    @Value("${PURCHASE_QUOTA_HOLD_ENABLED:false}")
    private boolean quotaHoldEnabled;

    @Value("${NEXION_ORDER_USDT_TOPUP_ENABLED:false}")
    private boolean orderUsdtTopupEnabled;

    public boolean orderUsdtTopupEnabled() {
        return orderUsdtTopupEnabled;
    }

    private void requireDirectMethod(String kind, String method) {
        if (orderUsdtTopupEnabled && "ORDER_PAY".equals(kind)
                && ("CREGIS_USDT_BSC".equals(method) || "CREGIS_USDT_ERC20".equals(method))) {
            throw new BizException(409, "ORDER_USDT_USE_TOPUP");
        }
    }

    private final PurchasePaymentMapper db;
    private final PurchaseQuotaHolds quotaHolds;
    private final PurchasePaymentPolicyService policy;
    private final AppOrderCommandService orders;
    private final AppTradeinService tradeins;
    private final AppTrialLifecycleService trials;
    private final HdPayProperties hd;
    private final HdPayGateway hdGateway;
    private final CregisProperties cr;
    private final CregisGatewayRouter crGateway;
    private final BscDepositProof chain;
    private final VietnamPaymentMapper fx;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper jsonMapper;
    private final AuditLogService audit;
    private final AdminIdempotencyService commands;
    @lombok.NonNull
    private final TestCloudActivationAdmission testCloudAdmission;
    private final Clock clock;
    private static final Set<String> KINDS=Set.of("ORDER_PAY","TRIAL_CONVERT","TRADE_IN","CAPACITY_REPLACE","CAPACITY_KEEP");
    private static final String TRIAL_CLAIM_SNAPSHOT = "trialClaimNo";
    private static final Set<String> NON_DELIVERABLE_FUNDS = Set.of("PAYMENT_AMOUNT_MISMATCH",
            "PAYMENT_LATE_REVIEW", "PAYMENT_MULTIPLE_ARRIVALS", "PAYMENT_BEFORE_ALLOCATION",
            "PAYMENT_TIME_UNVERIFIED", "PAYMENT_REFUNDED_REVIEW");
    private Instant now(){return clock.instant();}
    private static final String SELECT="SELECT * FROM nx_purchase_payment_intent WHERE intent_no=?";
    // Match the purchase command unique index's case- and accent-insensitive key comparison.
    private static final String COMMAND_WEIGHT = "HEX(WEIGHT_STRING(CONVERT(? USING utf8mb4) COLLATE utf8mb4_0900_ai_ci))";
    private static final String CREATE_LOCK = "SHA2(CONCAT(DATABASE(), ':purchase-intent:', ?, ':', "
            + COMMAND_WEIGHT + ", ':', " + COMMAND_WEIGHT + "), 256)";
    /** 报价、建单、超时和订单找回共用。已交付的付款可以进入财务核对，仍属于已完成的购买。 */
    private static final String IN_PROGRESS="p.receipt_json IS NULL AND p.refund_receipt_json IS NULL AND p.status NOT IN ('DELIVERED','EXPIRED')";

    private <T> T tx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private <T> T writeTx(java.util.function.Supplier<T> work) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> work.get());
    }
    private void user(long owner,boolean lock){
        var row=db.one("SELECT id,status,CAST(sandbox AS UNSIGNED) sandbox FROM nx_user WHERE id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),owner);
        if(row==null||!"ACTIVE".equals(row.get("status"))||number(row.get("sandbox"))!=0)
            throw new BizException(403,"PURCHASE_PAYMENT_USER_UNAVAILABLE");
    }
    private static boolean cregis(Object method){return Set.of("CREGIS_USDT_BSC","CREGIS_USDT_ERC20","CREGIS_USDT_TRC20").contains(method);}
    private static CregisNetwork network(Object method){
        return switch(text(method)){
            case "CREGIS_USDT_BSC" -> CregisNetwork.BEP20;
            case "CREGIS_USDT_ERC20" -> CregisNetwork.ERC20;
            case "CREGIS_USDT_TRC20" -> CregisNetwork.TRC20;
            default -> throw new BizException(422,"PURCHASE_PAYMENT_METHOD_INVALID");
        };
    }
    private static String method(CregisNetwork network){return network==CregisNetwork.BEP20?"CREGIS_USDT_BSC":"CREGIS_USDT_"+network.network();}

    /** Channel availability for the original trial SKU, independent of its price and eligibility. */
    public List<String> availableTrialPaymentMethods() {
        Set<String> configured = new HashSet<>();
        try {
            for (var channel : maps(policy.current().get("channels"))) {
                if (Boolean.TRUE.equals(channel.get("configured"))) {
                    configured.add(text(channel.get("paymentMethod")));
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Could not read trial purchase payment policy", unavailable);
            return List.of("WALLET");
        }

        List<String> methods = new ArrayList<>();
        methods.add("WALLET");
        List<CregisNetwork> openNetworks = new ArrayList<>();
        for (var network : CregisNetwork.values()) {
            if (!configured.contains(method(network)) || !cregisDirectConfigured(network)) continue;
            try {
                cregisGate(network, true, false);
                openNetworks.add(network);
            } catch (BizException closed) {
                // The provision gate or network switch is closed.
            } catch (org.springframework.dao.DataAccessException unavailable) {
                log.warn("Could not read trial purchase payment switch for {}", network, unavailable);
            }
        }
        if (!openNetworks.isEmpty()) {
            try {
                var coins = crGateway.provider().projectCoins();
                for (var network : openNetworks) {
                    if (cregisAssetEnabled(network, coins)) methods.add(method(network));
                }
            } catch (CregisGatewayException unavailable) {
                log.warn("Could not read Cregis purchase assets for trial: {}", unavailable.getMessage());
            }
        }
        if (configured.contains("HDPAY_BANK")) {
            try {
                ready("HDPAY_BANK");
                bankQuoteRate(fx.findFxQuoteConfig());
                methods.add("HDPAY_BANK");
            } catch (BizException | HdPayGatewayException closed) {
                // The bank provider, server IP or quote configuration is unavailable.
            } catch (RuntimeException unavailable) {
                log.warn("Could not read trial purchase bank quote configuration", unavailable);
            }
        }
        return methods;
    }

    private boolean cregisDirectConfigured(CregisNetwork network) {
        return cr.getMode() == CregisProperties.Mode.PROVIDER && cr.isDepositEnabled()
                && cr.isDepositCreditEnabled() && cr.getProjectId() > 0 && cr.confirmations(network) >= 15
                && cr.networkConfigured(network) && cr.depositNetworkEnabled(network);
    }

    private static boolean cregisAssetEnabled(CregisNetwork network, List<CregisGateway.Coin> coins) {
        return coins.stream().anyMatch(coin -> coin.addressEnabled() && network.chainId().equals(coin.chainId())
                && network.tokenMatches(coin.tokenId()));
    }

    private void ready(String method){
        if("HDPAY_BANK".equals(method)){
            if(!hd.ready())throw new BizException(503,"HDPAY_CONFIGURATION_INCOMPLETE");
            HdPayServerIp.requirePublicIpv4(hd.getServerIp());
        }else if(cregis(method)){
            var network=network(method);
            if(!cregisDirectConfigured(network))
                throw new BizException(503,"CREGIS_DIRECT_PAYMENT_UNAVAILABLE");
            if(!cregisAssetEnabled(network, crGateway.provider().projectCoins()))
                throw new BizException(503,"CREGIS_DIRECT_PAYMENT_ASSET_UNAVAILABLE");
            cregisGate(network,true,false);
        }else throw new BizException(422,"PURCHASE_PAYMENT_METHOD_INVALID");
    }
    private void cregisGate(CregisNetwork network,boolean assignment,boolean lock){
        if(!cr.isDepositCreditEnabled()||!cr.depositNetworkEnabled(network))throw new BizException(409,"CREGIS_DIRECT_PAYMENT_PAUSED");
        var gate=db.one("SELECT state,CAST(assign_enabled AS UNSIGNED) assign_enabled,CAST(credit_enabled AS UNSIGNED) credit_enabled FROM nx_cregis_provision_gate WHERE id=1"+(lock?" FOR UPDATE":""));
        if(gate==null||!"IDLE".equals(gate.get("state"))||number(gate.get("credit_enabled"))!=1||assignment&&number(gate.get("assign_enabled"))!=1)
            throw new BizException(409,"CREGIS_DIRECT_PAYMENT_PAUSED");
        var networkGate=db.one("SELECT CAST(deposit_enabled AS UNSIGNED) deposit_enabled FROM nx_cregis_network_switch WHERE network=?"+(lock?" FOR UPDATE":""),network.network());
        if(networkGate==null || number(networkGate.get("deposit_enabled"))!=1)
            throw new BizException(409,"CREGIS_DIRECT_PAYMENT_PAUSED");
    }
    private Map<String,Object> dedicatedAddress(CregisNetwork network){
        var candidate=db.one("SELECT id,address FROM nx_cregis_deposit_address WHERE project_id=? AND chain_id=? AND state='UNASSIGNED' AND user_id IS NULL AND purpose='WALLET_TOPUP' ORDER BY id LIMIT 1",cr.getProjectId(),network.chainId());
        if(candidate==null)throw new BizException(503,"CREGIS_DIRECT_ADDRESS_POOL_UNAVAILABLE");
        String address=text(candidate.get("address"));
        if(!crGateway.provider().addressBelongs(network.chainId(),address)
                ||!crGateway.provider().zeroAddressBalance(network.currency(),address)||!chain.zeroUsdtBalance(network,address))
            throw new BizException(409,"CREGIS_DIRECT_ADDRESS_REQUIRES_REVIEW");
        var head=chain.allocationHead(network);if(!head.hash().equalsIgnoreCase(chain.blockHash(network,head.number())))throw new BizException(503,"CREGIS_DIRECT_ALLOCATION_ANCHOR_INVALID");
        candidate.put("allocationBlock",head.number());candidate.put("allocationHash",head.hash());candidate.put("proofExpiresAt",now().plusSeconds(5));
        return candidate;
    }
    public Map<String,Object> quote(long owner,Map<String,Object> input){
        String kind=text(input.get("kind")),method=text(input.get("paymentMethod"));
        if(!KINDS.contains(kind))throw new BizException(422,"PURCHASE_PAYMENT_KIND_INVALID");
        Map<String,Object> payload=map(input.get("payload"));
        // Credential material is rejected before serialisation, hashing, logging or persistence.
        if(payload.containsKey("paymentPassword")||payload.containsKey("credentialVersion")||payload.containsKey("attemptNo"))
            throw new BizException(422,"EXTERNAL_PAYMENT_CREDENTIAL_FORBIDDEN");
        user(owner,false);
        if (!"TRIAL_CONVERT".equals(kind)) {
            requireNoActiveIntent(owner, kind, payload, Map.of());
            requireDirectMethod(kind, method);
        }
        if ("CAPACITY_REPLACE".equals(kind)) throw new BizException(409,"CAPACITY_REPLACEMENT_PURCHASE_RETIRED");
        return writeTx(()->{
            user(owner,true);
            requireNoActiveIntent(owner, kind, payload, Map.of());
            requireDirectMethod(kind, method);
            Instant trialDeadline = "TRIAL_CONVERT".equals(kind) ? trials.requireExternalPaymentConversion(owner) : null;
            Instant pricingAt=now();
            var target=new ReplacementTarget();
            var pricing=pricing(owner,kind,payload,pricingAt,false,false,true,target,false,null,Map.of());
            if ("ORDER_PAY".equals(kind)) {
                payload.put("orderNo", pricing.get("orderNo"));
                requireOrdinaryOrderPayment(owner, payload);
            }
            if ("TRIAL_CONVERT".equals(kind)) trials.requireExternalPaymentProduct(owner, text(payload.get("productNo")));
            ready(method);
            BigDecimal subtotal=money(pricing.get("subtotalUsdt"));
            if(subtotal.signum()<=0)throw new BizException(409,"ZERO_PAYMENT_REQUIRES_WALLET");
            checkPurchaseQuota(owner,kind,payload,pricing,target);
            var current=policy.current();var channel=maps(current.get("channels")).stream()
                    .filter(c->method.equals(c.get("paymentMethod"))).findFirst().orElseThrow();
            if(!Boolean.TRUE.equals(channel.get("configured")))throw new BizException(503,"PURCHASE_PAYMENT_FEES_NOT_CONFIGURED");
            BigDecimal rate=money(channel.get("channelFeeRatePct")),fixed=money(channel.get("channelFeeFixed")),network=money(channel.get("networkConfirmFeeUsdt"));
            BigDecimal fee=subtotal.multiply(rate).divide(new BigDecimal("100")).add(fixed).setScale(2,RoundingMode.HALF_UP);
            BigDecimal total=subtotal.add(fee).add(network).setScale(2,RoundingMode.HALF_UP);
            String id=id("PPQ-");Instant deadline=deadline(kind,payload,pricingAt,trialDeadline);
            requireOpenPaymentWindow(deadline, trialDeadline);
            Instant expires=pricingAt.plusSeconds(900);if(expires.isAfter(deadline))expires=deadline;
            var quote=values("paymentQuoteId",id,"kind",kind,"paymentMethod",method,"network","HDPAY_BANK".equals(method)?"VND_BANK":network(method)==CregisNetwork.BEP20?"BSC":network(method).network(),
                    "subtotalUsdt",subtotal.toPlainString(),"businessAmountUsdt",pricing.get("businessAmountUsdt"),
                    "roundingAdjustmentUsdt",subtotal.subtract(new BigDecimal(text(pricing.get("businessAmountUsdt")))).setScale(6,RoundingMode.UNNECESSARY).toPlainString(),"channelFeeRatePct",rate.toPlainString(),"channelFeeFixed",fixed.toPlainString(),
                    "channelFeeUsdt",fee.toPlainString(),"networkConfirmFeeUsdt",network.toPlainString(),"totalUsdt",total.toPlainString(),
                    "feePolicyVersion",current.get("version"),"paymentFeesConfigured",true,"pricingAsOf",pricingAt.toString(),"expiresAt",expires.toString(),"deadlineAt",deadline.toString(),"confirmationGraceEndsAt",graceEnd(method,deadline).toString(),"currency","HDPAY_BANK".equals(method)?"VND":"USDT");
            if ("TRIAL_CONVERT".equals(kind)) quote.put("trialOffsetBasis", "ACCRUED_B90");
            if("HDPAY_BANK".equals(method)){
                var config=fx.findFxQuoteConfig();
                BigDecimal quoted=bankQuoteRate(config);
                quote.put("fxRateVndPerUsdt",quoted.toPlainString());quote.put("fxVersion",config.get("version"));
                quote.put("amountVnd",total.multiply(quoted).setScale(0,RoundingMode.HALF_UP).toPlainString());
            }
            var snapshot = quote;
            if ("TRIAL_CONVERT".equals(kind)) {
                snapshot = new LinkedHashMap<>(quote);
                snapshot.put(TRIAL_CLAIM_SNAPSHOT, map(pricing.get("trial")).get("claimNo"));
                snapshot.putAll(trials.externalDeviceSnapshot(owner, text(payload.get("productNo"))));
                snapshot.put("trialPricingPolicy", map(pricing.get("trial")).get("trialPricingPolicy"));
            }
            db.write("INSERT INTO nx_purchase_payment_quote(quote_id,user_id,kind,payment_method,payload_json,quote_json,pricing_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                    id,owner,kind,method,json(payload),json(snapshot),hash(pricing),timestamp(expires));
            return quote;
        });
    }
    private BigDecimal bankQuoteRate(Map<String, Object> config) {
        if (config == null) throw new BizException(503, "FX_QUOTE_UNAVAILABLE");
        BigDecimal rate = VietnamPaymentPolicy.quoteRate(new BigDecimal(text(config.get("baseRateVndPerUsdt"))),
                new BigDecimal(text(config.get("buySpreadPct"))));
        if (rate.signum() <= 0) throw new BizException(503, "FX_QUOTE_UNAVAILABLE");
        return rate;
    }

    private List<Map<String,Object>> activeIntents(Object owner,Object kind,String payload,String excluding){
        // Resolve legacy aliases with the actual order column's collation, not JSON/string equality.
        return db.rows("SELECT p.intent_no,p.kind,p.status,p.reason,p.confirmed_at,p.receipt_json FROM nx_purchase_payment_intent p WHERE p.user_id=? AND p.kind=? AND p.intent_no<>? AND "+IN_PROGRESS+" AND ((p.kind='ORDER_PAY' AND EXISTS(SELECT 1 FROM nx_order o WHERE o.order_no=JSON_UNQUOTE(JSON_EXTRACT(p.payload_json,'$.orderNo')) AND o.order_no=?)) OR (p.kind<>'ORDER_PAY' AND p.payload_json=CAST(? AS JSON)))",owner,kind,excluding,text(parse(payload).get("orderNo")),payload);
    }

    /**
     * B58-BE-01: 同一本人、同一业务身份已有在途付款单时，仍按既有 409
     * PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED 拒绝，但把**已核对**的原付款单号放进回包 data，
     * 客户端可凭它读回原单。写入路径不变，不新建单据、不扣款。
     */
    private void requireNoActiveIntent(long owner, String kind, Map<String,Object> payload, Map<String,Object> quote) {
        if ("TRIAL_CONVERT".equals(kind)) {
            trials.requireNoOriginalPurchase(owner, true);
            return;
        }
        String intentNo = hasActiveBusinessIntent(owner, kind, payload);
        if (intentNo == null) return;
        // 覆盖置换、换容量、保留全部设备与试用转购；普通订单(ORDER_PAY)保持原回包不变。
        if ("ORDER_PAY".equals(kind)) throw new BizException(409, "PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED");
        throw new ExistingPurchasePaymentIntentException(intentNo);
    }

    /** 已核对（owner + kind + 同一业务身份）且仍在途的原付款单号；不打单时返回 null。 */
    private String hasActiveBusinessIntent(long owner, String kind, Map<String,Object> payload) {
        if ("ORDER_PAY".equals(kind)) {
            var active = activeIntents(owner, kind, json(payload), "");
            return active.isEmpty() ? null : text(active.get(0).get("intent_no"));
        }

        var active = db.rows("SELECT p.intent_no,p.payload_json,p.quote_json FROM nx_purchase_payment_intent p"
                + " WHERE p.user_id=? AND p.kind=? AND " + IN_PROGRESS, owner, kind);
        if (active.isEmpty()) return null;
        var identity = replacementIdentity(kind, payload);
        if (identity == null) return null;
        return active.stream()
                .filter(intent -> identity.equals(replacementIdentity(kind, parse(intent.get("payload_json")))))
                .map(intent -> text(intent.get("intent_no")))
                .findFirst().orElse(null);
    }

    private String trialClaimNo(Map<String,Object> quote, AppTrialLifecycleService.ExternalPaymentClaim currentClaim) {
        if (quote.get(TRIAL_CLAIM_SNAPSHOT) instanceof String claimNo) return claimNo;
        return currentClaim != null && (quote.isEmpty() || currentClaim.matches(null, text(quote.get("pricingAsOf"))))
                ? currentClaim.claimNo() : null;
    }

    private record ReplacementIdentity(Long sourceDeviceId, String targetProductNo) { }

    private ReplacementIdentity replacementIdentity(String kind, Map<String,Object> payload) {
        Long source = switch (kind) {
            case "TRADE_IN" -> optionalLong(payload.get("sourceDeviceId"));
            case "CAPACITY_REPLACE" -> capacitySourceId(payload);
            default -> null;
        };
        if (!"CAPACITY_KEEP".equals(kind) && (source == null || source <= 0)) return null;

        Long targetId = "TRADE_IN".equals(kind) ? optionalLong(payload.get("targetProductId")) : null;
        String requestedNo = text(payload.get("targetProductNo")).trim();
        if (targetId == null && requestedNo.isEmpty()) return null;
        // Resolve the same catalog row even after the product becomes ineligible for a new purchase.
        var target = targetId == null
                ? db.one("SELECT product_no FROM nx_product WHERE product_no=?", requestedNo)
                : db.one("SELECT product_no FROM nx_product WHERE id=? AND (?='' OR product_no=?)",
                        targetId, requestedNo, requestedNo);
        return target == null ? null : new ReplacementIdentity(source, text(target.get("product_no")));
    }

    private static Long capacitySourceId(Map<String,Object> payload) {
        try {
            return Long.valueOf(text(payload.get("sourceDeviceId")).trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
    private Instant deadline(String kind,Map<String,Object> payload,Instant pricingAt,Instant trialDeadline){
        if(!"ORDER_PAY".equals(kind))return capAtTrialDeadline(pricingAt.plusSeconds(1800),trialDeadline);
        return orderDeadline(db.one("SELECT created_at,order_no FROM nx_order WHERE order_no=?",text(payload.get("orderNo"))));
    }
    private Instant orderDeadline(Map<String,Object> order){
        Instant deadline=instant(order.get("created_at")).plusSeconds(1800);
        var promotion=db.one("SELECT pay_by FROM nx_promotion_order_receipt WHERE order_no=?",order.get("order_no"));
        return promotion!=null&&instant(promotion.get("pay_by")).isBefore(deadline)?instant(promotion.get("pay_by")):deadline;
    }
    private Instant quoteDeadline(Map<String,Object> snapshot, Instant calculatedDeadline, Instant trialDeadline){
        Instant frozenDeadline = snapshot.get("deadlineAt") == null
                ? calculatedDeadline : Instant.parse(text(snapshot.get("deadlineAt")));
        return capAtTrialDeadline(frozenDeadline, trialDeadline);
    }
    private static Instant capAtTrialDeadline(Instant deadline, Instant trialDeadline) {
        return trialDeadline != null && trialDeadline.isBefore(deadline) ? trialDeadline : deadline;
    }
    private void requireOpenPaymentWindow(Instant deadline, Instant trialDeadline) {
        Instant current = now();
        if (trialDeadline != null && !trialDeadline.isAfter(current)) throw new BizException(409, "TRIAL_NOT_CONVERTIBLE");
        if (!deadline.isAfter(current)) throw new BizException(409, "ORDER_PAYMENT_EXPIRED");
    }
    static Instant graceEnd(Object method,Instant deadline){return deadline.plusSeconds("CREGIS_USDT_BSC".equals(method)?180:300);}
    public boolean lateTrialPaymentCanReselect(String intentNo, String method, Instant deadline, Instant providerDeadline,
            String reason, String status, Instant paidAt, Instant confirmedAt, Instant now) {
        return "MANUAL_REVIEW".equals(status) && "PAYMENT_LATE_REVIEW".equals(reason)
                && confirmedAt != null && paidAt != null && !paidAt.isAfter(confirmedAt)
                && !paidAt.isBefore(effectivePaymentDeadline(deadline, providerDeadline))
                && !now.isBefore(graceEnd(method, deadline))
                && verifiedLateTrialArrival(intentNo, method);
    }

    // Old quota-release processing could label any arrival as late. Check the persisted payment evidence.
    private boolean verifiedLateTrialArrival(String intentNo, String method) {
        if ("HDPAY_BANK".equals(method)) {
            return db.one("""
                    SELECT 1 FROM nx_purchase_payment_intent
                     WHERE intent_no=? AND paid_at>=created_at AND confirmed_bank_amount_vnd>0
                       AND confirmed_bank_amount_vnd=CAST(JSON_UNQUOTE(JSON_EXTRACT(quote_json,'$.amountVnd')) AS DECIMAL(20,0))
                    """, intentNo) != null;
        }
        if (!cregis(method)) return false;
        return db.one("""
                SELECT 1 FROM nx_purchase_payment_arrival a
                  JOIN nx_purchase_payment_intent p ON p.intent_no=a.intent_no AND p.provider_project=a.provider_project
                  JOIN nx_cregis_deposit_address d ON d.purchase_intent_no=a.intent_no AND d.chain_id=a.chain_id
                   AND d.purpose='PURCHASE_DIRECT' AND d.state='DIRECT_PURCHASE'
                 WHERE a.intent_no=? AND a.chain_id=?
                   AND a.block_number>d.allocation_block AND a.amount>0
                   AND a.amount=CAST(JSON_UNQUOTE(JSON_EXTRACT(p.quote_json,'$.totalUsdt')) AS DECIMAL(38,18))
                   AND NOT EXISTS (SELECT 1 FROM nx_purchase_payment_arrival other
                                   WHERE other.intent_no=a.intent_no AND other.id<>a.id)
                """, intentNo, network(method).chainId()) != null;
    }

    public static Instant effectivePaymentDeadline(Instant deadline, Instant providerDeadline) {
        return providerDeadline != null && providerDeadline.isBefore(deadline) ? providerDeadline : deadline;
    }

    private Instant deadline(Map<String,Object> row){return instant(row.get("expires_at"));}
    private Instant paymentDeadline(Map<String,Object> row){
        return effectivePaymentDeadline(deadline(row),
                row.get("provider_expires_at") == null ? null : instant(row.get("provider_expires_at")));
    }
    static String arrivalReason(Instant deadline,Instant graceEndsAt,Instant paidAt,Instant observedAt){
        if(!observedAt.isBefore(graceEndsAt))return "PAYMENT_LATE_REVIEW";
        if(paidAt==null)return observedAt.isBefore(deadline)?"":"PAYMENT_TIME_UNVERIFIED";
        return paidAt.isBefore(deadline)&&!paidAt.isAfter(observedAt)?"":"PAYMENT_LATE_REVIEW";
    }
    private void outcome(Map<String,Object> row,String status){
        String orderNo=row.get("receipt_json")==null?text(parse(row.get("payload_json")).get("orderNo")):text(parse(row.get("receipt_json")).get("orderNo"));
        String key="PAYMENT_TIMEOUT".equals(status)&&!orderNo.isBlank()?"ORDER:"+orderNo:"INTENT:"+text(row.get("intent_no"));
        db.write("INSERT IGNORE INTO nx_purchase_payment_result(notification_key,intent_no,outcome,user_id,order_no) VALUES(?,?,?,?,?)",key,row.get("intent_no"),status,row.get("user_id"),orderNo);
    }
    private Map<String,Object> pricing(long owner,String kind,Map<String,Object> payload,Instant pricingAt,
                                      boolean legacyTrialQuote, boolean legacyTrialVersion, boolean checkReplacementSource, ReplacementTarget target,
                                      boolean lockReplacement, PurchaseQuotaReservation reservation, Map<String, String> trialPolicy){
        hostingCenters.requirePool();
        switch(kind){
            case "ORDER_PAY":{
                var order=db.one("SELECT * FROM nx_order WHERE order_no=? AND user_id=? AND is_deleted=0 FOR UPDATE",text(payload.get("orderNo")),owner);
                if(order==null)throw new BizException(404,"ORDER_NOT_FOUND");
                if(!"PENDING".equals(order.get("payment_status"))||!"PENDING_PAYMENT".equals(order.get("order_status")))throw new BizException(409,"ORDER_NOT_PAYABLE");
                return values("businessAmountUsdt",new BigDecimal(text(order.get("amount_usdt"))).toPlainString(),"subtotalUsdt",money(order.get("amount_usdt")).toPlainString(),"orderNo",order.get("order_no"),
                        "subtotal",text(order.get("subtotal_usdt")),"discount",text(order.get("discount_usdt")),
                        "lines",db.rows("SELECT product_id,product_no,quantity,unit_price_usdt FROM nx_order_item WHERE order_no=? AND is_deleted=0 ORDER BY id",order.get("order_no")));
            }
            case "TRADE_IN":{
                var q = require(tradeins.externalPaymentQuote(owner,
                        new AppTradeinQuoteRequest(optionalLong(payload.get("sourceDeviceId")),
                                optionalLong(payload.get("targetProductId")), text(payload.get("targetProductNo"))),
                        lockReplacement, reservation));
                if(payload.get("expectedSourceVersion")==null||number(payload.get("expectedSourceVersion"))!=q.sourceRowVersion())
                    throw new BizException(409,"TRADEIN_SOURCE_VERSION_CONFLICT");
                if(checkReplacementSource)tradeins.requireRecyclableSource(owner,q.sourceDeviceId());
                if(target!=null)target.productNo=q.targetProductNo();
                return values("businessAmountUsdt",q.payableUsdt().toPlainString(),"subtotalUsdt",money(q.payableUsdt()).toPlainString(),"sourceVersion",q.sourceRowVersion(),"sourceDeviceId",q.sourceDeviceId(),
                        "targetProductId",q.targetProductId(),"targetPrice",q.targetPriceUsdt().toPlainString(),"discount",q.discountUsdt().toPlainString());
            }
            case "CAPACITY_REPLACE":case "CAPACITY_KEEP":{
                boolean replace = "CAPACITY_REPLACE".equals(kind);
                var q = require(tradeins.externalCapacityQuote(owner,
                        new AppCapacityReplaceQuoteRequest(text(payload.get("targetProductNo"))), lockReplacement, replace, reservation));
                if (replace && !"REPLACE_REQUIRED".equals(q.decision()))
                    throw new BizException(409, "CAPACITY_REPLACEMENT_SOURCE_CHANGED");
                if (checkReplacementSource && replace) {
                    Long requestedSourceId = capacitySourceId(payload);
                    if (requestedSourceId == null || !requestedSourceId.equals(q.sourceDeviceId())) {
                        throw new BizException(409, "CAPACITY_REPLACEMENT_SOURCE_CHANGED");
                    }
                    tradeins.requireRecyclableSource(owner, q.sourceDeviceId());
                }
                var source=replace&&q.sourceDeviceId()!=null?db.one("SELECT id,row_version,status,ownership_status FROM nx_user_device WHERE id=? AND user_id=?",q.sourceDeviceId(),owner):null;
                if(target!=null)target.productNo=q.targetProductNo();
                return values("businessAmountUsdt",q.payableUsdt().toPlainString(),"subtotalUsdt",money(q.payableUsdt()).toPlainString(),"targetProductId",q.targetProductId(),"source",source);
            }
            case "TRIAL_CONVERT":{
                var q = require(trials.externalPaymentPricing(owner, pricingAt, legacyTrialQuote, legacyTrialVersion, trialPolicy));
                if (!legacyTrialVersion) {
                    AppTrialLifecycleService.requireExternalPaymentProduct(text(payload.get("productNo")), text(q.get("productNo")));
                }
                if ((!legacyTrialVersion || payload.get("quoteVersion") != null)
                        && !text(payload.get("quoteVersion")).equals(text(q.get("quoteVersion")))) {
                    throw trialQuoteConflict("TRIAL_QUOTE_CHANGED", payload, q);
                }
                if(!legacyTrialQuote){
                    String expected=text(payload.get("expectedAmountUsdt"));
                    if(!expected.matches("[0-9]{1,7}(\\.[0-9]{1,2})?")||new BigDecimal(expected).compareTo(new BigDecimal("1000000"))>0)throw new BizException(409,"TRIAL_AMOUNT_INVALID");
                    if(money(q.get("amountUsdt")).compareTo(new BigDecimal(expected))>0)throw trialQuoteConflict("TRIAL_AMOUNT_MISMATCH", payload, q);
                }
                return values("businessAmountUsdt",text(q.get("amountUsdt")),"subtotalUsdt",money(q.get("amountUsdt")).toPlainString(),"quoteVersion",q.get("quoteVersion"),"productNo",payload.get("productNo"),"trial",q);
            }
            default:throw new BizException(422,"PURCHASE_PAYMENT_KIND_INVALID");
        }
    }

    private String reservationBusiness(String kind, Map<String, Object> pricing) {
        return switch (kind) {
            case "ORDER_PAY" -> text(pricing.get("orderNo"));
            case "TRIAL_CONVERT" -> text(map(pricing.get("trial")).get("claimNo"));
            case "TRADE_IN" -> text(pricing.get("sourceDeviceId")) + ":" + text(pricing.get("targetProductId"));
            case "CAPACITY_REPLACE" -> text(map(pricing.get("source")).get("id")) + ":" + text(pricing.get("targetProductId"));
            case "CAPACITY_KEEP" -> text(pricing.get("targetProductId"));
            default -> throw new BizException(422, "PURCHASE_PAYMENT_KIND_INVALID");
        };
    }

    private List<PurchaseQuotaHolds.Item> reservationItems(long owner, String kind, Map<String, Object> payload,
                                                          Map<String, Object> pricing, ReplacementTarget target) {
        return switch (kind) {
            case "ORDER_PAY" -> quotaHolds.orderItems(owner, text(pricing.get("orderNo")));
            case "TRADE_IN", "CAPACITY_REPLACE", "CAPACITY_KEEP" -> List.of(new PurchaseQuotaHolds.Item(
                    number(pricing.get("targetProductId")), target.productNo, 1, false, false, null));
            case "TRIAL_CONVERT" -> {
                var product = trials.lockExternalPaymentProduct(owner, text(payload.get("productNo")));
                yield List.of(new PurchaseQuotaHolds.Item(product.id(), product.productNo(), 1, false, false, null));
            }
            default -> throw new BizException(422, "PURCHASE_PAYMENT_KIND_INVALID");
        };
    }

    // Carries the canonical replacement target outside the hashed pricing map.
    private static final class ReplacementTarget {
        private String productNo;
    }

    private void checkPurchaseQuota(long owner, String kind, Map<String,Object> payload,
                                    Map<String,Object> pricing, ReplacementTarget target) {
        var occurredAtUtc = LocalDateTime.ofInstant(now(), ZoneOffset.UTC);
        switch (kind) {
            case "ORDER_PAY" -> orders.checkExternalPaymentQuota(owner, text(pricing.get("orderNo")), occurredAtUtc);
            case "TRADE_IN", "CAPACITY_REPLACE", "CAPACITY_KEEP" ->
                    HardwareQuotaPurchaseGuard.check(db, owner, target.productNo, 1, occurredAtUtc);
            case "TRIAL_CONVERT" -> trials.checkExternalPaymentQuota(owner, text(payload.get("productNo")), occurredAtUtc);
            default -> throw new BizException(422, "PURCHASE_PAYMENT_KIND_INVALID");
        }
    }

    private static <T> T require(ApiResult<T> response){if(response==null||response.getCode()!=0)throw new BizException(response==null?503:response.getCode(),response==null?"PURCHASE_PAYMENT_UNAVAILABLE":response.getMessage());return response.getData();}
    private static Long optionalLong(Object value){return value==null?null:number(value);}
    private void requireUnusedQuote(String quoteId) {
        if (db.one("SELECT intent_no FROM nx_purchase_payment_intent WHERE quote_id=?", quoteId) != null) {
            throw new BizException(409, "PURCHASE_PAYMENT_QUOTE_ALREADY_USED");
        }
    }

    private TrialPurchaseConflict trialQuoteConflict(String code, Map<String, Object> payload, Map<String, Object> current) {
        var quote = new LinkedHashMap<>(current);
        quote.remove("trialPricingPolicy");
        quote.replaceAll((key, value) -> value instanceof BigDecimal amount ? TrialPurchaseOptions.amount(amount) : value);
        return new TrialPurchaseConflict(code, values("claimNo", quote.get("claimNo"), "productNo", payload.get("productNo"),
                "quoteVersion", quote.get("quoteVersion"), "quote", quote));
    }

    private Map<String, String> frozenTrialPolicy(Map<String, Object> quote) {
        var policy = new LinkedHashMap<String, String>();
        map(quote.get("trialPricingPolicy")).forEach((key, value) -> policy.put(key, text(value)));
        return policy;
    }

    private record CreatePreflight(Map<String,Object> quote, Map<String,Object> replay, TrialPurchaseConflict rejection) { }

    private void requireOrdinaryOrderPayment(long owner, Map<String, Object> payload) {
        if (orders.requiresTrialConversionForPendingOrder(owner, text(payload.get("orderNo")))) {
            throw new BizException(409, AppTrialLifecycleService.ORDER_TRIAL_DEVICE_REQUIRES_CONVERSION);
        }
    }

    private CreatePreflight createPreflight(long owner, String key, String quoteId) {
        var originalQuote=db.one("SELECT * FROM nx_purchase_payment_quote WHERE quote_id=? AND user_id=?",quoteId,owner);
        if(originalQuote==null)throw new BizException(404,"PURCHASE_PAYMENT_QUOTE_NOT_FOUND");
        String kind = text(originalQuote.get("kind"));
        boolean trial = "TRIAL_CONVERT".equals(kind);
        boolean order = "ORDER_PAY".equals(kind);
        if (trial) user(owner, true);
        var existing=db.one("SELECT * FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND command_key=?",owner,originalQuote.get("kind"),key);
        if(existing!=null){
            if(!quoteId.equals(existing.get("quote_id")))throw new BizException(409,"IDEMPOTENCY_KEY_CONFLICT");
            return new CreatePreflight(originalQuote, existing, null);
        }
        if (trial) {
            var prior = externalRejection(owner, key, quoteId);
            if (prior != null) return new CreatePreflight(originalQuote, null, prior);
        }
        if (order) user(owner, true);
        // New replacement quotes are retired. The owner's original stored quote
        // can still proceed through the existing one-use, expiry and pricing checks.
        var payload = parse(originalQuote.get("payload_json"));
        requireNoActiveIntent(owner, kind, payload,
                parse(originalQuote.get("quote_json")));
        requireUnusedQuote(quoteId);
        requireDirectMethod(kind, text(originalQuote.get("payment_method")));
        if (order) requireOrdinaryOrderPayment(owner, payload);
        return new CreatePreflight(originalQuote, null, trial ? checkTrialConfirmation(owner, key, originalQuote) : null);
    }

    private TrialPurchaseConflict externalRejection(long owner, String key, String quoteId) {
        var prior = db.one("SELECT quote_id,receipt_json FROM nx_trial_external_rejection WHERE user_id=? AND command_key=?", owner, key);
        if (prior == null) return null;
        if (quoteId != null && !quoteId.equals(prior.get("quote_id"))) throw new BizException(409, "IDEMPOTENCY_KEY_CONFLICT");
        var receipt = parse(prior.get("receipt_json"));
        return new TrialPurchaseConflict(text(receipt.get("message")), map(receipt.get("data")));
    }

    private TrialPurchaseConflict checkTrialConfirmation(long owner, String key, Map<String, Object> storedQuote) {
        trials.requireExternalPaymentConversion(owner);
        var frozen = parse(storedQuote.get("quote_json"));
        var payload = parse(storedQuote.get("payload_json"));
        var current = require(trials.externalPaymentPricing(owner, now(), false, false, Map.of()));
        if (frozen.get(TRIAL_CLAIM_SNAPSHOT) instanceof String claim && !claim.equals(current.get("claimNo"))) {
            throw new BizException(409, "TRIAL_NOT_CONVERTIBLE");
        }
        if ("ACCRUED_B90".equals(frozen.get("trialOffsetBasis"))
                && text(payload.get("quoteVersion")).equals(text(current.get("quoteVersion")))) return null;
        var rejection = trialQuoteConflict("TRIAL_QUOTE_CHANGED", payload, current);
        db.write("INSERT INTO nx_trial_external_rejection(user_id,command_key,quote_id,receipt_json) VALUES(?,?,?,?)",
                owner, key, storedQuote.get("quote_id"), json(values("message", rejection.getMessage(), "data", rejection.errorData())));
        return rejection;
    }

    public Map<String,Object> create(long owner,String key,String quoteId){
        if(key==null||key.isBlank()||key.length()>128)throw new BizException(422,"IDEMPOTENCY_KEY_REQUIRED");
        var quote = db.one("SELECT kind FROM nx_purchase_payment_quote WHERE quote_id=? AND user_id=?", quoteId, owner);
        if (quote == null) throw new BizException(404, "PURCHASE_PAYMENT_QUOTE_NOT_FOUND");
        var reservation = reserveWithCreationLock(owner, text(quote.get("kind")), key, quoteId);
        if (reservation.get("rejection") instanceof TrialPurchaseConflict rejection) throw rejection;
        var intent = map(reservation.get("intent"));
        if (Boolean.TRUE.equals(reservation.get("winner"))) submit(intent);
        return get(owner, text(intent.get("intent_no")));
    }

    private Map<String, Object> reserveWithCreationLock(long owner, String kind, String key, String quoteId) {
        var source = Objects.requireNonNull(((DataSourceTransactionManager) transactions).getDataSource());
        // A session lock keeps the existing preflight and reservation transactions independent.
        Connection connection = null;
        try {
            connection = source.getConnection();
            if (creationLockCommand(connection, "SELECT GET_LOCK(" + CREATE_LOCK + ", 0)", owner, kind, key) == 0) {
                throw new BizException(409, "PURCHASE_PAYMENT_INTENT_PROCESSING");
            }
            try {
                return reserveIntent(owner, key, quoteId);
            } finally {
                try {
                    if (creationLockCommand(connection, "SELECT RELEASE_LOCK(" + CREATE_LOCK + ")", owner, kind, key) != 1) {
                        throw new SQLException("Purchase creation lock was lost");
                    }
                } catch (SQLException failure) {
                    // The reservation or rejection may already be committed. Preserve its original outcome.
                    if (source instanceof HikariDataSource pooled) pooled.evictConnection(connection);
                    log.warn("Purchase creation lock release failed; discarded lock session");
                }
            }
        } catch (SQLException failure) {
            if (connection != null && source instanceof HikariDataSource pooled) pooled.evictConnection(connection);
            throw new BizException(503, "PURCHASE_PAYMENT_CREATE_UNAVAILABLE");
        } finally {
            JdbcUtils.closeConnection(connection);
        }
    }

    private int creationLockCommand(Connection connection, String statement, long owner, String kind, String key)
            throws SQLException {
        try (var command = connection.prepareStatement(statement)) {
            command.setLong(1, owner);
            command.setString(2, kind);
            command.setString(3, key);
            try (var row = command.executeQuery()) {
                if (row.next()) {
                    int value = row.getInt(1);
                    if (!row.wasNull()) return value;
                }
                throw new SQLException("Purchase creation lock result is unavailable");
            }
        }
    }

    private Map<String, Object> reserveIntent(long owner, String key, String quoteId) {
        var preflight=writeTx(()->createPreflight(owner,key,quoteId));
        if (preflight.rejection() != null) throw preflight.rejection();
        if (preflight.replay() != null) return values("intent", preflight.replay(), "winner", false);
        var originalQuote=preflight.quote();
        boolean bsc=cregis(originalQuote.get("payment_method"));
        CregisNetwork network=bsc?network(originalQuote.get("payment_method")):null;
        ready(text(originalQuote.get("payment_method")));
        Map<String,Object> candidate=bsc?dedicatedAddress(network):null;
        // HDPay commits the immutable intent before create; BSC atomically dedicates an existing proven pool address.
        return writeTx(()->{
            if(bsc)cregisGate(network,true,true);
            if ("ORDER_PAY".equals(originalQuote.get("kind"))) {
                String orderNo = text(parse(originalQuote.get("payload_json")).get("orderNo"));
                if (db.one("SELECT id FROM nx_order WHERE order_no=? AND user_id=? AND is_deleted=0", orderNo, owner) == null) {
                    throw new BizException(404, "ORDER_NOT_FOUND");
                }
                orders.lockDirectPaymentOrderParticipants(orderNo);
            }
            user(owner,true);var quote=db.one("SELECT * FROM nx_purchase_payment_quote WHERE quote_id=? AND user_id=? FOR UPDATE",quoteId,owner);
            if(quote==null)throw new BizException(404,"PURCHASE_PAYMENT_QUOTE_NOT_FOUND");
            var prior=db.one("SELECT * FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND command_key=?",owner,quote.get("kind"),key);
            if(prior!=null){if(!quoteId.equals(prior.get("quote_id")))throw new BizException(409,"IDEMPOTENCY_KEY_CONFLICT");return values("intent",prior,"winner",false);}
            var frozenQuote=parse(quote.get("quote_json"));
            String kind=text(quote.get("kind"));
            var payload=parse(quote.get("payload_json"));
            if ("TRIAL_CONVERT".equals(kind)) {
                var rejection = externalRejection(owner, key, quoteId);
                if (rejection != null) return values("rejection", rejection);
            }
            requireNoActiveIntent(owner, kind, payload, frozenQuote);
            requireUnusedQuote(quoteId);
            if ("ORDER_PAY".equals(kind)) requireOrdinaryOrderPayment(owner, payload);
            if ("TRIAL_CONVERT".equals(kind)) {
                var rejection = checkTrialConfirmation(owner, key, quote);
                if (rejection != null) return values("rejection", rejection);
                trials.lockExternalPaymentProduct(owner, text(payload.get("productNo")));
            }
            Instant trialDeadline = "TRIAL_CONVERT".equals(kind) ? trials.requireExternalPaymentConversion(owner) : null;
            Instant pricingAt = Instant.parse(text(frozenQuote.get("pricingAsOf")));
            Instant calculatedDeadline = deadline(kind, payload, pricingAt, trialDeadline);
            requireOpenPaymentWindow(calculatedDeadline, trialDeadline);
            if(!instant(quote.get("expires_at")).isAfter(now()))throw new BizException(409,"PURCHASE_PAYMENT_QUOTE_EXPIRED");
            if(!Boolean.TRUE.equals(frozenQuote.get("paymentFeesConfigured")))throw new BizException(503,"PURCHASE_PAYMENT_FEES_NOT_CONFIGURED");
            // Share the policy mutex with D1 publication until this intent commits its immutable fee snapshot.
            var feeVersion=db.one("SELECT version FROM nx_purchase_payment_policy WHERE id=1 FOR SHARE");
            if(feeVersion==null||number(feeVersion.get("version"))!=number(frozenQuote.get("feePolicyVersion")))
                throw new BizException(409,"PURCHASE_PAYMENT_FEE_POLICY_CHANGED");
            var target=new ReplacementTarget();
            var pricing=pricing(owner, kind, payload, pricingAt, !frozenQuote.containsKey("trialOffsetBasis"),
                    !"ACCRUED_B90".equals(frozenQuote.get("trialOffsetBasis")), true, target, quotaHoldEnabled, null,
                    frozenTrialPolicy(frozenQuote));
            if(!hash(pricing).equals(quote.get("pricing_hash")))throw new BizException(409,"PURCHASE_PAYMENT_PRICING_CHANGED");
            ready(text(quote.get("payment_method")));
            if (!quotaHoldEnabled) {
                checkPurchaseQuota(owner,kind,payload,pricing,target);
            } else if ("ORDER_PAY".equals(kind)) {
                orders.checkExternalPaymentE1(owner, text(pricing.get("orderNo")));
            }
            Instant intentDeadline = quoteDeadline(frozenQuote, calculatedDeadline, trialDeadline);
            requireOpenPaymentWindow(intentDeadline, trialDeadline);
            String no=id("PPI-");
            String quotaJson = quotaHoldEnabled ? quotaHolds.reserve(no, owner, kind, reservationBusiness(kind, pricing),
                    LocalDateTime.ofInstant(now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS),
                    reservationItems(owner, kind, payload, pricing, target)) : null;
            db.write("INSERT INTO nx_purchase_payment_intent(intent_no,quote_id,user_id,kind,payment_method,command_key,payload_json,quote_json,pricing_hash,status,expires_at,provider_project,quota_reservation_json) VALUES(?,?,?,?,?,?,?,?,?,'CREATING',?,?,?)",
                    no,quoteId,owner,quote.get("kind"),quote.get("payment_method"),key,quote.get("payload_json"),quote.get("quote_json"),quote.get("pricing_hash"),timestamp(intentDeadline),cr.getProjectId(),quotaJson);
            if(bsc){
                var locked=db.one("SELECT id,address FROM nx_cregis_deposit_address WHERE id=? AND project_id=? AND chain_id=? AND state='UNASSIGNED' AND user_id IS NULL AND purpose='WALLET_TOPUP' FOR UPDATE",candidate.get("id"),cr.getProjectId(),network.chainId());
                if(locked==null||!candidate.get("address").equals(locked.get("address"))||!((Instant)candidate.get("proofExpiresAt")).isAfter(now()))
                    throw new BizException(409,"CREGIS_DIRECT_ADDRESS_ALLOCATION_RETRY");
                if(db.write("UPDATE nx_cregis_deposit_address SET state='DIRECT_PURCHASE',purpose='PURCHASE_DIRECT',purchase_intent_no=?,allocation_block=?,allocation_hash=?,updated_at=NOW(6) WHERE id=? AND state='UNASSIGNED' AND user_id IS NULL",no,candidate.get("allocationBlock"),candidate.get("allocationHash"),candidate.get("id"))!=1)
                    throw new BizException(409,"CREGIS_DIRECT_ADDRESS_ALLOCATION_RETRY");
                db.write("UPDATE nx_purchase_payment_intent SET address=?,scan_block=?,status='AWAITING_PAYMENT',version=version+1 WHERE intent_no=?",candidate.get("address"),number(candidate.get("allocationBlock"))+1,no);
            }
            return values("intent",db.one(SELECT,no),"winner",!bsc);
        });
    }
    private void submit(Map<String,Object> intent){
        String no=text(intent.get("intent_no"));String method=text(intent.get("payment_method"));
        if(!deadline(intent).isAfter(now())){
            db.write("UPDATE nx_purchase_payment_intent SET status='AWAITING_PAYMENT',reason='PAYMENT_NOT_SUBMITTED',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status='CREATING'",no);
            return;
        }
        // A crash anywhere after this CAS is an unknown submission; never create another identity.
        if(db.write("UPDATE nx_purchase_payment_intent SET status='SUBMIT_UNKNOWN',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status='CREATING'",no)!=1)return;
        try{
            if("HDPAY_BANK".equals(method)){
                var q=parse(intent.get("quote_json"));var page=hdGateway.createPayOrder(new HdPayGateway.CreatePayOrder(no,new BigDecimal(text(q.get("amountVnd"))),HdPayServerIp.requirePublicIpv4(hd.getServerIp())));
                if(page==null||!hd.isTrustedPaymentPage(page.url()))throw new BizException(503,"HDPAY_PAYMENT_PAGE_INVALID");
                db.write("UPDATE nx_purchase_payment_intent SET payment_url=?,status='AWAITING_PAYMENT',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status='SUBMIT_UNKNOWN'",page.url(),no);
            }
        }catch(RuntimeException failure){
            db.write("UPDATE nx_purchase_payment_intent SET reason='PROVIDER_CREATE_OUTCOME_UNKNOWN',updated_at=NOW(6) WHERE intent_no=? AND status='SUBMIT_UNKNOWN'",no);
        }
    }
    public Map<String,Object> lookup(long owner,String kind,String key){
        String intent = intentNoByCommand(owner, kind, key);
        if (intent != null) return get(owner, intent);
        var attempt = db.one("SELECT CAST(IS_USED_LOCK(" + CREATE_LOCK + ") IS NOT NULL AS UNSIGNED) held",
                owner, kind, key);
        // A creation that finished during the read may already have committed its result.
        intent = intentNoByCommand(owner, kind, key);
        if (intent != null) return get(owner, intent);
        if ("TRIAL_CONVERT".equals(kind)) {
            var rejection = externalRejection(owner, key, null);
            if (rejection != null) throw rejection;
        }
        if (number(attempt.get("held")) == 1) throw new BizException(409, "PURCHASE_PAYMENT_INTENT_PROCESSING");
        throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");
    }

    private String intentNoByCommand(long owner, String kind, String key) {
        var row = db.one("SELECT intent_no FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND command_key=?", owner, kind, key);
        return row == null ? null : text(row.get("intent_no"));
    }

    /**
     * 按本人订单号找回进行中的外部付款单:只读,不改任何付款单/订单状态,也不需要客户端留着原命令键。
     * 订单归属沿用 ORDER_PAY 报价分支的同一判据(他人订单与不存在的订单一样只有 404);
     * 进行中沿用 {@link #activeIntents} 的同一谓词 {@link #IN_PROGRESS}。
     * 回包三态可分:有单回 intentNo + state=IN_PROGRESS;确定没有回 state=NONE 且 intentNo=null;
     * 读取本身失败(非归属 404 的异常)统一 503 PURCHASE_PAYMENT_LOOKUP_UNAVAILABLE,不与「确定没有」混淆。
     */
    public Map<String,Object> activeByOrder(long owner,String orderNo){
        String no=orderNo==null?"":orderNo.trim();
        try{
            if(db.one("SELECT order_no FROM nx_order WHERE order_no=? AND user_id=? AND is_deleted=0",no,owner)==null)
                throw new BizException(404,"ORDER_NOT_FOUND");
            var intent=db.one("SELECT p.intent_no FROM nx_purchase_payment_intent p WHERE p.user_id=? AND p.kind='ORDER_PAY' AND "+IN_PROGRESS
                    +" AND EXISTS(SELECT 1 FROM nx_order o WHERE o.order_no=JSON_UNQUOTE(JSON_EXTRACT(p.payload_json,'$.orderNo')) AND o.order_no=?)"
                    +" ORDER BY p.created_at DESC,p.intent_no DESC LIMIT 1",owner,no);
            return values("orderNo",no,"state",intent==null?"NONE":"IN_PROGRESS","intentNo",intent==null?null:intent.get("intent_no"));
        }catch(BizException refused){throw refused;}
        catch(RuntimeException unavailable){throw new BizException(503,"PURCHASE_PAYMENT_LOOKUP_UNAVAILABLE");}
    }
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> orderTopupFacts(long owner, String orderNo, CregisNetwork network) {
        if (!orderUsdtTopupEnabled) throw new BizException(409, "ORDER_USDT_TOPUP_DISABLED");
        var facts = orders.orderTopupFacts(owner, orderNo.trim());
        Object intentNo = facts.remove("intentNo");
        Map<String,Object> openIntent = null;
        if (intentNo != null) {
            var intent = db.one(SELECT+" AND user_id=?", intentNo, owner);
            if (intent == null) throw new BizException(503, "ORDER_TOPUP_READ_FAILED");
            openIntent = Map.of("intentNo", intentNo, "paymentMethod", intent.get("payment_method"),
                    "paymentAllowed", view(intent, false).get("paymentAllowed"));
        }
        var recharge = policy.rechargePolicy();
        BigDecimal shortfall = new BigDecimal((String) facts.get("shortfallUsdt"));
        BigDecimal suggested = shortfall.signum() == 0 ? BigDecimal.ZERO
                : shortfall.add(recharge.feeUsdt()).max(recharge.minDepositUsdt());
        facts.put("openIntent", openIntent);
        facts.put("network", network.network());
        facts.put("minDepositUsdt", recharge.minDepositUsdt().toPlainString());
        facts.put("depositFeeUsdt", recharge.feeUsdt().toPlainString());
        facts.put("suggestedTransferUsdt", suggested.toPlainString());
        return facts;
    }

    /** Called only after the existing callback verifier accepted the provider signature. */
    public boolean observeHdCallback(HdPayCallbackVerifier.VerifiedCallback callback){
        var row=db.one(SELECT,callback.merchantOrderId());if(row==null)return false;
        if(!"HDPAY_BANK".equals(row.get("payment_method")))throw new BizException(409,"PURCHASE_PAYMENT_CALLBACK_RAIL_CONFLICT");
        refresh(row);return true;
    }
    /** Callback ownership is resolved against the dedicated intent address, never the topup pool. */
    public boolean observeBscCallback(Map<String,Object> callback){return observeCregisCallback(callback);}
    public boolean observeCregisCallback(Map<String,Object> callback){
        var network=CregisNetwork.fromAsset(text(callback.get("chain_id")),text(callback.get("token_id")));
        var row=db.one("SELECT * FROM nx_purchase_payment_intent WHERE address=? AND payment_method=? AND provider_project=?",network.normalizeAddress(text(callback.get("address"))),method(network),cr.getProjectId());
        if(row==null)return false;refresh(row);return true;
    }
    public boolean ownsBscAddress(String address){return ownsCregisAddress(CregisNetwork.BEP20,address);}
    public boolean ownsCregisAddress(CregisNetwork network,String address){
        return db.one("SELECT intent_no FROM nx_purchase_payment_intent WHERE address=? AND payment_method=? AND provider_project=?",network.normalizeAddress(address),method(network),cr.getProjectId())!=null;
    }
    public Boolean materializeBscProviderRow(CregisGateway.DepositRow incoming){return materializeCregisProviderRow(incoming);}
    /** The provider asset selects the intent rail; the same hex address on another chain is a different owner. */
    public Boolean materializeCregisProviderRow(CregisGateway.DepositRow incoming){
        var network=CregisNetwork.fromAsset(incoming.chainId(),incoming.tokenId());
        var intent=db.one("SELECT * FROM nx_purchase_payment_intent WHERE address=? AND payment_method=? AND provider_project=?",incoming.address(),method(network),cr.getProjectId());
        if(intent==null)return null;if(incoming.status()!=1)return incoming.status()!=0;
        var matching=chain.scan(network,incoming.blockHeight(),incoming.blockHeight(),List.of(incoming.address())).stream()
                .filter(log->log.txid().equalsIgnoreCase(incoming.txid())&&log.amount().compareTo(incoming.amount())==0).toList();
        if(matching.size()!=1)return false;
        var log=matching.get(0);var proof=chain.verifyExact(network,log.txid(),log.logIndex(),incoming.address(),incoming.amount(),incoming.blockHeight());
        if(proof==null)return false;
        var fresh=crGateway.provider().depositsByTxid(network,incoming.txid()).stream().filter(row->row.cid()==incoming.cid()&&row.status()==1
                &&network.addressEquals(row.address(),incoming.address())&&row.amount().compareTo(incoming.amount())==0
                &&network.chainId().equals(row.chainId())&&network.tokenMatches(row.tokenId())).toList();
        if(fresh.size()!=1)return false;
        recordBscArrival(intent,log,proof);refresh(db.one(SELECT,intent.get("intent_no")));return true;
    }
    public Map<String,Object> get(long owner,String no){
        var row=db.one(SELECT+" AND user_id=?",no,owner);if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");
        refresh(row);return view(db.one(SELECT,no),false);
    }
    /**
     * B58-BE-01: 本人凭原 intentNo 的只读快照。只按实际 owner 取行并做纯 view 投影：
     * 不调用 refresh（不查供应商、不写 QUERY_UNKNOWN、不关单、不 deliver），因此不建单、不扣款。
     * 既有的 GET /{intentNo} 仍保留主动查单与交付路径，合同不变。
     */
    public Map<String,Object> snapshot(long owner,String no){
        var row=db.one(SELECT+" AND user_id=?",no,owner);
        if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");
        return view(row,false);
    }
    private void refresh(Map<String,Object> row){
        if(row.get("receipt_json")!=null||row.get("refund_receipt_json")!=null){
            try {
                if(cregis(row.get("payment_method"))){
                    if(row.get("receipt_json")!=null)auditCanonical(row);
                    observeBsc(row);
                }else if(row.get("refund_receipt_json")==null)observeHd(row);
            }catch(RuntimeException unavailable){}
            return;
        }
        try{
            boolean observed="PAYMENT_NOT_SUBMITTED".equals(row.get("reason"));
            if(!observed){if("HDPAY_BANK".equals(row.get("payment_method")))observed=observeHd(row);else{observeBsc(row);observed=true;}}
            if(!observed)reconciliationUnknown(row);
        }catch(RuntimeException unavailable){reconciliationUnknown(row);}

        var current=db.one(SELECT,row.get("intent_no"));
        if(isLateOrderReview(current)||current.get("confirmed_at")==null&&!now().isBefore(graceEnd(current.get("payment_method"),deadline(current))))expire(current);
        else if(current.get("confirmed_at")!=null&&Set.of("FUNDS_CONFIRMED","MANUAL_REVIEW").contains(text(current.get("status"))))deliver(text(row.get("intent_no")),false);
    }
    private static boolean isLateOrderReview(Map<String,Object> row){
        return "ORDER_PAY".equals(row.get("kind"))&&"MANUAL_REVIEW".equals(row.get("status"))
                &&"PAYMENT_LATE_REVIEW".equals(row.get("reason"))&&row.get("confirmed_at")!=null&&row.get("receipt_json")==null;
    }
    private void reconciliationUnknown(Map<String,Object> row){
        // Ending a commerce order never declares unresolved provider funds settled.
        db.write("UPDATE nx_purchase_payment_intent SET reconciliation_status='QUERY_UNKNOWN',updated_at=NOW(6) WHERE intent_no=? AND confirmed_at IS NULL AND receipt_json IS NULL",row.get("intent_no"));
    }
    private void expire(Map<String,Object> snapshot){
        var expiration=new TransactionTemplate(transactions);
        expiration.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        // Participant discovery may read before waiting for the buyer; later successor checks must be current.
        expiration.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        expiration.execute(status->{
            var row=db.one(SELECT+" FOR UPDATE",snapshot.get("intent_no"));
            if(row==null||row.get("receipt_json")!=null||row.get("refund_receipt_json")!=null)return null;
            boolean lateReview=isLateOrderReview(row);
            if(!lateReview&&(row.get("confirmed_at")!=null||"MANUAL_REVIEW".equals(row.get("status"))))return null;
            String orderNo="ORDER_PAY".equals(row.get("kind"))?text(parse(row.get("payload_json")).get("orderNo")):null;
            // Match issuer/pay/cancel: every ordered participant precedes the buyer mutex.
            if(orderNo!=null)orders.lockDirectPaymentOrderParticipants(orderNo);
            db.one("SELECT id FROM nx_user WHERE id=? FOR UPDATE",row.get("user_id"));
            Instant cutoff=deadline(row);
            if(orderNo!=null){
                // The user mutex makes this a current business-state check without reversing promotion -> order locks.
                var order=db.one("SELECT order_no,created_at,payment_status,order_status FROM nx_order WHERE order_no=? AND user_id=?",orderNo,row.get("user_id"));
                if(order==null)throw new BizException(409,"PURCHASE_PAYMENT_TIMEOUT_ORDER_CONFLICT");
                if(lateReview && "EXPIRED".equals(order.get("payment_status")) && "EXPIRED".equals(order.get("order_status"))){
                    releaseLateOrderQuota(row);
                    return null;
                }
                if(!"PENDING".equals(order.get("payment_status"))||!"PENDING_PAYMENT".equals(order.get("order_status")))return null;
                cutoff=orderDeadline(order);
            }
            if(now().isBefore(lateReview?cutoff:graceEnd(row.get("payment_method"),cutoff)))return null;
            if ("TRIAL_CONVERT".equals(row.get("kind")) && "QUERY_UNKNOWN".equals(row.get("reconciliation_status"))) return null;
            // Trial timeout releases only this intent's quota. Its claim has a separate, claim-scoped payment hold.
            if (!"TRIAL_CONVERT".equals(row.get("kind"))) {
                var active=activeIntents(row.get("user_id"),row.get("kind"),text(row.get("payload_json")),text(row.get("intent_no")));
                if(active.stream().anyMatch(intent->!isLateOrderReview(intent)))return null;
            }
            if (!lateReview && !"EXPIRED".equals(row.get("status"))) {
                int changed = db.write("UPDATE nx_purchase_payment_intent SET status='EXPIRED',reason='PAYMENT_TIMEOUT',expires_at=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND confirmed_at IS NULL AND receipt_json IS NULL AND status IN ('CREATING','SUBMIT_UNKNOWN','AWAITING_PAYMENT')",
                        timestamp(cutoff), row.get("intent_no"));
                if (changed != 1) return null;
                quotaHolds.finish(quotaHolds.read(row), PurchaseQuotaReservation.State.RELEASED, null, "PAYMENT_TIMEOUT");
            }
            if(lateReview)releaseLateOrderQuota(row);
            if(orderNo!=null&&!orders.expireDirectPaymentOrder(number(row.get("user_id")),orderNo))throw new BizException(409,"PURCHASE_PAYMENT_TIMEOUT_ORDER_CONFLICT");
            // A confirmed late payment keeps its evidence and review notification.
            if (!lateReview && (orderNo != null || !"EXPIRED".equals(row.get("status")))) outcome(row,"PAYMENT_TIMEOUT");
            return null;
        });
    }
    private void releaseLateOrderQuota(Map<String,Object> row){
        var reservation=quotaHolds.read(row);
        if(reservation!=null && reservation.state()==PurchaseQuotaReservation.State.HELD)
            quotaHolds.finish(reservation,PurchaseQuotaReservation.State.RELEASED,null,"PAYMENT_LATE_REVIEW");
    }
    private boolean observeHd(Map<String,Object> row){
        if(!hd.ready())return false;String no=text(row.get("intent_no"));var observed=hdGateway.queryPayOrder(no);
        if(observed==null||!no.equals(observed.merchantOrderId())||observed.providerOrderId()==null||!"BANKQR".equalsIgnoreCase(observed.payType()))return false;
        Instant observedAt=now();
        return writeTx(()->{
            var locked=db.one(SELECT+" FOR UPDATE",no);
            if(locked.get("refund_receipt_json")!=null)return true;
            db.write("UPDATE nx_purchase_payment_intent SET reconciliation_status=? WHERE intent_no=? AND confirmed_at IS NULL AND receipt_json IS NULL",observed.orderStatus()==5?"PROVIDER_FAILED":"OBSERVED_PENDING",no);
            if(observed.expirationTime()!=null){
                db.write("UPDATE nx_purchase_payment_intent SET provider_expires_at=? WHERE intent_no=?",timestamp(observed.expirationTime()),no);
                locked.put("provider_expires_at",timestamp(observed.expirationTime()));
            }
            if(observed.orderStatus()==4){
                if(locked.get("provider_reference")!=null&&!observed.providerOrderId().equals(locked.get("provider_reference")))return false;
                if(!"PAYMENT_REFUNDED_REVIEW".equals(locked.get("reason")))
                    confirm(no,observed.providerOrderId(),"PAYMENT_REFUNDED_REVIEW",null,observedAt);
            }
            else if(observed.orderStatus()==3){
                boolean verifiedAmount = observed.paidAt() != null && observed.transAmt() != null
                        && new BigDecimal(text(parse(locked.get("quote_json")).get("amountVnd"))).compareTo(observed.transAmt()) == 0;
                if(locked.get("confirmed_at")!=null){
                    if (verifiedAmount && observed.providerOrderId().equals(locked.get("provider_reference"))
                            && locked.get("paid_at") != null && observed.paidAt().equals(instant(locked.get("paid_at")))) {
                        db.write("UPDATE nx_purchase_payment_intent SET confirmed_bank_amount_vnd=? WHERE intent_no=? AND confirmed_bank_amount_vnd IS NULL",
                                observed.transAmt(), no);
                    }
                    return true;
                }
                String reason=observed.paidAt()!=null&&observed.paidAt().isBefore(instant(locked.get("created_at")))?"PAYMENT_BEFORE_ALLOCATION":
                        observed.transAmt()==null||new BigDecimal(text(parse(locked.get("quote_json")).get("amountVnd"))).compareTo(observed.transAmt())!=0?"PAYMENT_AMOUNT_MISMATCH":arrivalReason(paymentDeadline(locked),graceEnd(locked.get("payment_method"),deadline(locked)),observed.paidAt(),observedAt);
                confirm(no, observed.providerOrderId(), arrivalReasonAfterQuotaRelease(locked, reason),
                        observed.paidAt(), observedAt);
                if (verifiedAmount) {
                    db.write("UPDATE nx_purchase_payment_intent SET confirmed_bank_amount_vnd=? WHERE intent_no=? AND provider_reference=?",
                            observed.transAmt(), no, observed.providerOrderId());
                }
            }else if(observed.orderStatus()==5){
                int changed = db.write("UPDATE nx_purchase_payment_intent SET status='EXPIRED',reason='PAYMENT_FAILED',provider_reference=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND confirmed_at IS NULL AND receipt_json IS NULL AND status IN ('CREATING','SUBMIT_UNKNOWN','AWAITING_PAYMENT')",observed.providerOrderId(),no);
                if (changed == 1) {
                    quotaHolds.finish(quotaHolds.read(locked), PurchaseQuotaReservation.State.RELEASED, null, "PAYMENT_FAILED");
                }
            }else if(observed.orderStatus()==1&&hd.isTrustedPaymentPage(observed.appLink())){
                db.write("UPDATE nx_purchase_payment_intent SET status='AWAITING_PAYMENT',payment_url=?,provider_reference=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status IN ('CREATING','SUBMIT_UNKNOWN','AWAITING_PAYMENT')",observed.appLink(),observed.providerOrderId(),no);
            }
            return true;
        });
    }
    private void observeBsc(Map<String,Object> row){
        var network=network(row.get("payment_method"));
        String address=text(row.get("address"));if(address.isBlank()||cr.getMode()!=CregisProperties.Mode.PROVIDER||number(row.get("provider_project"))!=cr.getProjectId())return;
        long from=number(row.get("scan_block")),head=chain.finalizedHeight(network,Math.max(15,cr.confirmations(network)));
        if(head<from)return;long to=Math.min(head,from+Math.min(399,cr.getChainScanBlocks()-1));
        for(var incoming:chain.scan(network,from,to,List.of(address))){
            var proof=chain.verifyExact(network,incoming.txid(),incoming.logIndex(),address,incoming.amount(),incoming.blockNumber());if(proof==null)return;
            var trades=crGateway.provider().depositsByTxid(network,incoming.txid()).stream().filter(t->t.status()==1&&network.chainId().equals(t.chainId())
                    &&network.tokenMatches(t.tokenId())&&network.addressEquals(address,t.address())&&incoming.amount().compareTo(t.amount())==0).toList();
            if(trades.size()!=1)return;
            recordBscArrival(row,incoming,proof);
        }
        db.write("UPDATE nx_purchase_payment_intent SET scan_block=GREATEST(scan_block,?),updated_at=NOW(6) WHERE intent_no=?",to+1,row.get("intent_no"));
    }
    private void recordBscArrival(Map<String,Object> intent,BscDepositProof.Observation incoming,BscDepositProof.TransferProof proof){
        var network=network(intent.get("payment_method"));
        String no=text(intent.get("intent_no"));
        tx(()->{
            var locked=db.one(SELECT+" FOR UPDATE",no);
            var allocation=db.one("SELECT allocation_block,allocation_hash FROM nx_cregis_deposit_address WHERE purchase_intent_no=? AND chain_id=? AND purpose='PURCHASE_DIRECT' AND state='DIRECT_PURCHASE'",no,network.chainId());
            if(allocation==null||!text(allocation.get("allocation_hash")).equalsIgnoreCase(chain.blockHash(network,number(allocation.get("allocation_block")))))
                throw new BizException(409,"CREGIS_DIRECT_ALLOCATION_ANCHOR_INVALID");
            int inserted=db.write("INSERT IGNORE INTO nx_purchase_payment_arrival(intent_no,provider_project,chain_id,txid,log_index,amount,block_number,block_hash,confirmations,from_address,status) VALUES(?,?,?,?,?,?,?,?,?,?,'CONFIRMED')",no,cr.getProjectId(),network.chainId(),incoming.txid(),incoming.logIndex(),incoming.amount(),proof.blockNumber(),proof.blockHash(),proof.confirmations(),proof.fromAddress());
            if(inserted==1){
                if(locked.get("refund_receipt_json")!=null){
                    db.write("UPDATE nx_purchase_payment_arrival SET status='MANUAL_REVIEW' WHERE provider_project=? AND chain_id=? AND txid=? AND log_index=?",cr.getProjectId(),network.chainId(),incoming.txid(),incoming.logIndex());
                    db.write("UPDATE nx_purchase_payment_intent SET status='MANUAL_REVIEW',reason='PAYMENT_AFTER_REFUND_REVIEW',reconciliation_status='REVIEW',version=version+1,updated_at=NOW(6) WHERE intent_no=?",no);
                    outcome(locked,"MANUAL_REVIEW");
                    return null;
                }
                Instant paidAt=chain.blockTime(network,proof.blockNumber(),proof.blockHash()),observedAt=now();
                BigDecimal expected=new BigDecimal(text(parse(locked.get("quote_json")).get("totalUsdt")));
                String reason=incoming.blockNumber()<=number(allocation.get("allocation_block"))?"PAYMENT_BEFORE_ALLOCATION":incoming.amount().compareTo(expected)!=0?"PAYMENT_AMOUNT_MISMATCH":arrivalReason(paymentDeadline(locked),graceEnd(locked.get("payment_method"),deadline(locked)),paidAt,observedAt);
                if(locked.get("confirmed_at")!=null)reason="PAYMENT_MULTIPLE_ARRIVALS";
                reason = arrivalReasonAfterQuotaRelease(locked, reason);
                if(!reason.isBlank())db.write("UPDATE nx_purchase_payment_arrival SET status='MANUAL_REVIEW' WHERE provider_project=? AND chain_id=? AND txid=? AND log_index=?",cr.getProjectId(),network.chainId(),incoming.txid(),incoming.logIndex());
                confirm(no,incoming.txid()+":"+incoming.logIndex(),reason,paidAt,observedAt);
            }return null;
        });
    }
    private void auditCanonical(Map<String,Object> intent){
        var network=network(intent.get("payment_method"));
        if("PAYMENT_REORG_REVIEW".equals(intent.get("reason")))return;
        for(var arrival:db.rows("SELECT * FROM nx_purchase_payment_arrival WHERE intent_no=?",intent.get("intent_no"))){
            var proof=chain.inspectCanonical(network,text(arrival.get("txid")),(int)number(arrival.get("log_index")),text(intent.get("address")),
                    new BigDecimal(text(arrival.get("amount"))),number(arrival.get("block_number")),text(arrival.get("block_hash")),
                    arrival.get("from_address")==null?null:text(arrival.get("from_address")));
            if(proof==null)continue;
            var current=proof.canonical();
            boolean changed=proof.positivelyVerifiedAbsent()||current!=null&&(current.logIndex()!=number(arrival.get("log_index"))
                    ||current.blockNumber()!=number(arrival.get("block_number"))||!current.blockHash().equalsIgnoreCase(text(arrival.get("block_hash"))));
            if(!changed)continue;
            // Positive chain evidence closes the existing financial risk gate; RPC failure alone never does.
            db.write("UPDATE nx_cregis_provision_gate SET state='BLOCKED',assign_enabled=0,credit_enabled=0,payout_enabled=0,version=version+1,switch_reason='PURCHASE_PAYMENT_REORG' WHERE id=1");
            tx(()->{
                db.write("UPDATE nx_purchase_payment_intent SET status='MANUAL_REVIEW',reason='PAYMENT_REORG_REVIEW',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND receipt_json IS NOT NULL",intent.get("intent_no"));
                db.write("UPDATE nx_purchase_payment_arrival SET status='MANUAL_REVIEW' WHERE id=?",arrival.get("id"));
                outcome(intent,"MANUAL_REVIEW");
                audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("PURCHASE_PAYMENT_REORG_REVIEW").resourceType("PURCHASE_PAYMENT_INTENT")
                        .resourceId(text(intent.get("intent_no"))).userId(number(intent.get("user_id"))).actorType("SYSTEM").actorUsername("system:purchase-payment")
                        .result("MANUAL_REVIEW").riskLevel("HIGH").detail(values("txid",arrival.get("txid"),"logIndex",arrival.get("log_index"),"checkedHead",proof.checkedHead())).build());return null;
            });
        }
    }
    private String arrivalReasonAfterQuotaRelease(Map<String, Object> locked, String reason) {
        var reservation = quotaHolds.read(locked);
        // Keep a trial's specific review reason. A released quota can prevent delivery but cannot prove late payment.
        return reservation != null && reservation.state() == PurchaseQuotaReservation.State.RELEASED
                && (!"TRIAL_CONVERT".equals(locked.get("kind")) || reason.isBlank())
                ? "PAYMENT_LATE_REVIEW" : reason;
    }

    private void confirm(String no,String reference,String reason,Instant paidAt,Instant observedAt){
        db.write("UPDATE nx_purchase_payment_intent SET provider_reference=COALESCE(provider_reference,?),reconciliation_status=?,paid_at=COALESCE(paid_at,?),confirmed_at=COALESCE(confirmed_at,?),status=?,reason=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND (receipt_json IS NULL OR ?<>'')",reference,reason.isBlank()?"CONFIRMED":"REVIEW",paidAt==null?null:timestamp(paidAt),timestamp(observedAt),reason.isBlank()?"FUNDS_CONFIRMED":"MANUAL_REVIEW",reason,no,reason);
        if(!reason.isBlank())outcome(db.one(SELECT,no),"MANUAL_REVIEW");
    }
    private void deliver(String no,boolean explicit){
        var before=db.one(SELECT,no);
        if(before==null||before.get("refund_receipt_json")!=null||!verifyFunds(before))return;
        boolean joined=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        var delivery=new TransactionTemplate(transactions);delivery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        delivery.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try{delivery.execute(status->{
            testCloudAdmission.lock();
            if(cregis(before.get("payment_method")))cregisGate(network(before.get("payment_method")),false,true);
            var intent=db.one(SELECT+" FOR UPDATE",no);
            if(intent==null||intent.get("receipt_json")!=null||intent.get("refund_receipt_json")!=null||intent.get("confirmed_at")==null)return null;
            if(NON_DELIVERABLE_FUNDS.contains(text(intent.get("reason"))))return null;
            if(!explicit&&"MANUAL_REVIEW".equals(intent.get("status")))return null;
            var reservation = quotaHolds.read(intent);
            if (reservation != null && reservation.state() == PurchaseQuotaReservation.State.RELEASED) return null;
            if (reservation != null && reservation.state() != PurchaseQuotaReservation.State.HELD) {
                throw PurchaseQuotaReservation.conflict();
            }
            long owner=number(intent.get("user_id"));String kind=text(intent.get("kind"));var payload=parse(intent.get("payload_json"));
            if("ORDER_PAY".equals(kind))orders.lockDirectPaymentOrderParticipants(text(payload.get("orderNo")));
            user(owner,true);var quote=parse(intent.get("quote_json"));
            boolean legacyTrialQuote=!quote.containsKey("trialOffsetBasis");
            boolean legacyTrialVersion = !"ACCRUED_B90".equals(quote.get("trialOffsetBasis"));
            var trialPolicy = frozenTrialPolicy(quote);
            Instant pricingAt = Instant.parse(text(quote.get("pricingAsOf")));
            String trialClaimNo;
            if ("TRIAL_CONVERT".equals(kind)) {
                var currentClaim = trials.currentExternalPaymentClaim(owner);
                trialClaimNo = trialClaimNo(quote, currentClaim);
                if (currentClaim == null || !currentClaim.claimNo().equals(trialClaimNo)) {
                    throw new BizException(409, "TRIAL_NOT_CONVERTIBLE");
                }
            } else {
                trialClaimNo = null;
            }
            // Fulfilment checks the replacement source under the existing submission rules.
            var pricing = pricing(owner, kind, payload, pricingAt, legacyTrialQuote, legacyTrialVersion, false, null, false, reservation, trialPolicy);
            if (!hash(pricing).equals(intent.get("pricing_hash"))) throw new BizException(409,"PURCHASE_PAYMENT_PRICING_CHANGED");
            if (reservation != null) {
                if (!reservation.businessId().equals(reservationBusiness(kind, pricing))) throw PurchaseQuotaReservation.conflict();
                if ("ORDER_PAY".equals(kind)) quotaHolds.requireOrder(reservation, owner, text(pricing.get("orderNo")));
            }
            Instant paidAt = instant(intent.get("paid_at") == null ? intent.get("confirmed_at") : intent.get("paid_at"));
            var result = VerifiedPurchasePayment.settle(owner, text(intent.get("payment_method")), no,
                    money(quote.get("subtotalUsdt")), pricingAt, paidAt,
                    () -> fulfil(owner, kind, payload, no, legacyTrialQuote, legacyTrialVersion, reservation, trialClaimNo, trialPolicy));
            Object receipt=require(result);
            Map<String,Object> canonical=jsonMapper.convertValue(receipt,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            canonical.put("walletDebitUsdt","0.00");canonical.put("amountUsdt",quote.get("businessAmountUsdt"));
            canonical.put("paidTotalUsdt",quote.get("totalUsdt"));
            canonical.put("paymentSubtotalUsdt",quote.get("subtotalUsdt"));canonical.put("roundingAdjustmentUsdt",quote.get("roundingAdjustmentUsdt"));
            canonical.put("paymentMethod",intent.get("payment_method"));canonical.put("paymentRail",intent.get("payment_method"));
            String orderNo=text(canonical.get("orderNo"));
            if(orderNo.isBlank())throw new BizException(409,"PURCHASE_PAYMENT_RECEIPT_INVALID");
            quotaHolds.finish(reservation, PurchaseQuotaReservation.State.CONSUMED, orderNo, "External purchase settlement");
            if(db.write("UPDATE nx_order SET payment_no=?,updated_at=NOW(6) WHERE order_no=? AND user_id=? AND payment_status='PAID'",no,orderNo,owner)!=1)
                throw new BizException(409,"PURCHASE_PAYMENT_ORDER_LINK_CONFLICT");
            if(db.write("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,currency,payment_status,signature_status,raw_callback,paid_at,created_at,updated_at,is_deleted) VALUES(?,?,?,?,?,?,'USDT','PAID','VERIFIED',?,NOW(6),NOW(6),NOW(6),0)",
                    no,orderNo,owner,intent.get("payment_method"),intent.get("provider_reference"),quote.get("businessAmountUsdt"),json(values("intentNo",no,"quoteId",intent.get("quote_id"),"paymentMethod",intent.get("payment_method"))))!=1)
                throw new BizException(409,"PURCHASE_PAYMENT_RECORD_CONFLICT");
            if(db.write("UPDATE nx_purchase_payment_intent SET status='DELIVERED',reason='',receipt_json=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status IN ('FUNDS_CONFIRMED','MANUAL_REVIEW')",json(canonical),no)!=1)throw new BizException(409,"PURCHASE_PAYMENT_DELIVERY_CONFLICT");
            outcome(db.one(SELECT,no),"DELIVERED");
            audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("PURCHASE_PAYMENT_DELIVERED").resourceType("PURCHASE_PAYMENT_INTENT")
                    .resourceId(no).bizNo(no).userId(owner).actorType("SYSTEM").actorUsername("system:purchase-payment")
                    .result("SUCCESS").riskLevel("HIGH").detail(values("paymentMethod",intent.get("payment_method"),"quote",quote,"providerReference",intent.get("provider_reference"))).build());
            return null;
        });}catch(RuntimeException failure){
            if(joined)throw failure;
            String code=failure.getMessage();if(code==null||!code.matches("[A-Z0-9_:]{1,128}"))code="PURCHASE_PAYMENT_DELIVERY_BLOCKED";
            String reason=code;
            tx(()->{int changed=db.write("UPDATE nx_purchase_payment_intent SET status='MANUAL_REVIEW',reconciliation_status='REVIEW',reason=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND confirmed_at IS NOT NULL AND status<>'DELIVERED' AND refund_receipt_json IS NULL",reason,no);if(changed==1)outcome(db.one(SELECT,no),"MANUAL_REVIEW");return null;});
        }
    }
    private boolean verifyFunds(Map<String,Object> intent){
        if(intent.get("confirmed_at")==null)return false;
        try{
            if("HDPAY_BANK".equals(intent.get("payment_method"))){
                var fresh=hdGateway.queryPayOrder(text(intent.get("intent_no")));
                return fresh!=null&&fresh.orderStatus()==3&&text(intent.get("intent_no")).equals(fresh.merchantOrderId())
                        &&text(intent.get("provider_reference")).equals(fresh.providerOrderId())&&"BANKQR".equalsIgnoreCase(fresh.payType())
                        &&fresh.transAmt()!=null&&new BigDecimal(text(parse(intent.get("quote_json")).get("amountVnd"))).compareTo(fresh.transAmt())==0;
            }
            var network=network(intent.get("payment_method"));
            var arrivals=db.rows("SELECT * FROM nx_purchase_payment_arrival WHERE intent_no=? AND chain_id=?",intent.get("intent_no"),network.chainId());
            if(arrivals.size()!=1||number(intent.get("provider_project"))!=cr.getProjectId())return false;
            var incoming=arrivals.get(0);String txid=text(incoming.get("txid"));BigDecimal amount=new BigDecimal(text(incoming.get("amount")));
            var proof=chain.verifyExact(network,txid,(int)number(incoming.get("log_index")),text(intent.get("address")),amount,number(incoming.get("block_number")));
            if(proof==null||!proof.blockHash().equalsIgnoreCase(text(incoming.get("block_hash"))))return false;
            var trades=crGateway.provider().depositsByTxid(network,txid).stream().filter(t->t.status()==1&&network.chainId().equals(t.chainId())
                    &&network.tokenMatches(t.tokenId())&&network.addressEquals(text(intent.get("address")),t.address())&&amount.compareTo(t.amount())==0).toList();
            return trades.size()==1&&amount.compareTo(new BigDecimal(text(parse(intent.get("quote_json")).get("totalUsdt"))))==0;
        }catch(RuntimeException unavailable){return false;}
    }
    private ApiResult<?> fulfil(long owner,String kind,Map<String,Object> payload,String no,boolean legacyTrialQuote, boolean legacyTrialVersion, PurchaseQuotaReservation reservation,
            String trialClaimNo, Map<String, String> trialPolicy){
        return switch(kind){
            case "ORDER_PAY"->orders.settleVerifiedExternal(owner,text(payload.get("orderNo")),reservation);
            case "TRADE_IN"->tradeins.settleVerifiedExternal(owner,"TRADE_IN",no,jsonMapper.convertValue(payload,AppTradeinSubmitRequest.class),null,null,reservation);
            case "CAPACITY_REPLACE"->tradeins.settleVerifiedExternal(owner,"CAPACITY_REPLACE",no,null,jsonMapper.convertValue(payload,AppCapacityReplaceSubmitRequest.class),null,reservation);
            case "CAPACITY_KEEP"->tradeins.settleVerifiedExternal(owner,"CAPACITY_KEEP",no,null,null,jsonMapper.convertValue(payload,AppCapacityKeepSubmitRequest.class),reservation);
            case "TRIAL_CONVERT"->trials.settleVerifiedExternal(owner,text(payload.get("productNo")),new BigDecimal(text(payload.get("expectedAmountUsdt"))),text(payload.get("quoteVersion")),legacyTrialQuote,legacyTrialVersion,reservation,trialClaimNo,trialPolicy);
            default->throw new BizException(422,"PURCHASE_PAYMENT_KIND_INVALID");
        };
    }
    @Scheduled(fixedDelayString="${nexion.finance.purchase-payment-observe-ms:30000}")
    public void scheduled(){
        // Failed and confirmed-late intents can still own a live commerce deadline.
        String live="""
                p.status IN ('CREATING','SUBMIT_UNKNOWN','AWAITING_PAYMENT','FUNDS_CONFIRMED') OR (
                    p.kind='ORDER_PAY' AND p.receipt_json IS NULL
                    AND ((p.status='EXPIRED' AND p.confirmed_at IS NULL)
                         OR (p.status='MANUAL_REVIEW' AND p.reason='PAYMENT_LATE_REVIEW' AND p.confirmed_at IS NOT NULL))
                    AND EXISTS(SELECT 1 FROM nx_order o WHERE o.order_no=JSON_UNQUOTE(JSON_EXTRACT(p.payload_json,'$.orderNo'))
                        AND o.user_id=p.user_id AND o.is_deleted=0 AND o.payment_status='PENDING' AND o.order_status='PENDING_PAYMENT')
                )
                """;
        observeBatch(db.rows("SELECT p.* FROM nx_purchase_payment_intent p WHERE "+live+" ORDER BY COALESCE(p.observed_at,p.created_at),p.intent_no LIMIT 50"));
        // Only finished commerce results use the separate historical financial-reconciliation quota.
        observeBatch(db.rows("SELECT p.* FROM nx_purchase_payment_intent p WHERE NOT ("+live+") ORDER BY COALESCE(p.observed_at,p.created_at),p.intent_no LIMIT 10"));
    }
    private void observeBatch(List<Map<String,Object>> rows){
        for(var row:rows){
            try{refresh(row);}catch(RuntimeException unavailable){/* One unavailable rail cannot stop others. */}
            finally{db.write("UPDATE nx_purchase_payment_intent SET observed_at=NOW(6) WHERE intent_no=?",row.get("intent_no"));}
        }
    }
    public Map<String,Object> records(String status,String method,int page,int size){
        int limit=Math.min(100,Math.max(1,size)),number=Math.max(1,page);String s=status==null?"":status,m=method==null?"":method;
        String where=" WHERE (?='' OR status=?) AND (?='' OR payment_method=?)";
        var total=db.one("SELECT COUNT(*) total FROM nx_purchase_payment_intent"+where,s,s,m,m);
        var rows=db.rows("SELECT * FROM nx_purchase_payment_intent"+where+" ORDER BY created_at DESC LIMIT ? OFFSET ?",s,s,m,m,limit,(number-1)*limit);
        return values("items",rows.stream().map(row->view(row,true)).toList(),"total",total.get("total"),"pageNum",number,"pageSize",limit);
    }
    public Map<String,Object> detail(String no){var row=db.one(SELECT,no);if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");return view(row,true);}

    /** Finance confirms an already completed external refund. The command commits no money movement. */
    public Map<String,Object> closeRefunded(String no, String key, Map<String,Object> request) {
        return recordRefund(no, key, request, false);
    }

    /** Record proof for reporting while preserving an already fulfilled purchase and its receipt. */
    public Map<String,Object> recordDeliveredRefund(String no, String key, Map<String,Object> request) {
        return recordRefund(no, key, request, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> recordRefund(String no, String key, Map<String,Object> request, boolean delivered) {
        PurchasePaymentPolicyService.reason(request);
        String actorId=AdminActorResolver.subjectId();
        if(actorId==null)throw new BizException(401,"ADMIN_AUTH_REQUIRED");
        Object version=request.get("expectedVersion");
        if(!(version instanceof Integer || version instanceof Long)||number(version)<0)
            throw new BizException(422,"PURCHASE_PAYMENT_REFUND_VERSION_REQUIRED");
        String actor=AdminActorResolver.resolve("admin:"+actorId);
        String commandScope = delivered ? "D1:PURCHASE_REFUND_EVIDENCE:" : "D1:PURCHASE_REFUND_CLOSEOUT:";
        return commands.executeRetained(commandScope+actorId,key,
                hash(values("intentNo",no,"request",request)),Map.class,()->{
            var row=db.one(SELECT+" FOR UPDATE",no);
            if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");
            if(row.get("refund_receipt_json")!=null)throw new BizException(409,"PURCHASE_PAYMENT_REFUND_ALREADY_CLOSED");
            if(number(row.get("version"))!=number(version))throw new BizException(409,"PURCHASE_PAYMENT_REFUND_VERSION_CONFLICT");
            boolean eligible = delivered
                    ? row.get("receipt_json") != null && row.get("paid_at") != null
                    : row.get("receipt_json") == null
                        && Set.of("FUNDS_CONFIRMED","MANUAL_REVIEW").contains(text(row.get("status")));
            if(!eligible || row.get("confirmed_at")==null)
                throw new BizException(409,"PURCHASE_PAYMENT_REFUND_NOT_ELIGIBLE");
            var proof=refundProof(row,request,delivered);
            long owner=number(row.get("user_id"));
            String orderNo="ORDER_PAY".equals(row.get("kind"))?text(parse(row.get("payload_json")).get("orderNo")):null;
            if(!delivered) {
                if(orderNo!=null)orders.lockDirectPaymentOrderParticipants(orderNo);
                db.one("SELECT id FROM nx_user WHERE id=? FOR UPDATE",owner);
            }
            String refundKey=proof.containsKey("txid")?proof.get("chainId")+":"+proof.get("txid")+":"+proof.get("logIndex"):null;
            proof.putAll(values("closedAt",now().toString(),"reason",text(request.get("reason")).trim(),"adminId",actorId));
            try {
                int changed=delivered ? db.write("""
                        UPDATE nx_purchase_payment_intent SET refund_receipt_json=?,refund_transfer_key=?,
                            version=version+1,updated_at=NOW(6)
                        WHERE intent_no=? AND version=? AND confirmed_at IS NOT NULL
                          AND receipt_json IS NOT NULL AND refund_receipt_json IS NULL
                        """,json(proof),refundKey,no,number(version)) : db.write("""
                        UPDATE nx_purchase_payment_intent SET status='REFUNDED',reason='PAYMENT_REFUNDED',
                            reconciliation_status='REFUNDED',refund_receipt_json=?,refund_transfer_key=?,
                            version=version+1,updated_at=NOW(6)
                        WHERE intent_no=? AND version=? AND confirmed_at IS NOT NULL
                          AND receipt_json IS NULL AND refund_receipt_json IS NULL
                        """,json(proof),refundKey,no,number(version));
                if(changed!=1)throw new BizException(409,"PURCHASE_PAYMENT_REFUND_VERSION_CONFLICT");
            } catch(org.springframework.dao.DuplicateKeyException used) {
                throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_ALREADY_USED");
            }
            if(!delivered) {
                quotaHolds.finish(quotaHolds.read(row),PurchaseQuotaReservation.State.RELEASED,null,"PAYMENT_REFUNDED");
                if(orderNo!=null && activeIntents(owner,row.get("kind"),text(row.get("payload_json")),no).isEmpty())
                    orders.closeRefundedDirectPaymentOrder(owner,orderNo);
            }
            audit.recordRequired(AuditLogWriteRequest.builder().action(delivered
                            ? "PURCHASE_PAYMENT_REFUND_EVIDENCE_RECORDED" : "PURCHASE_PAYMENT_REFUND_CLOSED")
                    .resourceType("PURCHASE_PAYMENT_INTENT").resourceId(no).bizNo(no).userId(owner)
                    .actorType("ADMIN").actorId(Long.parseLong(actorId)).actorUsername(actor)
                    .riskLevel("HIGH").result("SUCCESS").detail(proof).build());
            return detail(no);
        });
    }

    private Map<String,Object> refundProof(Map<String,Object> row, Map<String,Object> request, boolean delivered) {
        if ("HDPAY_BANK".equals(row.get("payment_method"))) {
            if (delivered) throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_UNAVAILABLE");
            HdPayGateway.PayOrder observed;
            try { observed=hdGateway.queryPayOrder(text(row.get("intent_no"))); }
            catch(HdPayGatewayException unavailable) { throw new BizException(503,"PURCHASE_PAYMENT_REFUND_PROOF_UNAVAILABLE"); }
            if(observed==null||observed.orderStatus()!=4||!text(row.get("intent_no")).equals(observed.merchantOrderId())
                    ||text(row.get("provider_reference")).isBlank()||!text(row.get("provider_reference")).equals(observed.providerOrderId())
                    ||!"BANKQR".equalsIgnoreCase(observed.payType())||observed.transAmt()==null
                    ||new BigDecimal(text(parse(row.get("quote_json")).get("amountVnd"))).compareTo(observed.transAmt())!=0)
                throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_MISMATCH");
            return values("channel","HDPAY","currency","VND","amount",observed.transAmt().toPlainString(),
                    "providerOrderId",observed.providerOrderId(),"providerStatus",observed.orderStatus());
        }
        var network=network(row.get("payment_method"));
        String txid=text(request.get("refundTxid")).trim();
        Object logIndex=request.get("refundLogIndex");
        if(!network.validTxid(txid)||!(logIndex instanceof Integer index)||index<0)
            throw new BizException(422,"PURCHASE_PAYMENT_REFUND_TRANSFER_REQUIRED");
        txid=network.normalizeTxid(txid);
        var arrivals=db.rows("SELECT * FROM nx_purchase_payment_arrival WHERE intent_no=?",row.get("intent_no"));
        if(arrivals.size()!=1||cr.getProjectId()<=0||number(row.get("provider_project"))!=cr.getProjectId())
            throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_MISMATCH");
        var arrival=arrivals.get(0);
        String paymentAddress=text(row.get("address"));
        String payer=text(arrival.get("from_address"));
        var amount=new BigDecimal(text(arrival.get("amount")));
        BigDecimal returned = amount;
        if (delivered && request.get("refundAmountUsdt") != null) {
            String rawAmount = text(request.get("refundAmountUsdt"));
            if (!rawAmount.matches("[0-9]{1,12}(\\.[0-9]{1,6})?")) {
                throw new BizException(422,"PURCHASE_PAYMENT_REFUND_AMOUNT_INVALID");
            }
            returned = new BigDecimal(rawAmount);
            if (returned.signum() <= 0 || returned.compareTo(amount) > 0) {
                throw new BizException(422,"PURCHASE_PAYMENT_REFUND_AMOUNT_INVALID");
            }
            BigDecimal principal = new BigDecimal(text(parse(row.get("quote_json")).get("businessAmountUsdt")));
            if (returned.compareTo(amount) < 0 && returned.compareTo(principal) > 0) {
                throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_MISMATCH");
            }
        }
        if(!network.chainId().equals(text(arrival.get("chain_id")))||number(arrival.get("provider_project"))!=cr.getProjectId()
                ||!network.validAddress(payer)||!network.validAddress(paymentAddress)
                ||db.one("""
                        SELECT id FROM nx_cregis_deposit_address
                         WHERE project_id=? AND chain_id=? AND purchase_intent_no=? AND address=?
                           AND purpose='PURCHASE_DIRECT' AND state='DIRECT_PURCHASE'
                        """,cr.getProjectId(),network.chainId(),row.get("intent_no"),paymentAddress)==null)
            throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_MISMATCH");
        BscDepositProof.TransferProof incoming;
        BscDepositProof.TransferProof refund;
        try {
            incoming=chain.verifyExact(network,text(arrival.get("txid")),(int)number(arrival.get("log_index")),
                    paymentAddress,amount,number(arrival.get("block_number")));
            refund=chain.verifyExact(network,txid,index,payer,returned,null);
        } catch(IllegalStateException invalid) {
            throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_MISMATCH");
        }
        if(incoming==null||refund==null||!incoming.blockHash().equalsIgnoreCase(text(arrival.get("block_hash")))
                ||!network.addressEquals(payer,incoming.fromAddress())||refund.blockNumber()<=incoming.blockNumber()
                ||network.addressEquals(payer,paymentAddress)||!network.addressEquals(paymentAddress,refund.fromAddress()))
            throw new BizException(409,"PURCHASE_PAYMENT_REFUND_PROOF_MISMATCH");
        return values("channel","CREGIS","currency","USDT","amount",returned.toPlainString(),
                "chainId",network.chainId(),"txid",txid,"logIndex",index,"blockNumber",refund.blockNumber(),
                "blockHash",refund.blockHash(),"fromAddress",refund.fromAddress(),"toAddress",payer,
                "arrivalTxid",arrival.get("txid"),"arrivalLogIndex",arrival.get("log_index"));
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> revalidate(String no,String key,Map<String,Object> request){
        PurchasePaymentPolicyService.reason(request);String actor=AdminActorResolver.resolve("admin");
        return commands.executeRetained("D1:PURCHASE_REVALIDATE:"+actor,key,hash(values("intentNo",no,"request",request)),Map.class,()->{
            testCloudAdmission.lock();
            var snapshot=db.one(SELECT,no);
            if(snapshot!=null&&cregis(snapshot.get("payment_method")))cregisGate(network(snapshot.get("payment_method")),false,true);
            var row=db.one(SELECT+" FOR UPDATE",no);if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");
            if(row.get("refund_receipt_json")!=null)throw new BizException(409,"PURCHASE_PAYMENT_REFUND_ALREADY_CLOSED");
            if(number(row.get("version"))!=number(request.get("expectedVersion")))throw new BizException(409,"PURCHASE_PAYMENT_REVALIDATE_VERSION_CONFLICT");
            refresh(row);deliver(no,true);var result=detail(no);
            audit.recordRequired(AuditLogWriteRequest.builder().action("PURCHASE_PAYMENT_REVALIDATE").resourceType("PURCHASE_PAYMENT_INTENT").resourceId(no)
                    .actorType("ADMIN").actorUsername(actor).riskLevel("HIGH").result("SUCCESS").detail(values("reason",request.get("reason"),"result",result)).build());
            return result;
        });
    }
    private Map<String,Object> view(Map<String,Object> row,boolean admin){
        var result=parse(row.get("quote_json"));result.putAll(values("intentNo",row.get("intent_no"),"commandKey",row.get("command_key"),"status",row.get("status"),
                "reason",row.get("reason"),"version",row.get("version"),"expiresAt",deadline(row).toString(),"deadlineAt",deadline(row).toString(),"confirmationGraceEndsAt",graceEnd(row.get("payment_method"),deadline(row)).toString()));
        result.remove(TRIAL_CLAIM_SNAPSHOT);
        result.remove("trialPricingPolicy");
        result.remove("trialDeviceName");
        result.remove("trialImageSku");
        result.remove("trialImageAssetId");
        result.remove("trialImageObjectKey");
        if ("TRIAL_CONVERT".equals(row.get("kind"))) {
            result.put("claimNo", parse(row.get("quote_json")).get(TRIAL_CLAIM_SNAPSHOT));
            if ("EXPIRED".equals(row.get("status")) && row.get("confirmed_at") == null
                    && !"QUERY_UNKNOWN".equals(row.get("reconciliation_status"))) {
                result.put("trialPurchaseOutcome", "UNPAID_TERMINAL");
            } else if (lateTrialPaymentCanReselect(text(row.get("intent_no")), text(row.get("payment_method")), deadline(row), paymentDeadline(row),
                    text(row.get("reason")), text(row.get("status")), row.get("paid_at") == null ? null : instant(row.get("paid_at")),
                    row.get("confirmed_at") == null ? null : instant(row.get("confirmed_at")), now())) {
                result.put("trialPurchaseOutcome", "LATE_PAYMENT_REVIEW");
            }
        }
        if(row.get("provider_expires_at")!=null)result.put("expirationTime",instant(row.get("provider_expires_at")).toString());
        if(row.get("payment_url")!=null&&"AWAITING_PAYMENT".equals(row.get("status"))&&now().isBefore(paymentDeadline(row)))result.put("paymentUrl",row.get("payment_url"));
        boolean networkOpen=true;
        if(cregis(row.get("payment_method"))) {
            var network=network(row.get("payment_method"));
            var global=db.one("SELECT state,CAST(assign_enabled AS UNSIGNED) assign_enabled,CAST(credit_enabled AS UNSIGNED) credit_enabled FROM nx_cregis_provision_gate WHERE id=1");
            var business=db.one("SELECT CAST(deposit_enabled AS UNSIGNED) deposit_enabled FROM nx_cregis_network_switch WHERE network=?",network.network());
            networkOpen=cr.getMode()==CregisProperties.Mode.PROVIDER && cr.isDepositEnabled() && cr.isDepositCreditEnabled()
                    && cr.depositNetworkEnabled(network) && global!=null && "IDLE".equals(global.get("state"))
                    && number(global.get("assign_enabled"))==1 && number(global.get("credit_enabled"))==1
                    && business!=null && number(business.get("deposit_enabled"))==1;
        }
        if(row.get("address")!=null&&(admin||row.get("refund_receipt_json")==null&&networkOpen&&now().isBefore(paymentDeadline(row))))result.put("address",row.get("address"));
        result.put("paymentAllowed",networkOpen&&"AWAITING_PAYMENT".equals(row.get("status"))&&now().isBefore(paymentDeadline(row)));
        result.put("fulfilmentStatus",row.get("receipt_json")==null?"NOT_DELIVERED":"DELIVERED");
        if(row.get("receipt_json")!=null){
            var receipt=parse(row.get("receipt_json"));
            receipt.put("paidTotalUsdt",result.get("totalUsdt"));
            result.put("receipt",receipt);
        }
        result.put("reconciliationStatus",row.get("reconciliation_status"));
        if(row.get("refund_receipt_json")!=null && row.get("receipt_json")==null) {
            result.put("refundStatus","REFUNDED");
            // Installed App and PC clients only accept their existing payment status enum.
            if(!admin||"REFUNDED".equals(row.get("status")))
                result.putAll(values("status","MANUAL_REVIEW","reason","PAYMENT_REFUNDED_REVIEW","reconciliationStatus","REFUNDED"));
            if(admin)result.put("refundReceipt",parse(row.get("refund_receipt_json")));
        }
        if(admin && row.get("receipt_json")!=null && row.get("refund_receipt_json")!=null)
            result.put("refundReceipt",parse(row.get("refund_receipt_json")));
        if(admin)result.putAll(values("userId",text(row.get("user_id")),"request",parse(row.get("payload_json")),"providerReference",row.get("provider_reference"),
                "providerActualFeeUsdt",row.get("provider_actual_fee_usdt")==null?null:new BigDecimal(text(row.get("provider_actual_fee_usdt"))).toPlainString(),
                "createdAt",row.get("created_at")==null?null:instant(row.get("created_at")).toString(),"updatedAt",row.get("updated_at")==null?null:instant(row.get("updated_at")).toString()));
        if(admin)result.put("arrivals",db.rows("SELECT txid,log_index,CAST(amount AS CHAR) amount,block_number,block_hash,confirmations,status FROM nx_purchase_payment_arrival WHERE intent_no=? ORDER BY id",row.get("intent_no")));
        return result;
    }
}
