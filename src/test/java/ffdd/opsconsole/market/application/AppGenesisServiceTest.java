package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppGenesisServiceTest {
    private final AppGenesisMapper mapper=mock(AppGenesisMapper.class);
    private final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox=mock(EventOutboxService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final GenesisCatalogService catalog=mock(GenesisCatalogService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final AppGenesisService service=new AppGenesisService(mapper,config,idempotency,outbox,audit,
            Clock.fixed(Instant.parse("2026-07-22T04:00:00Z"), ZoneOffset.UTC),catalog,environment,
            Optional.empty());

    @BeforeEach
    @SuppressWarnings({"rawtypes","unchecked"})
    void setUp(){
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "market.genesis.ops.eligibility.enabled" -> Optional.of("true");
            case "market.genesis.ops.eligibility.maxPerUser" -> Optional.of("5");
            case "market.genesis.ops.eligibility.minAccountAgeDays" -> Optional.of("0");
            case "market.genesis.ops.presale.enabled" -> Optional.of("false");
            case "market.genesis.ops.presale.showCountdown" -> Optional.of("true");
            case "market.genesis.ops.presale.unitPrice" -> Optional.of("9999");
            case "market.genesis.ops.presale.maxPerUser" -> Optional.of("5");
            default -> Optional.empty();
        });
        when(mapper.controlValue(anyString())).thenReturn(null);
        when(mapper.activeSeries()).thenReturn(series());
        when(mapper.lockActiveSeries()).thenReturn(series());
        when(mapper.holdingCount("genesis-main")).thenReturn(0L);
        when(mapper.lockHoldingCount("genesis-main")).thenReturn(0L);
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.userSandbox(anyLong())).thenReturn(0);
        when(mapper.userPolicy(42L)).thenReturn(new AppGenesisMapper.UserPolicyRow(
                42L,"VN","P1",120,4,"2026-W30"));
        when(mapper.lockWallet(42L)).thenReturn(new BigDecimal("20000"));
        when(mapper.wallet(42L)).thenReturn(new BigDecimal("10001"));
        when(mapper.debitWallet(42L,new BigDecimal("9999.000000"))).thenReturn(1);
        when(mapper.insertOrder(any())).thenReturn(1);
        when(mapper.insertHolding(any())).thenReturn(1);
        when(mapper.updateSoldSupply(1L,1L)).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.holdings(42L)).thenReturn(List.of());
        when(mapper.emissions(42L)).thenReturn(List.of());
        when(mapper.listings()).thenReturn(List.of());
        when(mapper.transactions()).thenReturn(List.of());
        when(mapper.userTransactions(42L)).thenReturn(List.of());
        when(catalog.marketOpen()).thenReturn(true);
        when(catalog.priceForSold(anyLong())).thenReturn(new BigDecimal("9999.000000"));
        when(catalog.publicState()).thenReturn(java.util.Map.of(
                "tiers", List.of(), "tiersVersion", 1L, "marketOpenState", "open",
                "marketOpenStateVersion", 1L, "closedNoticeKey", "default"));
        when(idempotency.execute(anyString(),anyString(),anyString(),any(),any()))
                .thenAnswer(i->((Supplier)i.getArgument(4)).get());
    }

    @Test
    void stateUsesHoldingsAsSoldTruthInsteadOfLegacySeriesCounter(){
        var data=service.state().getData();
        @SuppressWarnings("unchecked") var series=(java.util.Map<String,Object>)data.get("series");
        assertThat(series).containsEntry("soldSupply",0L).containsEntry("remainingSupply",1000L);
    }

    @Test
    void accountIncludesOnlyTheCurrentUsersGenesisPurchaseOrders() {
        when(mapper.holdings(42L)).thenReturn(List.of(new AppGenesisMapper.HoldingRow(
                1L,"GEN-HOLD-1",42L,"GEN-OWN-1","genesis-main",new BigDecimal("9999"),
                "ACTIVE",null,LocalDateTime.parse("2026-07-22T04:00:00"),null)));
        when(mapper.emissions(42L)).thenReturn(List.of(new AppGenesisMapper.EmissionRow(
                "GEN-EM-1","GEN-HOLD-1",new BigDecimal("1.25"),"PAID",
                LocalDateTime.parse("2026-07-22T04:30:00"))));
        when(mapper.userTransactions(42L)).thenReturn(List.of(new AppGenesisMapper.TransactionRow(
                "GEN-OWN-1","PRIMARY",1,new BigDecimal("9999"),new BigDecimal("9999"),
                BigDecimal.ZERO,LocalDateTime.parse("2026-07-22T04:00:00"))));

        Map<String,Object> data=service.account(42L).getData();

        assertThat(data.get("orders").toString()).contains("GEN-OWN-1", "PRIMARY", "2026-07-21T20:00:00Z");
        assertThat(data.get("holdings").toString()).contains("GEN-HOLD-1", "2026-07-21T20:00:00Z");
        assertThat(data.get("emissions").toString()).contains("GEN-EM-1", "2026-07-21T20:30:00Z");
        verify(mapper).userTransactions(42L);
    }

    @Test
    void eligibilityProjectsCanonicalHolderAllocationPriorityAndProvenance() {
        when(mapper.userHoldingCount(42L, "genesis-main")).thenReturn(2L);
        when(mapper.currentPriorityRank(42L, "genesis-main")).thenReturn(2);
        when(mapper.activeHolderCount("genesis-main")).thenReturn(100L);
        when(config.activeValue("market.genesis.ops.holder.allocationNexPerHolding")).thenReturn(Optional.of("125.50"));
        when(config.activeValue("market.genesis.ops.holder.priorityTop1Percent")).thenReturn(Optional.of("1"));
        when(config.activeValue("market.genesis.ops.holder.priorityTop3Percent")).thenReturn(Optional.of("3"));
        when(config.activeValue("market.genesis.ops.holder.priorityTop5Percent")).thenReturn(Optional.of("5"));
        when(config.activeValue("market.genesis.ops.holder.policyVersion")).thenReturn(Optional.of("genesis-holder-v2"));
        when(config.activeValue("market.genesis.ops.holder.effectiveAt")).thenReturn(Optional.of("2026-07-01T00:00:00Z"));

        Map<String, Object> data = service.eligibility(42L).getData();
        assertThat(data).containsEntry("reservedAllocation", new BigDecimal("251.00"))
                .containsEntry("reservedAllocationUnit", "NEX")
                .containsEntry("priorityRank", 2)
                .containsEntry("priorityTier", "TOP_3")
                .containsEntry("policyVersion", "genesis-holder-v2")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsKey("effectiveAt")
                .containsKey("asOf")
                .containsKey("serverTime")
                .containsKey("provenance");
        assertThat(data.get("qualificationReasonCodes").toString()).contains("HOLDINGS_CONFIRMED");
    }

    @Test
    void missingOrWithdrawnHolderPolicyIsExplicitAndNeverLocallyEstimated() {
        when(mapper.userHoldingCount(42L, "genesis-main")).thenReturn(2L);
        Map<String, Object> data = service.eligibility(42L).getData();
        assertThat(data).containsEntry("status", "CONFIG_UNAVAILABLE")
                .containsEntry("reservedAllocation", null)
                .containsEntry("priorityRank", null)
                .containsEntry("priorityTier", "NONE")
                .containsEntry("reservedAllocationUnit", "NEX");
    }

    @Test
    void publicStateExposesCanonicalSecondaryStatsAndNeverRawOrderNumbers() {
        when(mapper.secondaryStats()).thenReturn(new AppGenesisMapper.SecondaryStatsRow(
                new BigDecimal("25000"), new BigDecimal("1200"), 17L,
                new BigDecimal("24800"), new BigDecimal("20000")));
        when(mapper.transactions()).thenReturn(List.of(new AppGenesisMapper.TransactionRow(
                "tx_deadbeef", "SECONDARY", 1, new BigDecimal("24800"),
                new BigDecimal("24800"), new BigDecimal("620"),
                LocalDateTime.of(2026, 7, 22, 3, 0))));
        when(mapper.listings()).thenReturn(List.of(new AppGenesisMapper.ListingRow(
                "GEN-LIST-1","genesis-main",new BigDecimal("25000"),
                LocalDateTime.of(2026,7,22,3,30),"usr_abcd")));

        var data = service.state().getData();
        assertThat(data.get("marketStats")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) data.get("marketStats");
        assertThat(stats).containsEntry("owners", 17L)
                .containsEntry("floorDeltaPct", new BigDecimal("25.0000"));
        @SuppressWarnings("unchecked")
        var transactions = (List<Map<String, Object>>) data.get("transactions");
        assertThat(transactions.get(0)).containsEntry("orderNo", "tx_deadbeef")
                .containsEntry("completedAt", "2026-07-21T19:00:00Z");
        @SuppressWarnings("unchecked")
        var listings = (List<Map<String, Object>>) data.get("listings");
        assertThat(listings.get(0)).containsEntry("holdingNo", "GEN-LIST-1")
                .containsEntry("listedAt", "2026-07-21T19:30:00Z");
    }

    @Test
    void purchaseAtomicallyCreatesOrderHoldingLedgerAuditAndEvent(){
        var result=service.purchase(42L,"purchase-1",new AppGenesisService.PurchaseRequest(1));
        assertThat(result.getCode()).isZero();
        verify(mapper).debitWallet(42L,new BigDecimal("9999.000000"));
        verify(mapper).insertOrder(any(AppGenesisMapper.OrderWrite.class));
        verify(mapper).insertHolding(any(AppGenesisMapper.HoldingWrite.class));
        verify(mapper).updateSoldSupply(1L,1L);
        verify(mapper).insertLedger(any(AppGenesisMapper.LedgerWrite.class));
        verify(outbox).publishUserEvent(anyString(),anyString(),org.mockito.ArgumentMatchers.eq("genesis.purchased"),
                org.mockito.ArgumentMatchers.eq(42L),anyString(),org.mockito.ArgumentMatchers.anyInt(),anyString(),any());
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void j1PauseWinsBeforePurchase(){
        when(mapper.controlValue("killswitch.genesis")).thenReturn("disabled");
        assertThatThrownBy(()->service.purchase(42L,"purchase-paused",new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_MARKET_PAUSED");
        verify(mapper,never()).debitWallet(any(),any());
    }

    @Test
    void j1PauseIsProjectedByEligibilityAsAClosedFailSafeGate(){
        when(mapper.controlValue("killswitch.genesis")).thenReturn("disabled");

        @SuppressWarnings("unchecked")
        var eligibility = (java.util.Map<String, Object>) service.eligibility(42L).getData();
        assertThat(eligibility).containsEntry("eligible", false)
                .containsEntry("halted", true)
                .containsEntry("serverCanonical", true);
    }

    @Test
    void publicStateProjectsTheCanonicalJ1KillSwitchWithRevisionAndSource(){
        when(mapper.controlValue("killswitch.genesis")).thenReturn("disabled");
        when(mapper.controlValue("emergency.killswitch.genesis.lastChange")).thenReturn("rev-17");

        var data = service.state().getData();

        assertThat(data).containsEntry("serverCanonical", true)
                .containsEntry("halted", true)
                .containsEntry("revision", "rev-17")
                .containsEntry("source", "nx_emergency_control_setting:killswitch.genesis");
    }

    @Test
    void missingSalePolicyRemainsViewableButFailsClosedForMutation() {
        when(config.activeValue(anyString())).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        var sale = (java.util.Map<String, Object>) service.state().getData().get("sale");
        assertThat(sale).containsEntry("available", false).containsEntry("open", false);

        assertThatThrownBy(() -> service.purchase(42L, "policy-missing",
                new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("GENESIS_SALE_POLICY_UNAVAILABLE"));
        verify(mapper, never()).debitWallet(any(), any());
    }

    @Test
    void developmentGenesisPurchaseUsesCanonicalTablesForACanonicalAccount() {
        environment.setActiveProfiles("dev");
        when(mapper.userSandbox(42L)).thenReturn(0);

        ApiResult<Map<String, Object>> result = service.purchase(42L, "development-genesis",
                new AppGenesisService.PurchaseRequest(1));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        verify(mapper).lockActiveSeries();
        verify(mapper).debitWallet(42L, new BigDecimal("9999.000000"));
        verify(mapper).insertOrder(any(AppGenesisMapper.OrderWrite.class));
        verify(mapper).insertHolding(any(AppGenesisMapper.HoldingWrite.class));
        verify(mapper).insertLedger(any(AppGenesisMapper.LedgerWrite.class));
    }

    @Test
    void developmentGenesisAccountAndEligibilityUseTheSameCanonicalUserBoundaryAsG4() {
        environment.setActiveProfiles("dev");
        when(mapper.userSandbox(42L)).thenReturn(0);
        when(mapper.holdings(42L)).thenReturn(List.of(new AppGenesisMapper.HoldingRow(
                1L, "DEV-GENESIS-1", 42L, "DEV-ORDER-1", "genesis-main", new BigDecimal("7999"),
                "ACTIVE", null, LocalDateTime.parse("2026-08-28T11:10:12"), null)));

        var state = service.state();
        var account = service.account(42L);
        var eligibility = service.eligibility(42L);

        assertThat(state.getCode()).isZero();
        assertThat(state.getData()).containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        assertThat(account.getCode()).isZero();
        assertThat(account.getData()).containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        assertThat(account.getData().get("holdings").toString()).contains("DEV-GENESIS-1");
        assertThat(eligibility.getCode()).isZero();
        assertThat(eligibility.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("serverCanonical", true);
        verify(mapper).holdings(42L);
        verify(mapper).wallet(42L);
    }

    @Test
    void developmentRejectsASandboxMarkedAccountFromCanonicalGenesisTables() {
        environment.setActiveProfiles("dev");
        when(mapper.userSandbox(42L)).thenReturn(1);

        assertThatThrownBy(() -> service.account(42L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_PRODUCTION_USER_REQUIRED");
                });
        verify(mapper, never()).holdings(anyLong());
    }

    @Test
    void productionStillRejectsASandboxMarkedAccountFromCanonicalGenesisTables() {
        environment.setActiveProfiles("prod");
        when(mapper.userSandbox(42L)).thenReturn(1);

        assertThatThrownBy(() -> service.account(42L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_PRODUCTION_USER_REQUIRED");
                });
        verify(mapper, never()).holdings(anyLong());
    }

    @Test
    void finalSlotUsesCurrentLockingHoldingCountBeforeAnyWalletMutation(){
        when(mapper.lockHoldingCount("genesis-main")).thenReturn(1000L);

        assertThatThrownBy(()->service.purchase(42L,"purchase-sold-out",new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_SOLD_OUT");

        verify(mapper,never()).debitWallet(any(),any());
    }

    @Test
    void nullSeriesSupplyFailsClosedWithoutUnboxingOrWalletMutation() {
        when(mapper.lockActiveSeries()).thenReturn(new AppGenesisMapper.SeriesRow(
                1L, "genesis-main", "Genesis Node", null, new BigDecimal("9999"), 250,
                new BigDecimal("0.1"), "ACTIVE"));

        assertThatThrownBy(() -> service.purchase(42L, "purchase-null-supply",
                new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(503);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_SERIES_INVALID");
                });
        verify(mapper, never()).debitWallet(any(), any());
    }

    @Test
    void concurrentLastSlotLoserHasNoFinancialAuditOrEventSideEffects(){
        when(mapper.lockHoldingCount("genesis-main")).thenReturn(999L);
        when(mapper.updateSoldSupply(1L,1000L)).thenReturn(0);

        assertThatThrownBy(()->service.purchase(42L,"purchase-last-slot-loser",
                new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("GENESIS_SUPPLY_CONFLICT");
                });

        verify(mapper,never()).debitWallet(any(),any());
        verify(mapper,never()).insertOrder(any());
        verify(mapper,never()).insertHolding(any());
        verify(mapper,never()).insertLedger(any());
        verify(outbox,never()).publishUserEvent(anyString(),anyString(),anyString(),any(),anyString(),any(),anyString(),any());
        verify(audit,never()).recordRequiredForTrustedActor(any());
    }

    @Test
    void secondaryPurchaseRejectsSellerFromAnotherEnvironmentBeforeWalletMutation() {
        when(mapper.lockHolding("sandbox-holding")).thenReturn(new AppGenesisMapper.HoldingRow(
                9L, "sandbox-holding", 99L, "sandbox-order", "genesis-main",
                new BigDecimal("100"), "LISTED", new BigDecimal("120"),
                LocalDateTime.now(), LocalDateTime.now()));
        when(mapper.userSandbox(99L)).thenReturn(1);

        assertThatThrownBy(() -> service.buyListing(42L, "sandbox-holding", "secondary-env-boundary"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_SELLER_ENVIRONMENT_MISMATCH");
                });
        verify(mapper, never()).debitWallet(any(), any());
        verify(mapper, never()).creditWallet(any(), any());
        verify(mapper, never()).transferHolding(anyLong(), anyLong(), anyLong(), anyString(), any(), any());
    }

    @Test
    void seriesMutexMakesSecondLastSlotBuyerObserveSoldOutWithoutSideEffects(){
        when(mapper.lockHoldingCount("genesis-main")).thenReturn(999L,1000L);
        when(mapper.updateSoldSupply(1L,1000L)).thenReturn(1);
        when(mapper.lockActiveUser(43L)).thenReturn(43L);
        when(mapper.userPolicy(43L)).thenReturn(new AppGenesisMapper.UserPolicyRow(
                43L,"VN","P1",60,2,"2026-W30"));

        assertThat(service.purchase(42L,"purchase-last-slot-winner",
                new AppGenesisService.PurchaseRequest(1)).getCode()).isZero();
        clearInvocations(mapper,outbox,audit);

        assertThatThrownBy(()->service.purchase(43L,"purchase-last-slot-loser-2",
                new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo("GENESIS_SOLD_OUT");
                });

        verify(mapper,never()).updateSoldSupply(any(),anyLong());
        verify(mapper,never()).debitWallet(any(),any());
        verify(mapper,never()).insertOrder(any());
        verify(mapper,never()).insertHolding(any());
        verify(mapper,never()).insertLedger(any());
        verify(outbox,never()).publishUserEvent(anyString(),anyString(),anyString(),any(),anyString(),any(),anyString(),any());
        verify(audit,never()).recordRequiredForTrustedActor(any());
    }

    @Test
    void purchaseInventorySqlUsesSeriesRowMutexAndConditionalSupplyUpdate() throws Exception {
        var purchaseMethod=AppGenesisService.class.getMethod("purchase",Long.class,String.class,
                AppGenesisService.PurchaseRequest.class);
        String lockSeriesSql=String.join("\n",AppGenesisMapper.class.getMethod("lockActiveSeries")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String lockCountSql=String.join("\n",AppGenesisMapper.class.getMethod("lockHoldingCount",String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String updateSupplySql=String.join("\n",AppGenesisMapper.class
                .getMethod("updateSoldSupply",Long.class,long.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class).value());

        assertThat(purchaseMethod.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                .isTrue();
        assertThat(lockSeriesSql).contains("nx_genesis_series").contains("FOR UPDATE");
        assertThat(lockCountSql).contains("nx_genesis_holding").contains("FOR UPDATE");
        assertThat(updateSupplySql)
                .contains("sold_supply=#{soldSupply}")
                .contains("total_supply>=#{soldSupply}");
    }

    @Test
    void configuredEligibilityAndPresaleAreEnforcedBeforeAnyWalletMutation(){
        when(config.activeValue("market.genesis.ops.eligibility.minAccountAgeDays"))
                .thenReturn(Optional.of("180"));
        when(config.activeValue("market.genesis.ops.presale.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("market.genesis.ops.presale.startAt"))
                .thenReturn(Optional.of("2026-07-23T00:00:00Z"));
        when(config.activeValue("market.genesis.ops.presale.endAt"))
                .thenReturn(Optional.of("2026-08-23T00:00:00Z"));

        assertThatThrownBy(()->service.purchase(42L,"purchase-age-gate",
                new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_ACCOUNT_AGE_REQUIRED");
        verify(mapper,never()).debitWallet(any(),any());
    }

    @Test
    void activePresaleUsesCanonicalTierPriceAndAppliesCombinedPerUserCap(){
        when(config.activeValue("market.genesis.ops.eligibility.maxPerUser")).thenReturn(Optional.of("5"));
        when(config.activeValue("market.genesis.ops.presale.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("market.genesis.ops.presale.unitPrice")).thenReturn(Optional.of("7999"));
        when(config.activeValue("market.genesis.ops.presale.maxPerUser")).thenReturn(Optional.of("2"));
        when(config.activeValue("market.genesis.ops.presale.startAt"))
                .thenReturn(Optional.of("2026-07-01T00:00:00Z"));
        when(config.activeValue("market.genesis.ops.presale.endAt"))
                .thenReturn(Optional.of("2026-08-01T00:00:00Z"));
        when(mapper.userHoldingCount(42L,"genesis-main")).thenReturn(1L);
        when(mapper.debitWallet(42L,new BigDecimal("9999.000000"))).thenReturn(1);

        var result=service.purchase(42L,"purchase-presale-price",new AppGenesisService.PurchaseRequest(1));

        assertThat(result.getCode()).isZero();
        verify(mapper).debitWallet(42L,new BigDecimal("9999.000000"));
        var eligibility=service.eligibility(42L).getData();
        assertThat(eligibility).containsEntry("maxPerUser",2).containsEntry("remainingCap",1L);
        assertThat(eligibility).doesNotContainKeys("mode","appliesTo","hasGenesisInvite");
    }

    @Test
    void userCapBlocksPrimaryAndSecondaryAcquisitionBeforeWalletMutation(){
        when(config.activeValue("market.genesis.ops.eligibility.maxPerUser")).thenReturn(Optional.of("2"));
        when(mapper.userHoldingCount(42L,"genesis-main")).thenReturn(2L);

        assertThatThrownBy(()->service.purchase(42L,"purchase-cap",
                new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_USER_CAP_REACHED");
        when(mapper.lockHolding("listed-cap")).thenReturn(new AppGenesisMapper.HoldingRow(
                9L,"listed-cap",99L,"seller-order","genesis-main",new BigDecimal("100"),
                "LISTED",new BigDecimal("120"),LocalDateTime.now(),LocalDateTime.now()));
        assertThatThrownBy(()->service.buyListing(42L,"listed-cap","secondary-cap"))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_USER_CAP_REACHED");
        verify(mapper,never()).debitWallet(any(),any());
        verify(mapper,never()).creditWallet(any(),any());
        verify(mapper,never()).transferHolding(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
    }

    @Test
    void requestValidationDoesNotKeepTheRetiredTwentyUnitCap() {
        when(config.activeValue("market.genesis.ops.eligibility.maxPerUser")).thenReturn(Optional.of("5"));

        assertThatThrownBy(() -> service.purchase(42L,"purchase-retired-cap",
                new AppGenesisService.PurchaseRequest(21)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("GENESIS_USER_CAP_REACHED");

        verify(mapper,never()).debitWallet(any(),any());
    }

    @Test
    void publicAndAccountSnapshotsExposeTheSameServerCanonicalSalePolicy(){
        when(config.activeValue("market.genesis.ops.eligibility.maxPerUser")).thenReturn(Optional.of("3"));
        when(config.activeValue("market.genesis.ops.presale.showCountdown")).thenReturn(Optional.of("false"));

        @SuppressWarnings("unchecked")
        var publicSale=(java.util.Map<String,Object>)service.state().getData().get("sale");
        @SuppressWarnings("unchecked")
        var accountSale=(java.util.Map<String,Object>)service.account(42L).getData().get("sale");

        assertThat(publicSale).isEqualTo(accountSale)
                .containsEntry("maxPerUser",3)
                .containsEntry("serverCanonical",true);
    }

    private AppGenesisMapper.SeriesRow series(){
        return new AppGenesisMapper.SeriesRow(1L,"genesis-main","Genesis Node",1000,
                new BigDecimal("9999"),250,new BigDecimal("0.1"),"ACTIVE");
    }
}
