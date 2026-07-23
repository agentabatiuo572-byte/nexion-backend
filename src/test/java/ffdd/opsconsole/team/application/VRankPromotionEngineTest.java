package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantCommand;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantResult;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankConfigRow;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import ffdd.opsconsole.team.domain.VRankRewardPayout;
import ffdd.opsconsole.team.domain.VRankRewardRuleRow;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link VRankPromotionEngine} 单测:覆盖逐阶/不越级/不降级/字段缺失/meetsAllConditions 五条核心路径。
 *
 * <p>Fake 注入,无需 Spring 上下文。配置口径对齐 nx_v_rank_config seed:
 * <pre>
 * V0: 全 0
 * V1: self_buy=500, direct_refs=3
 * V2: team_volume=5000
 * V3: team_volume=20000, downline_rank=V2, downline_count=2
 * V4: team_volume=50000, downline_rank=V2, downline_count=2
 * V5: team_volume=100000, downline_rank=V3, downline_count=3
 * V6: team_volume=200000, downline_rank=V3, downline_count=3
 * </pre>
 */
class VRankPromotionEngineTest {

    private FakePlatformConfigFacade configFacade;
    private FakeTeamCommissionRepository commissionRepository;
    private FakeVRankPerformanceRepository performanceRepository;
    private FakeTreasuryCoverageFacade coverageFacade;
    private FakeTreasuryLedgerPostingFacade ledgerPostingFacade;
    private VoucherGrantFacade voucherGrantFacade;
    private VRankPromotionEngine engine;
    private VRankRewardDispatcher rewardDispatcher;

    @BeforeEach
    void setUp() {
        configFacade = new FakePlatformConfigFacade();
        commissionRepository = new FakeTeamCommissionRepository();
        performanceRepository = new FakeVRankPerformanceRepository();
        coverageFacade = new FakeTreasuryCoverageFacade();
        ledgerPostingFacade = new FakeTreasuryLedgerPostingFacade();
        voucherGrantFacade = mock(VoucherGrantFacade.class);
        when(voucherGrantFacade.grant(any())).thenAnswer(invocation -> {
            VoucherGrantCommand command = invocation.getArgument(0);
            return new VoucherGrantResult("VGR-" + command.userId() + "-" + command.voucherId(), false);
        });
        seedVRankConfigRows();
        rewardDispatcher = new VRankRewardDispatcher(
                commissionRepository,
                coverageFacade,
                ledgerPostingFacade,
                voucherGrantFacade,
                configFacade);
        engine = new VRankPromotionEngine(
                commissionRepository,
                performanceRepository,
                configFacade,
                new ObjectMapper(),
                rewardDispatcher,
                mock(EventOutboxService.class));
    }

    private void seedVRankConfigRows() {
        // 对齐 schema seed 的 V0..V12 门槛
        commissionRepository.vRankConfigRows.addAll(List.of(
                row("V0", "0", 0, "0", null, 0, 0),
                row("V1", "500", 3, "0", null, 0, 1),
                row("V2", "0", 0, "5000", null, 0, 2),
                row("V3", "0", 0, "20000", "V2", 2, 3),
                row("V4", "0", 0, "50000", "V2", 2, 4),
                row("V5", "0", 0, "100000", "V3", 3, 5),
                row("V6", "0", 0, "200000", "V3", 3, 6),
                row("V7", "0", 0, "500000", "V4", 3, 7),
                row("V8", "0", 0, "1000000", "V4", 4, 8),
                row("V9", "0", 0, "2000000", null, 0, 9),
                row("V10", "0", 0, "5000000", null, 0, 10),
                row("V11", "0", 0, "10000000", null, 0, 11),
                row("V12", "0", 0, "20000000", null, 0, 12)));
    }

    private VRankConfigRow row(String code, String selfBuy, int directRefs,
                               String teamVolume, String downlineRank,
                               int downlineCount, int sortOrder) {
        return new VRankConfigRow(
                code,
                new BigDecimal(selfBuy),
                directRefs,
                new BigDecimal(teamVolume),
                downlineRank,
                downlineCount,
                sortOrder,
                null,
                BigDecimal.ZERO,
                0);
    }

    // ============================================================
    // 逐阶判定 + 不越级:V0→V3 应中途卡在 V1(selfBuy 不够)
    // ============================================================
    @Test
    void evaluateEscalatesStepByStepWithoutSkipping() {
        // 当前 V0,selfBuy=100(不够 V1 的 500),即使 teamVolume 巨大也只能停在 V0
        commissionRepository.memberVRanks.put(1001L, "V0");
        performanceRepository.snapshot = new VRankEvaluationSnapshot(
                new BigDecimal("100"), new BigDecimal("99999999"), 0, Map.of());

        String result = engine.evaluate(VRankPromotionContext.systemEvaluation(1001L));

        assertThat(result).isEqualTo("V0");
        assertThat(commissionRepository.userLevelLogs).isEmpty();
    }

    @Test
    void evaluateReachesV2WhenSelfBuyAndTeamVolumeSatisfy() {
        // V0→V1(selfBuy 500,directRefs 3)→V2(teamVolume 5000)
        commissionRepository.memberVRanks.put(1002L, "V0");
        performanceRepository.snapshot = new VRankEvaluationSnapshot(
                new BigDecimal("500"), new BigDecimal("6000"), 3, Map.of());

        String result = engine.evaluate(VRankPromotionContext.systemEvaluation(1002L));

        assertThat(result).isEqualTo("V2");
        assertThat(commissionRepository.memberVRanks.get(1002L)).isEqualTo("V2");
        assertThat(commissionRepository.userLevelLogs).hasSize(1);
        Map<String, Object> log = commissionRepository.userLevelLogs.get(0);
        assertThat(log).containsEntry("fromCode", "V0").containsEntry("toCode", "V2");
        assertThat((String) log.get("auditNo")).startsWith("VRANK-1002-V0-V2-");
        assertThat(log.get("snapshotJson")).asString().contains("selfBuyUSD");
    }

    @Test
    void evaluateDoesNotSkipRankEvenIfUserIsHigher() {
        // 用户当前 V5,但业绩仅够 V3(teamVolume 30000 卡在 V4 的 50000 门槛)。
        // 逐阶算法从 V0 起评:V0→V1(selfBuy 500)→V2(teamVolume 5000)→V3(teamVolume 20000 + V2 腿>=2)
        // →V4 失败(teamVolume<50000)。reachable=V3。permanent 默认 off → 用户从 V5 降到 V3。
        commissionRepository.memberVRanks.put(1003L, "V5");
        performanceRepository.snapshot = new VRankEvaluationSnapshot(
                new BigDecimal("500"), new BigDecimal("30000"), 3,
                Map.of(2, 2)); // V2 腿=2(>=2 满足 V3 的 leg 要求)

        String result = engine.evaluate(VRankPromotionContext.systemEvaluation(1003L));

        // 逐阶不越级:即便当前 V5,也按业绩实情降到 V3(V4 teamVolume 不达标)
        assertThat(result).isEqualTo("V3");
        assertThat(commissionRepository.memberVRanks.get(1003L)).isEqualTo("V3");
        assertThat(commissionRepository.userLevelLogs).hasSize(1);
        assertThat(commissionRepository.userLevelLogs.get(0))
                .containsEntry("fromCode", "V5")
                .containsEntry("toCode", "V3");
    }

    // ============================================================
    // 不降级:F.vrank.permanent=on 且业绩下滑时维持原阶
    // ============================================================
    @Test
    void permanentProtectionPreventsDowngradeWhenEnabled() {
        // 用户当前 V5,业绩下滑到 V0 水准,permanent=on → 维持 V5
        commissionRepository.memberVRanks.put(2001L, "V5");
        performanceRepository.snapshot = VRankEvaluationSnapshot.empty();
        configFacade.values.put("team.ui.F.vrank.permanent", "on");

        String result = engine.evaluate(VRankPromotionContext.systemEvaluation(2001L));

        assertThat(result).isEqualTo("V5");
        assertThat(commissionRepository.memberVRanks.get(2001L)).isEqualTo("V5");
        assertThat(commissionRepository.userLevelLogs).isEmpty();
    }

    @Test
    void downgradeAppliesWhenPermanentProtectionOff() {
        // permanent 未配置(默认 off),V5 用户业绩为 0 → 降到 V0
        commissionRepository.memberVRanks.put(2002L, "V5");
        performanceRepository.snapshot = VRankEvaluationSnapshot.empty();

        String result = engine.evaluate(VRankPromotionContext.systemEvaluation(2002L));

        assertThat(result).isEqualTo("V0");
        assertThat(commissionRepository.memberVRanks.get(2002L)).isEqualTo("V0");
        assertThat(commissionRepository.userLevelLogs).hasSize(1);
    }

    // ============================================================
    // 字段缺失 ≠ value=0 通过:V1 不配 teamVolume 不能用 0 阻断
    // ============================================================
    @Test
    void missingFieldsAreIgnoredAsConditions() {
        // V1: selfBuy=500 + directRefs=3(无 teamVolume 要求),构造 selfBuy=500/directRefs=3/teamVolume=0 应通过
        commissionRepository.memberVRanks.put(3001L, "V0");
        performanceRepository.snapshot = new VRankEvaluationSnapshot(
                new BigDecimal("500"), BigDecimal.ZERO, 3, Map.of());

        String result = engine.evaluate(VRankPromotionContext.systemEvaluation(3001L));

        // 应至少升到 V1(selfBuy/directRefs 满足,teamVolume 字段缺失不作条件)
        // V2 teamVolume=5000 不满足 → 停在 V1
        assertThat(result).isEqualTo("V1");
    }

    // ============================================================
    // meetsAllConditions:directRefs 字段单独验证
    // ============================================================
    @Test
    void meetsAllConditionsDirectRefsLessThanConfigReturnsFalse() {
        VRankConfigRow v1 = commissionRepository.vRankConfigRows.get(1); // selfBuy=500, directRefs=3
        VRankEvaluationSnapshot snapshot = new VRankEvaluationSnapshot(
                new BigDecimal("500"), BigDecimal.ZERO, 2, Map.of());

        assertThat(engine.meetsAllConditions(snapshot, v1)).isFalse();
    }

    @Test
    void meetsAllConditionsAllFieldsSatisfiedReturnsTrue() {
        VRankConfigRow v1 = commissionRepository.vRankConfigRows.get(1);
        VRankEvaluationSnapshot snapshot = new VRankEvaluationSnapshot(
                new BigDecimal("1000"), BigDecimal.ZERO, 5, Map.of());

        assertThat(engine.meetsAllConditions(snapshot, v1)).isTrue();
    }

    @Test
    void meetsAllConditionsLegCountUsesCumulativeAtLeastTargetRank() {
        // V5: teamVolume>=100000 + V3 腿>=3(累加 V3+V4+V5...)
        VRankConfigRow v5 = commissionRepository.vRankConfigRows.get(5);
        // legCounts: V3=1, V4=2 → 累加 V3+ = 3,满足 >=3
        VRankEvaluationSnapshot snapshot = new VRankEvaluationSnapshot(
                BigDecimal.ZERO, new BigDecimal("100000"), 0, Map.of(3, 1, 4, 2));

        assertThat(engine.meetsAllConditions(snapshot, v5)).isTrue();
    }

    @Test
    void meetsAllConditionsLegCountInsufficientReturnsFalse() {
        VRankConfigRow v3 = commissionRepository.vRankConfigRows.get(3); // V2 腿>=2
        // legCounts: V2=1 → 1 < 2,不满足
        VRankEvaluationSnapshot snapshot = new VRankEvaluationSnapshot(
                BigDecimal.ZERO, new BigDecimal("20000"), 0, Map.of(2, 1));

        assertThat(engine.meetsAllConditions(snapshot, v3)).isFalse();
    }

    @Test
    void meetsAllConditionsMissingDownlineRankButCountPositiveReturnsFalse() {
        // 配了 count>0 但 rank 为 null:保守视为不满足
        VRankConfigRow broken = new VRankConfigRow(
                "VX", BigDecimal.ZERO, 0, new BigDecimal("1000"), null, 1, 99,
                null, BigDecimal.ZERO, 0);
        VRankEvaluationSnapshot snapshot = new VRankEvaluationSnapshot(
                BigDecimal.ZERO, new BigDecimal("1000"), 0, Map.of());

        assertThat(engine.meetsAllConditions(snapshot, broken)).isFalse();
    }

    @Test
    void parseRankLevelHandlesEdgeCases() {
        assertThat(engine.parseRankLevel("V0")).isZero();
        assertThat(engine.parseRankLevel("V12")).isEqualTo(12);
        assertThat(engine.parseRankLevel("v3")).isEqualTo(3);
        assertThat(engine.parseRankLevel(null)).isNull();
        assertThat(engine.parseRankLevel("")).isNull();
        assertThat(engine.parseRankLevel("V13")).isNull(); // 超出 0-12 范围
        assertThat(engine.parseRankLevel("VX")).isNull();
        assertThat(engine.parseRankLevel("L1")).isNull();
    }

    // ============================================================
    // Sprint3: VRankRewardDispatcher 派发路径测试
    // ============================================================

    /** 资金类 USDT 奖励:落 nx_commission_event + D4 台账 + payout(GRANTED)。 */
    @Test
    void dispatchFundRewardWritesCommissionEventAndLedgerAndPayout() {
        // given:V5 用户(被晋升人),usdt 奖励 100
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v5-usdt", "V5", "usdt", new BigDecimal("100"), null, null, null, 1));
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(
                new BigDecimal("110.00"), new BigDecimal("85.00"));

        // when
        rewardDispatcher.dispatch(5001L, "V5", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5001L));

        // then:commission_event(user_id=本人,type=vrank_reward,currency=USDT,amount_usdt=100)
        assertThat(commissionRepository.commissionEvents).hasSize(1);
        Map<String, Object> event = commissionRepository.commissionEvents.get(0);
        assertThat(event).containsEntry("userId", 5001L);
        assertThat(event).containsEntry("commissionType", "vrank_reward");
        assertThat(event).containsEntry("currency", "USDT");
        assertThat(event.get("amountUsdt")).isEqualTo(new BigDecimal("100"));

        // D4 台账:direction=IN,status=PENDING,asset=USDT,bizNo=F1-VRANKREWARD-<id>
        assertThat(ledgerPostingFacade.entries).hasSize(1);
        Map<String, Object> ledger = ledgerPostingFacade.entries.get(0);
        assertThat(ledger).containsEntry("direction", "IN");
        assertThat(ledger).containsEntry("status", "PENDING");
        assertThat(ledger).containsEntry("asset", "USDT");
        assertThat((String) ledger.get("bizNo")).startsWith("F1-VRANKREWARD-");

        // payout:status=GRANTED,有 commission_event_id 与 bill_id
        assertThat(commissionRepository.payouts).hasSize(1);
        VRankRewardPayout payout = commissionRepository.payouts.get(0);
        assertThat(payout.status()).isEqualTo("GRANTED");
        assertThat(payout.rewardType()).isEqualTo("usdt");
        assertThat(payout.commissionEventId()).isNotNull();
        assertThat(payout.billId()).startsWith("F1-VRANKREWARD-");
        assertThat(payout.sponsorUserId()).isNull();
    }

    /** B1 备付金低于红线:资金类奖励抛 COVERAGE_BELOW_REDLINE,事务应回滚(此处验证抛出)。 */
    @Test
    void dispatchFundRewardBlockedByCoverageBelowRedline() {
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v5-usdt", "V5", "usdt", new BigDecimal("100"), null, null, null, 1));
        // 覆盖率 80% < 红线 85% → 阻断
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(
                new BigDecimal("80.00"), new BigDecimal("85.00"));

        // when + then:抛 IllegalStateException 含 COVERAGE_BELOW_REDLINE
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        rewardDispatcher.dispatch(5002L, "V5", VRankEvaluationSnapshot.empty(),
                                VRankPromotionContext.systemEvaluation(5002L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COVERAGE_BELOW_REDLINE");
        // 阻断后 commission_event/payout 都不应写入
        assertThat(commissionRepository.commissionEvents).isEmpty();
        assertThat(commissionRepository.payouts).isEmpty();
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    /** 培育类 NEX 奖励:派给 L1 上线 sponsor,commission_type=cultivation,source_user_id=被晋升人。 */
    @Test
    void dispatchCultivationNexRewardGoesToSponsor() {
        // given:V6 NEX 1000 亿的培育奖(参考 seed 数据);5003 的 sponsor=5004
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v6-nex", "V6", "nex", new BigDecimal("1000000000"), null, null, null, 1));
        commissionRepository.sponsorMap.put(5003L, 5004L);

        // when
        rewardDispatcher.dispatch(5003L, "V6", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5003L));

        // then:commission_event 写给 sponsor(5004),type=cultivation,source_user_id=5003
        assertThat(commissionRepository.commissionEvents).hasSize(1);
        Map<String, Object> event = commissionRepository.commissionEvents.get(0);
        assertThat(event).containsEntry("userId", 5004L);
        assertThat(event).containsEntry("commissionType", "cultivation");
        assertThat(event).containsEntry("sourceUserId", 5003L);
        assertThat(event).containsEntry("currency", "NEX");
        assertThat(event.get("amountNex")).isEqualTo(new BigDecimal("1000000000"));

        // payout:user_id=被晋升人(5003),sponsor_user_id=5004
        assertThat(commissionRepository.payouts).hasSize(1);
        VRankRewardPayout payout = commissionRepository.payouts.get(0);
        assertThat(payout.userId()).isEqualTo(5003L);
        assertThat(payout.sponsorUserId()).isEqualTo(5004L);
        assertThat(payout.rewardType()).isEqualTo("nex");
        assertThat(ledgerPostingFacade.entries).hasSize(1);
        assertThat(ledgerPostingFacade.entries.get(0)).containsEntry("userId", 5004L);
    }

    /** 培育类 NEX 无 sponsor → fallback 派本人(commission_type=vrank_reward,sponsorUserId=null)。 */
    @Test
    void dispatchNexRewardFallbackToSelfWhenNoSponsor() {
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v6-nex", "V6", "nex", new BigDecimal("500"), null, null, null, 1));
        // sponsorMap 不设 5005 的 sponsor

        rewardDispatcher.dispatch(5005L, "V6", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5005L));

        Map<String, Object> event = commissionRepository.commissionEvents.get(0);
        assertThat(event).containsEntry("userId", 5005L);
        assertThat(event).containsEntry("commissionType", "vrank_reward");
        assertThat(commissionRepository.payouts.get(0).sponsorUserId()).isNull();
    }

    /** voucher 真实调用 H7 权属边界,成功后 payout=GRANTED;权益类不入 D4。 */
    @Test
    void dispatchVoucherRewardGrantsH7OwnershipAndMarksPayoutGranted() {
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v5-voucher", "V5", "voucher", null, "VC-100", null, null, 2));

        rewardDispatcher.dispatch(5006L, "V5", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5006L));

        verify(voucherGrantFacade).grant(new VoucherGrantCommand(
                5006L,
                "VC-100",
                "vrank:5006:v5:VC-100",
                "VRANK_REWARD",
                "5006:V5:voucher",
                "ENGINE",
                "F1 V-Rank voucher reward"));
        assertThat(commissionRepository.payouts).hasSize(1);
        VRankRewardPayout payout = commissionRepository.payouts.get(0);
        assertThat(payout.status()).isEqualTo("GRANTED");
        assertThat(payout.voucherId()).isEqualTo("VC-100");
        assertThat(payout.reason()).contains("h7GrantId=VGR-5006-VC-100");
        assertThat(payout.commissionEventId()).isNull();
        assertThat(payout.billId()).isNull();
        // 不入 D4
        assertThat(commissionRepository.commissionEvents).isEmpty();
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    @Test
    void dispatchVoucherRewardRollsBackPayoutWhenH7GrantFails() {
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v5-voucher-fail", "V5", "voucher", null, "VC-INACTIVE", null, null, 2));
        doThrow(new IllegalStateException("H7_VOUCHER_NOT_GRANTABLE"))
                .when(voucherGrantFacade).grant(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rewardDispatcher.dispatch(
                        5008L, "V5", VRankEvaluationSnapshot.empty(), VRankPromotionContext.systemEvaluation(5008L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("H7_VOUCHER_NOT_GRANTABLE");

        assertThat(commissionRepository.payouts).isEmpty();
    }

    /** sku stub:E 域未接入,仅落 payout(status=PENDING_GRANT),不入 D4。 */
    @Test
    void dispatchSkuRewardIsStubPendingGrant() {
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v5-sku", "V5", "sku", null, null, "SKU-DEVICE-1", null, 3));

        rewardDispatcher.dispatch(5007L, "V5", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5007L));

        VRankRewardPayout payout = commissionRepository.payouts.get(0);
        assertThat(payout.status()).isEqualTo("PENDING_GRANT");
        assertThat(payout.skuId()).isEqualTo("SKU-DEVICE-1");
        assertThat(commissionRepository.commissionEvents).isEmpty();
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    /** custom 类型:仅记流水(status=GRANTED),不入 D4。 */
    @Test
    void dispatchCustomRewardWritesPayoutOnlyWithoutD4() {
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v5-custom", "V5", "custom", null, null, null, "VIP 专属勋章", 4));

        rewardDispatcher.dispatch(5008L, "V5", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5008L));

        assertThat(commissionRepository.payouts).hasSize(1);
        VRankRewardPayout payout = commissionRepository.payouts.get(0);
        assertThat(payout.status()).isEqualTo("GRANTED");
        assertThat(payout.customLabel()).isEqualTo("VIP 专属勋章");
        assertThat(payout.commissionEventId()).isNull();
        assertThat(commissionRepository.commissionEvents).isEmpty();
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    /** 幂等防重:同一 user/rank/type 第二次调用直接跳过(不重复派发)。 */
    @Test
    void dispatchIsIdempotentForSameUserRankType() {
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v5-usdt", "V5", "usdt", new BigDecimal("100"), null, null, null, 1));

        // 第一次派发:成功
        rewardDispatcher.dispatch(5009L, "V5", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5009L));
        assertThat(commissionRepository.payouts).hasSize(1);
        assertThat(commissionRepository.commissionEvents).hasSize(1);
        assertThat(ledgerPostingFacade.entries).hasSize(1);

        // 第二次派发:跳过(幂等)
        rewardDispatcher.dispatch(5009L, "V5", VRankEvaluationSnapshot.empty(),
                VRankPromotionContext.systemEvaluation(5009L));
        assertThat(commissionRepository.payouts).hasSize(1); // 未增加
        assertThat(commissionRepository.commissionEvents).hasSize(1);
        assertThat(ledgerPostingFacade.entries).hasSize(1);
    }

    /** 集成:engine.evaluate 触发阶变化 → 自动调用 dispatcher 派发奖励。 */
    @Test
    void engineEvaluateTriggersRewardDispatchOnRankChange() {
        // 给 V1 阶配一个 usdt 奖励,用户从 V0 升 V1 时应自动派发
        commissionRepository.rewardRules.add(new ffdd.opsconsole.team.domain.VRankRewardRuleRow(
                "r-v1-usdt", "V1", "usdt", new BigDecimal("50"), null, null, null, 1));
        commissionRepository.memberVRanks.put(5010L, "V0");
        performanceRepository.snapshot = new VRankEvaluationSnapshot(
                new BigDecimal("500"), new BigDecimal("0"), 3, Map.of());

        String result = engine.evaluate(VRankPromotionContext.systemEvaluation(5010L));

        assertThat(result).isEqualTo("V1");
        // dispatcher 被自动调用:落了一条 payout
        assertThat(commissionRepository.payouts).hasSize(1);
        assertThat(commissionRepository.payouts.get(0).rankCode()).isEqualTo("V1");
        assertThat(commissionRepository.payouts.get(0).rewardType()).isEqualTo("usdt");
    }

    // ============================================================
    // Fakes
    // ============================================================

    static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }
    }

    static final class FakeVRankPerformanceRepository implements VRankPerformanceRepository {
        VRankEvaluationSnapshot snapshot = VRankEvaluationSnapshot.empty();

        @Override
        public VRankEvaluationSnapshot computeSnapshot(Long userId) {
            return snapshot;
        }
    }

    /**
     * 复用 OpsTeamServiceTest 的 fake 风格,只暴露引擎需要的字段。
     * (引擎测试用独立 fake,避免与 OpsTeamServiceTest 共享状态。)
     */
    static final class FakeTeamCommissionRepository implements TeamCommissionRepository {
        final List<VRankConfigRow> vRankConfigRows = new java.util.ArrayList<>();
        final Map<Long, String> memberVRanks = new LinkedHashMap<>();
        final List<Map<String, Object>> userLevelLogs = new java.util.ArrayList<>();
        // Sprint3 派发相关状态
        final List<VRankRewardRuleRow> rewardRules = new java.util.ArrayList<>();
        final Map<Long, Long> sponsorMap = new LinkedHashMap<>(); // userId → sponsorUserId
        final List<Map<String, Object>> commissionEvents = new java.util.ArrayList<>();
        final List<VRankRewardPayout> payouts = new java.util.ArrayList<>();
        final java.util.Set<String> existingPayoutKeys = new java.util.HashSet<>();
        private long nextCommissionEventId = 1L;

        @Override
        public List<Map<String, Object>> binarySettlements(int limit) {
            return List.of();
        }

        @Override
        public Map<String, Object> binarySettlementSummary() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> vRankRows() {
            return List.of();
        }

        @Override
        public boolean updateVRankThreshold(String rank, String field, Object value) {
            return false;
        }

        @Override
        public List<Map<String, Object>> f2Metrics() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> unilevelRates() {
            return List.of();
        }

        @Override
        public boolean updateUnilevelRule(int layerNo, String field, Object value) {
            return false;
        }

        @Override
        public List<Map<String, Object>> rateTiers() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> vRankRewards(String rank) {
            return List.of();
        }

        @Override
        public boolean addVRankReward(String rank, Map<String, Object> reward) {
            return false;
        }

        @Override
        public boolean updateVRankReward(String rank, String rewardId, Map<String, Object> reward) {
            return false;
        }

        @Override
        public boolean deleteVRankReward(String rank, String rewardId) {
            return false;
        }

        @Override
        public Map<String, Object> leadershipPoolSummary() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> leadershipRanks() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> quotaRows() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> ambassadorBands() {
            return List.of();
        }

        @Override
        public Map<String, Object> ambassadorSummary() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> leaderboardPodium(int limit) {
            return List.of();
        }

        @Override
        public Map<String, Object> leaderboardSummary() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> commissionEvents(int limit) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> commissionKindSummary() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> commissionAuditFeed(int limit) {
            return List.of();
        }

        @Override
        public boolean updateCommissionStatus(String eventId, String status) {
            return false;
        }

        @Override
        public boolean updateVRankLeadershipVotes(String rankCode, int votes) {
            return false;
        }

        @Override
        public boolean updateAmbassadorStatus(String applicationId, String status, String reviewer, String reason) {
            return false;
        }

        @Override
        public boolean insertLeaderboardAction(String period, String actionType, String reason, String operator) {
            return false;
        }

        @Override
        public List<VRankConfigRow> vRankConfigRows() {
            return vRankConfigRows;
        }

        @Override
        public String currentMemberVRank(Long userId) {
            return memberVRanks.getOrDefault(userId, "V0");
        }

        @Override
        public boolean updateMemberVRank(Long userId, String newRank) {
            memberVRanks.put(userId, newRank);
            return true;
        }

        @Override
        public boolean insertUserLevelLog(Long userId,
                                          String fromCode,
                                          String toCode,
                                          String reason,
                                          String operator,
                                          String snapshotJson,
                                          String triggerEventId,
                                          String auditNo,
                                          boolean isManual) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("fromCode", fromCode);
            row.put("toCode", toCode);
            row.put("reason", reason);
            row.put("operator", operator);
            row.put("snapshotJson", snapshotJson);
            row.put("triggerEventId", triggerEventId);
            row.put("auditNo", auditNo);
            row.put("isManual", isManual);
            userLevelLogs.add(row);
            return true;
        }

        // ============================================================
        // Sprint3 接口实现
        // ============================================================

        @Override
        public List<VRankRewardRuleRow> selectVRankRewardRulesByRank(String rankCode) {
            return rewardRules.stream()
                    .filter(r -> rankCode != null && rankCode.equalsIgnoreCase(r.rankCode()))
                    .toList();
        }

        @Override
        public Long findSponsorUserId(Long userId) {
            return sponsorMap.get(userId);
        }

        @Override
        public boolean existsVRankRewardPayout(Long userId, String rankCode, String rewardType) {
            return existingPayoutKeys.contains(payoutKey(userId, rankCode, rewardType));
        }

        @Override
        public Long insertCommissionEvent(Long userId,
                                          String commissionType,
                                          Long sourceUserId,
                                          String currency,
                                          BigDecimal amountUsdt,
                                          BigDecimal amountNex,
                                          String status,
                                          int coolingDays,
                                          String remark) {
            long id = nextCommissionEventId++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("userId", userId);
            row.put("commissionType", commissionType);
            row.put("sourceUserId", sourceUserId);
            row.put("currency", currency);
            row.put("amountUsdt", amountUsdt);
            row.put("amountNex", amountNex);
            row.put("status", status);
            row.put("remark", remark);
            commissionEvents.add(row);
            return id;
        }

        @Override
        public int countNetworkCommissionByOrder(Long userId, String orderNo) {
            return 0;
        }

        @Override
        public Long insertNetworkCommissionEvent(Long userId,
                                                 String commissionType,
                                                 Long sourceUserId,
                                                 Integer layerNo,
                                                 String orderNo,
                                                 BigDecimal orderAmountUsdt,
                                                 String currency,
                                                 BigDecimal amountUsdt,
                                                 BigDecimal amountNex,
                                                 String status,
                                                 int coolingDays,
                                                 String remark) {
            return nextCommissionEventId++;
        }

        @Override
        public boolean insertVRankRewardPayout(VRankRewardPayout payout) {
            String key = payoutKey(payout.userId(), payout.rankCode(), payout.rewardType());
            if (!existingPayoutKeys.add(key)) {
                return false; // 模拟 UNIQUE 约束
            }
            payouts.add(payout);
            return true;
        }

        // Sprint5 端点 3 接口契约补全 — 引擎测试不直接消费,提供空实现保持接口编译通过
        @Override
        public List<Map<String, Object>> queryPromotionLog(Long userId,
                                                           String v,
                                                           String cohort,
                                                           String from,
                                                           String to) {
            return List.of();
        }

        // Sprint6 端点第二组接口契约补全 — 引擎测试不直接消费,提供空/no-op 实现保持接口编译通过
        @Override
        public List<Map<String, Object>> queryRewardPayouts(String type,
                                                            String v,
                                                            String status,
                                                            Long userId,
                                                            String cursor) {
            return List.of();
        }

        @Override
        public Map<String, Object> findRewardPayoutByPayoutId(String payoutId) {
            return null;
        }

        @Override
        public boolean updateRewardPayoutStatus(String payoutId, String newStatus, String operator, String reason) {
            return false;
        }

        @Override
        public int reverseCommissionEvent(Long commissionEventId) {
            return 0;
        }

        private String payoutKey(Long userId, String rankCode, String rewardType) {
            return userId + "|" + rankCode + "|" + rewardType;
        }
    }

    // ============================================================
    // Sprint3 fake Treasury facades
    // ============================================================

    static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(
                new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }

    static final class FakeTreasuryLedgerPostingFacade implements TreasuryLedgerPostingFacade {
        final List<Map<String, Object>> entries = new java.util.ArrayList<>();

        @Override
        public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                    BigDecimal amount, String status, String remark) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("bizNo", bizNo);
            entry.put("userId", userId);
            entry.put("bizType", bizType);
            entry.put("asset", asset);
            entry.put("direction", direction);
            entry.put("amount", amount);
            entry.put("status", status);
            entry.put("remark", remark);
            entries.add(entry);
        }
    }
}
