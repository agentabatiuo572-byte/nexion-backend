package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F2 unilevel 网络佣金结算引擎。
 *
 * <p>订单支付(checkout.completed)→ buyerUserId 的 L1-L7 上级链 × nx_commission_rule:UNILEVEL 费率
 * → commission_event(network, source=buyer, layer_no=L1-L7, order_no) + D4 台账 IN/PENDING(cooling)。
 *
 * <p>PRD(落地规格 line231):UNILEVEL_USDT L1-L7=[10/5/3/2/1/0.5/0.5%](和≤25%)+UNILEVEL_NEX+PartnerStatus
 * (不改费率,直推恒 10%)+InfluenceScore clamp(1.0,5.0)+coolingDays(30)。
 *
 * <p><b>已实现</b>:上级链×usdtPct→commission_event(network/USDT,layer_no+order_no)+D4;幂等(同 orderNo+ancestor+network 防重);
 * PartnerStatus(直推恒10%,unilevelRates L1 配置);paused 层开关(F.unilevel.L{n}.paused);
 * depthGate(L4+ 上级需 ≥ depthGateRank V2,否则跳过);UNILEVEL_NEX 派发(nex_per_usd × USDT 佣金);coolingDays 配置化(commission/cooling-days);
 * InfluenceScore clamp(PRD line1400/1529):L2-L7 扩展层佣金乘 ancestor 的 InfluenceScore,L1 直推不乘(恒10%)。
 *
 * <p><b>InfluenceScore</b>:score = clamp(1 + log10(monthlyNetworkVolume / 100), clampMin, clampMax);
 * monthlyNetworkVolume = ancestor 的 L1-L7 下级子树本月 PAID 订单 subtotal 之和(不含自己);
 * clampMin/Max 读 F.commission.influenceScore.clampMin/clampMax(默认 1.0/5.0),容错回退默认;精度 6 位 HALF_UP。
 *
 * <p><b>命名澄清(P2,非 bug)</b>:commission_event.commission_type='network' 是事件 kind(PRD line233
 * kind enum {network|binary|peer|cultivation|leadership|genesis}),表示"网络佣金事件";
 * nx_commission_rule.commission_type='unilevel' 是费率配置类型,表示"unilevel 费率档"。
 * 两者语义不同、均正确:network 是派发事件的分类,unilevel 是费率档配置的分类,不可混为一谈。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnilevelCommissionService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_DEPTH = 7;
    private static final String COMMISSION_NETWORK = "network";
    private static final String CURRENCY_USDT = "USDT";
    private static final String CURRENCY_NEX = "NEX";
    private static final String STATUS_COOLING = "COOLING";

    /** F2 depthGate 配置 key(PRD line231:默认 L4 层起需上级 ≥ depthGateRank V2)。 */
    private static final String CONFIG_KEY_DEPTH_GATE_LAYER = "F.unilevel.depthGate";
    private static final String CONFIG_KEY_DEPTH_GATE_RANK = "F.unilevel.depthGateRank";
    private static final String DEFAULT_DEPTH_GATE_LAYER = "L4";
    private static final String DEFAULT_DEPTH_GATE_RANK = "V2";

    /** F5 coolingDays 配置 key(PRD line231 默认30;读 commission/cooling-days)。 */
    private static final String CONFIG_KEY_COOLING_DAYS = "commission/cooling-days";
    private static final int DEFAULT_COOLING_DAYS = 30;

    /** F2 InfluenceScore clamp 边界配置 key(PRD line1400/1529 默认 1.0/5.0)。 */
    private static final String CONFIG_KEY_INFLUENCE_CLAMP_MIN = "F.commission.influenceScore.clampMin";
    private static final String CONFIG_KEY_INFLUENCE_CLAMP_MAX = "F.commission.influenceScore.clampMax";
    private static final double DEFAULT_INFLUENCE_CLAMP_MIN = 1.0;
    private static final double DEFAULT_INFLUENCE_CLAMP_MAX = 5.0;

    private final TeamCommissionMapper teamCommissionMapper;
    private final TeamCommissionRepository commissionRepository;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final PlatformConfigFacade configFacade;

    /**
     * 结算一笔订单的 unilevel 网络佣金:给 buyer 的 L1-L7 上级按对应层费率派发 USDT 佣金。
     *
     * @param buyerUserId       下单用户(佣金 source)
     * @param orderSubtotalUsdt 订单 USDT 小计(佣金基数,PAID/排除退款口径由调用方保证)
     * @param orderNo           订单号(审计 + 幂等键)
     * @return 实际派发的上级人数
     */
    @Transactional(rollbackFor = Exception.class)
    public int settle(Long buyerUserId, BigDecimal orderSubtotalUsdt, String orderNo) {
        if (buyerUserId == null || orderSubtotalUsdt == null || orderSubtotalUsdt.signum() <= 0
                || orderNo == null || orderNo.isBlank()) {
            return 0;
        }
        List<Map<String, Object>> upline = teamCommissionMapper.listUplineChain(buyerUserId, MAX_DEPTH);
        if (upline == null || upline.isEmpty()) {
            log.debug("F2 unilevel settle: no upline for buyer {}", buyerUserId);
            return 0;
        }
        Map<String, BigDecimal> usdtPctByLevel = loadUsdtPctByLevel();
        if (usdtPctByLevel.isEmpty()) {
            log.warn("F2 unilevel settle: no UNILEVEL rates configured, skip buyer {}", buyerUserId);
            return 0;
        }
        Map<String, BigDecimal> nexPerUsdByLevel = loadNexPerUsdByLevel();
        int coolingDays = resolveCoolingDays();
        int depthGateLayer = resolveDepthGateLayer();
        int depthGateRankNum = parseRankNum(resolveDepthGateRank());
        int settled = 0;
        int skippedIdempotent = 0;
        int skippedDepthGate = 0;
        for (Map<String, Object> row : upline) {
            Long ancestor = asLong(row.get("userId"));
            Integer layer = asInt(row.get("layer"));
            String vRank = asString(row.get("vRank"));
            if (ancestor == null || layer == null || layer < 1 || layer > MAX_DEPTH) {
                continue;
            }
            BigDecimal usdtPct = usdtPctByLevel.get("L" + layer);
            if (usdtPct == null || usdtPct.signum() <= 0) {
                continue;
            }
            // F2 paused 层开关(F.unilevel.L{n}.paused):运营暂停的层不派佣金
            if (isLayerPaused(layer)) {
                log.debug("F2 layer {} paused, skip order={}", layer, orderNo);
                continue;
            }
            // F2 depthGate(L4+ 上级需 ≥ depthGateRank V2):layer >= depthGateLayer 时,
            // 上级自循环行 v_rank < depthGateRank 则跳过(vRank null 视为 V0,在 depthGate 层被跳过)。
            if (layer >= depthGateLayer) {
                int ancestorRankNum = parseRankNum(vRank);
                if (ancestorRankNum < depthGateRankNum) {
                    skippedDepthGate++;
                    log.info("F2 depthGate skip: order={} ancestor={} layer={} vRank={} rankNum={} < gateRank={}",
                            orderNo, ancestor, layer, vRank, ancestorRankNum, depthGateRankNum);
                    continue;
                }
            }
            // 幂等:同 orderNo + 上级 + network 已派发则跳过(防订单重复结算)
            if (commissionRepository.countNetworkCommissionByOrder(ancestor, orderNo) > 0) {
                skippedIdempotent++;
                continue;
            }
            BigDecimal usdtAmount = orderSubtotalUsdt.multiply(usdtPct)
                    .divide(HUNDRED, 6, RoundingMode.HALF_UP);
            if (usdtAmount.signum() <= 0) {
                continue;
            }
            // F2 InfluenceScore(PRD line1400/1529):L2-L7 扩展层乘 ancestor 的 InfluenceScore;L1 直推不乘(恒10%)
            BigDecimal influenceScore = (layer >= 2) ? resolveInfluenceScore(ancestor) : null;
            BigDecimal finalUsdt = (influenceScore == null)
                    ? usdtAmount
                    : usdtAmount.multiply(influenceScore).setScale(6, RoundingMode.HALF_UP);
            if (layer >= 2) {
                log.info("F2 InfluenceScore applied: order={} ancestor={} layer={} score={} finalUsdt={}",
                        orderNo, ancestor, layer, influenceScore, finalUsdt);
            }
            String remark = "F2 unilevel L" + layer + " | order=" + orderNo + " buyer=" + buyerUserId;
            // ① USDT commission_event(network, source=buyer, layer_no, order_no, USDT, COOLING) + D4 IN/PENDING
            Long usdtEventId = commissionRepository.insertNetworkCommissionEvent(
                    ancestor, COMMISSION_NETWORK, buyerUserId, layer, orderNo, orderSubtotalUsdt,
                    CURRENCY_USDT, finalUsdt, ZERO, STATUS_COOLING, coolingDays, remark);
            if (usdtEventId == null) {
                log.warn("F2 unilevel USDT commission_event insert failed: ancestor={} layer={} order={}",
                        ancestor, layer, orderNo);
                continue;
            }
            ledgerPostingFacade.postLedgerEntry(
                    "F2-NETWORK-" + usdtEventId, ancestor, "TEAM_COMMISSION", CURRENCY_USDT,
                    "IN", finalUsdt, "PENDING", "F2 unilevel commission | " + remark);

            // ② NEX commission_event(nex_per_usd × finalUsdt 佣金;nexAmount>0 才派发,L2-L7 经 finalUsdt 隐式乘 InfluenceScore)+ D4 IN/PENDING
            BigDecimal nexPerUsd = nexPerUsdByLevel.get("L" + layer);
            if (nexPerUsd != null && nexPerUsd.signum() > 0) {
                BigDecimal nexAmount = finalUsdt.multiply(nexPerUsd).setScale(6, RoundingMode.HALF_UP);
                if (nexAmount.signum() > 0) {
                    Long nexEventId = commissionRepository.insertNetworkCommissionEvent(
                            ancestor, COMMISSION_NETWORK, buyerUserId, layer, orderNo, orderSubtotalUsdt,
                            CURRENCY_NEX, ZERO, nexAmount, STATUS_COOLING, coolingDays, remark + " NEX");
                    if (nexEventId != null) {
                        ledgerPostingFacade.postLedgerEntry(
                                "F2-NETWORK-NEX-" + nexEventId, ancestor, "TEAM_COMMISSION", CURRENCY_NEX,
                                "IN", nexAmount, "PENDING", "F2 unilevel NEX commission | " + remark);
                    } else {
                        log.warn("F2 unilevel NEX commission_event insert failed: ancestor={} layer={} order={}",
                                ancestor, layer, orderNo);
                    }
                }
            }
            settled++;
        }
        log.info("F2 unilevel settled: buyer={} order={} subtotal={} settled={}/{} idempotentSkip={} depthGateSkip={}",
                buyerUserId, orderNo, orderSubtotalUsdt, settled, upline.size(), skippedIdempotent, skippedDepthGate);
        return settled;
    }

    /** 加载 nx_commission_rule:UNILEVEL 的 level("L1"-"L7") → usdtPct 映射。 */
    private Map<String, BigDecimal> loadUsdtPctByLevel() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Map<String, Object> r : teamCommissionMapper.unilevelRates()) {
            Object level = r.get("level");
            BigDecimal pct = asBigDecimal(r.get("usdtPct"));
            if (level != null && pct != null) {
                map.put(level.toString(), pct);
            }
        }
        return map;
    }

    /** 加载 nx_commission_rule:UNILEVEL 的 level("L1"-"L7") → nexPerUsd 映射(nexReward 字段)。 */
    private Map<String, BigDecimal> loadNexPerUsdByLevel() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Map<String, Object> r : teamCommissionMapper.unilevelRates()) {
            Object level = r.get("level");
            BigDecimal nexPerUsd = asBigDecimal(r.get("nexReward"));
            if (level != null && nexPerUsd != null) {
                map.put(level.toString(), nexPerUsd);
            }
        }
        return map;
    }

    /** F2 层暂停开关:读 F.unilevel.L{n}.paused(true 暂停该层派发)。 */
    private boolean isLayerPaused(int layer) {
        return "true".equalsIgnoreCase(
                configFacade.activeValue("F.unilevel.L" + layer + ".paused").orElse("false"));
    }

    /** F2 depthGate 层(读 F.unilevel.depthGate,默认 "L4"→解析 4;非法回退 4)。 */
    private int resolveDepthGateLayer() {
        String raw = configFacade.activeValue(CONFIG_KEY_DEPTH_GATE_LAYER).orElse(DEFAULT_DEPTH_GATE_LAYER);
        String digits = raw == null ? "" : raw.trim().replaceAll("[^0-9]", "");
        try {
            int n = Integer.parseInt(digits);
            return n > 0 ? n : Integer.parseInt(DEFAULT_DEPTH_GATE_LAYER.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /** F2 depthGate 阶位(读 F.unilevel.depthGateRank,默认 "V2")。 */
    private String resolveDepthGateRank() {
        return configFacade.activeValue(CONFIG_KEY_DEPTH_GATE_RANK).orElse(DEFAULT_DEPTH_GATE_RANK);
    }

    /** F5 coolingDays(读 commission/cooling-days,默认30;PRD line231)。 */
    private int resolveCoolingDays() {
        return configFacade.activeValue(CONFIG_KEY_COOLING_DAYS)
                .map(v -> {
                    try { return Integer.parseInt(v.trim()); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(d -> d >= 0)
                .orElse(DEFAULT_COOLING_DAYS);
    }

    /**
     * F2 InfluenceScore(PRD line1400/1529):score = clamp(1 + log10(monthlyNetworkVolume / 100), clampMin, clampMax)。
     * <p>monthlyNetworkVolume = userId 的 L1-L7 下级子树本月 PAID 订单 subtotal 之和(不含自己);
     * volume<=0 直接返回 clampMin;clampMin/Max 读配置(默认 1.0/5.0),非法值容错回退默认;精度 6 位 HALF_UP。
     */
    private BigDecimal resolveInfluenceScore(Long userId) {
        BigDecimal volume = teamCommissionMapper.monthlyNetworkVolume(userId);
        double clampMin = parseInfluenceClamp(CONFIG_KEY_INFLUENCE_CLAMP_MIN, DEFAULT_INFLUENCE_CLAMP_MIN);
        double clampMax = parseInfluenceClamp(CONFIG_KEY_INFLUENCE_CLAMP_MAX, DEFAULT_INFLUENCE_CLAMP_MAX);
        if (volume == null || volume.signum() <= 0) {
            return BigDecimal.valueOf(clampMin).setScale(6, RoundingMode.HALF_UP);
        }
        double raw = 1.0 + Math.log10(Math.max(volume.doubleValue(), 1.0) / 100.0);
        double score = Math.max(clampMin, Math.min(clampMax, raw));
        return BigDecimal.valueOf(score).setScale(6, RoundingMode.HALF_UP);
    }

    /** 解析 InfluenceScore clamp 边界配置(容错:空/非法值回退 defaultValue)。 */
    private double parseInfluenceClamp(String key, double defaultValue) {
        String raw = configFacade.activeValue(key).orElse(null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 解析阶位代码为数字用于比较:"V2"→2, null/""→0(V0), "V12"→12, 非法→0。 */
    private static int parseRankNum(String rank) {
        if (rank == null || rank.isBlank()) {
            return 0;
        }
        String digits = rank.trim().replaceAll("[^0-9]", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.valueOf(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
