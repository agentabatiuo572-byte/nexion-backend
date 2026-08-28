package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.market.mapper.AppMarketSandboxMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppMarketSandboxServiceTest {
    @Test
    void productionProfileCannotEnterSandboxWriteRail() {
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class); MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-alpha");
        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());
        assertThatThrownBy(()->service.swap(7L,"k",new AppExchangeService.SwapRequest("USDT_TO_NEX",new BigDecimal("2"),false)))
                .hasMessageContaining("MARKET_SANDBOX_PROFILE_REQUIRED");
        verifyNoInteractions(mapper);
    }

    @Test
    void standardDevelopmentCannotEnterSandboxWriteRailEvenWithARunId() {
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-alpha");
        env.setActiveProfiles("dev");
        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());

        assertThatThrownBy(()->service.swap(7L,"k",new AppExchangeService.SwapRequest(
                "USDT_TO_NEX",new BigDecimal("2"),false)))
                .hasMessageContaining("MARKET_SANDBOX_PROFILE_REQUIRED");
        verifyNoInteractions(mapper);
    }

    @Test
    void exchangeMutationUsesRunAndAccountScopedWalletAndReplay() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-alpha"); env.setActiveProfiles("test"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.exchangeByKey("run-alpha",7L,"k")).thenReturn(null);
        when(mapper.lockExchangeRun("run-alpha")).thenReturn("run-alpha");
        when(mapper.userCompletedGrossToday("run-alpha",7L)).thenReturn(BigDecimal.ZERO);
        when(mapper.platformCompletedGrossToday("run-alpha")).thenReturn(BigDecimal.ZERO);
        when(mapper.exchangeOrders("run-alpha",7L)).thenReturn(List.of()); when(mapper.exchangeLedger("run-alpha",7L)).thenReturn(List.of());
        when(mapper.exchangeWallet("run-alpha",7L)).thenReturn(new AppMarketSandboxMapper.ExchangeWallet("run-alpha",7L,new BigDecimal("100"),BigDecimal.ZERO,0L));
        when(mapper.insertExchangeOrder(any())).thenReturn(1); when(mapper.updateExchangeWallet(anyString(),anyLong(),any(),any(),anyLong())).thenReturn(1); when(mapper.insertExchangeLedger(any())).thenReturn(1);
        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());
        service.swap(7L,"k",new AppExchangeService.SwapRequest("USDT_TO_NEX",new BigDecimal("2"),false));
        verify(mapper).ensureExchangeWallet(eq("run-alpha"),eq(7L),any(),any()); verify(mapper).updateExchangeWallet(eq("run-alpha"),eq(7L),any(),any(),eq(0L));
        verify(mapper,times(2)).insertExchangeLedger(any());
    }

    @Test
    void sandboxUserCannotBeReadFromAnotherRun() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-beta"); env.setActiveProfiles("test"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.exchangeWallet("run-beta",7L)).thenReturn(new AppMarketSandboxMapper.ExchangeWallet("run-beta",7L,BigDecimal.ZERO,BigDecimal.ZERO,0L));
        when(mapper.exchangeOrders("run-beta",7L)).thenReturn(List.of()); when(mapper.exchangeLedger("run-beta",7L)).thenReturn(List.of());
        new AppMarketSandboxService(mapper,env,Optional.empty()).exchangeState(7L);
        verify(mapper).exchangeWallet("run-beta",7L); verify(mapper,never()).exchangeWallet("run-alpha",7L);
    }

    @Test
    void dailyCapCannotBeBypassedByDisablingQueueMode() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-caps");
        env.setActiveProfiles("test"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.exchangeByKey("run-caps",7L,"cap-key")).thenReturn(null);
        when(mapper.exchangeWallet("run-caps",7L)).thenReturn(new AppMarketSandboxMapper.ExchangeWallet("run-caps",7L,new BigDecimal("100"),BigDecimal.ZERO,0L));
        when(mapper.lockExchangeRun("run-caps")).thenReturn("run-caps");
        when(mapper.userCompletedGrossToday("run-caps",7L)).thenReturn(new BigDecimal("49"));
        when(mapper.platformCompletedGrossToday("run-caps")).thenReturn(new BigDecimal("49"));

        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());
        assertThatThrownBy(()->service.swap(7L,"cap-key",new AppExchangeService.SwapRequest("USDT_TO_NEX",new BigDecimal("2"),false)))
                .hasMessage("EXCHANGE_DAILY_CAP_EXCEEDED");
        verify(mapper,never()).insertExchangeOrder(any());
    }

    @Test
    void repeatedGenesisPurchasesCannotExceedPerUserCap() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-genesis");
        env.setActiveProfiles("test"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.lockGenesisRun("run-genesis")).thenReturn("run-genesis");
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(7L,new BigDecimal("1000"),0L));
        when(mapper.holdings("run-genesis",7L)).thenReturn(java.util.Collections.nCopies(19,
                new AppMarketSandboxMapper.HoldingView(1L,"run-genesis","h","o",7L,"GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null)));

        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.of(saleConfig(20,0)));
        assertThatThrownBy(()->service.genesisPurchase(7L,"purchase-key",new AppGenesisService.PurchaseRequest(2)))
                .hasMessage("GENESIS_USER_CAP_REACHED");
        verify(mapper,never()).insertGenesisHolding(any());
    }

    @Test
    void genesisEligibilityUsesTheSameG4SalePolicyAsProduction() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-unified-policy");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.holdings("run-unified-policy",7L)).thenReturn(List.of(
                new AppMarketSandboxMapper.HoldingView(1L,"run-unified-policy","h1","o1",7L,
                        "GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null)));
        when(config.activeValue("market.genesis.ops.eligibility.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("market.genesis.ops.eligibility.maxPerUser")).thenReturn(Optional.of("5"));
        when(config.activeValue("market.genesis.ops.eligibility.minAccountAgeDays")).thenReturn(Optional.of("0"));

        Map<String,Object> eligibility = new AppMarketSandboxService(mapper,env,Optional.of(config))
                .genesisEligibility(7L).getData();

        assertThat(eligibility).containsEntry("eligible",true)
                .containsEntry("maxPerUser",5)
                .containsEntry("remainingCap",4L)
                .containsEntry("minAccountAgeDays",0);
    }

    @Test
    void genesisEligibilityUsesTheSandboxAccountsAuthoritativeAge() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-account-age");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.sandboxAccountAgeDays(7L)).thenReturn(10);
        when(mapper.holdings("run-account-age",7L)).thenReturn(List.of());

        Map<String,Object> eligibility = new AppMarketSandboxService(mapper,env,Optional.of(saleConfig(5,11)))
                .genesisEligibility(7L).getData();

        assertThat(eligibility).containsEntry("eligible",false)
                .containsEntry("accountAgeDays",10)
                .containsEntry("minAccountAgeDays",11);
        assertThat(eligibility.get("reasons")).isEqualTo(List.of("ACCOUNT_AGE_REQUIRED"));
    }

    @Test
    void genesisPurchaseFailsClosedAgainstTheConfiguredFiveHoldingCap() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-configured-cap");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.lockGenesisRun("run-configured-cap")).thenReturn("run-configured-cap");
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("20000"),0L));
        when(mapper.holdings("run-configured-cap",7L)).thenReturn(java.util.Collections.nCopies(5,
                new AppMarketSandboxMapper.HoldingView(1L,"run-configured-cap","h","o",7L,
                        "GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null)));
        when(config.activeValue("market.genesis.ops.eligibility.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("market.genesis.ops.eligibility.maxPerUser")).thenReturn(Optional.of("5"));
        when(config.activeValue("market.genesis.ops.eligibility.minAccountAgeDays")).thenReturn(Optional.of("0"));

        assertThatThrownBy(() -> new AppMarketSandboxService(mapper,env,Optional.of(config))
                .genesisPurchase(7L,"configured-cap-key",new AppGenesisService.PurchaseRequest(1)))
                .hasMessage("GENESIS_USER_CAP_REACHED");

        verify(mapper,never()).debitCanonicalGenesisWallet(anyLong(),any());
        verify(mapper,never()).insertGenesisOrder(any());
    }

    @Test
    void malformedOrMissingG4PolicyBlocksPrimaryAndSecondaryBeforeAnyFinancialSideEffect() {
        assertInvalidPolicyBlocksPurchases(Optional.empty(), Optional.of("5"), Optional.of("0"), "missing-enabled");
        assertInvalidPolicyBlocksPurchases(Optional.of("true"), Optional.empty(), Optional.of("0"), "missing-max");
        assertInvalidPolicyBlocksPurchases(Optional.of("true"), Optional.of("5"), Optional.empty(), "missing-age");
        assertInvalidPolicyBlocksPurchases(Optional.of("yes"), Optional.of("5"), Optional.of("0"), "invalid-enabled");
        assertInvalidPolicyBlocksPurchases(Optional.of("true"), Optional.of("five"), Optional.of("0"), "invalid-max");
        assertInvalidPolicyBlocksPurchases(Optional.of("true"), Optional.of("-1"), Optional.of("0"), "negative-max");
        assertInvalidPolicyBlocksPurchases(Optional.of("true"), Optional.of("5"), Optional.of("-1"), "negative-age");
    }

    @Test
    void genesisAccountProjectsRunScopedSupplyAndUserPurchaseOrders() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-orders");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.canonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("990"),1L));
        when(mapper.holdings("run-orders",7L)).thenReturn(List.of(new AppMarketSandboxMapper.HoldingView(
                1L,"run-orders","H-1","GEN-SBX-1",7L,"GENESIS-SANDBOX",BigDecimal.TEN,
                "ACTIVE",null,LocalDateTime.parse("2026-08-27T00:00:00"),null)));
        when(mapper.holdingCount("run-orders")).thenReturn(1L);
        when(mapper.genesisLedger("run-orders",7L)).thenReturn(List.of());
        when(mapper.genesisOrders("run-orders",7L)).thenReturn(List.of(new AppMarketSandboxMapper.GenesisOrderView(
                "GEN-SBX-1","PRIMARY",1,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,
                LocalDateTime.parse("2026-08-27T00:00:00"))));

        Map<String,Object> account = new AppMarketSandboxService(mapper,env,Optional.of(saleConfig(20,0)))
                .genesisAccount(7L).getData();

        @SuppressWarnings("unchecked") Map<String,Object> series=(Map<String,Object>)account.get("series");
        assertThat(series).containsEntry("soldSupply",1L).containsEntry("remainingSupply",999L);
        assertThat(account.get("orders").toString()).contains("GEN-SBX-1", "PRIMARY", "2026-08-26T16:00:00Z");
        assertThat(account).containsEntry("walletBalanceUsdt",new BigDecimal("990.000000"));
        verify(mapper).genesisOrders("run-orders",7L);
        verify(mapper,never()).ensureGenesisWallet(anyString(),anyLong(),any());
    }

    @Test
    void genesisCanonicalWalletRejectsAUserThatAlreadyBelongsToAnotherRun() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-new1");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.genesisArtifactsInOtherRuns("run-new1",7L)).thenReturn(1L);

        assertThatThrownBy(() -> new AppMarketSandboxService(mapper,env,Optional.empty()).genesisAccount(7L))
                .hasMessage("GENESIS_SANDBOX_USER_RUN_CONFLICT");

        verify(mapper,never()).canonicalGenesisWallet(anyLong());
        verify(mapper,never()).insertCanonicalGenesisLedger(any());
    }

    @Test
    void genesisPurchaseRechecksRunIsolationAfterTheAccountWalletMutex() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-race");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.genesisArtifactsInOtherRuns("run-race",7L)).thenReturn(0L,1L);
        when(mapper.lockGenesisRun("run-race")).thenReturn("run-race");
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("20000"),0L));

        assertThatThrownBy(() -> new AppMarketSandboxService(mapper,env,Optional.empty())
                .genesisPurchase(7L,"run-race-key",new AppGenesisService.PurchaseRequest(1)))
                .hasMessage("GENESIS_SANDBOX_USER_RUN_CONFLICT");

        verify(mapper,times(2)).genesisArtifactsInOtherRuns("run-race",7L);
        verify(mapper,never()).debitCanonicalGenesisWallet(anyLong(),any());
        verify(mapper,never()).insertGenesisOrder(any());
    }

    @Test
    void genesisPurchaseUsesTheVisibleFirstTierPriceAndAdequateSandboxBalance() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-price");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.lockGenesisRun("run-price")).thenReturn("run-price");
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("20000"),0L));
        when(mapper.canonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("12001"),1L));
        when(mapper.holdings("run-price",7L)).thenReturn(List.of());
        when(mapper.debitCanonicalGenesisWallet(7L,new BigDecimal("7999.000000"))).thenReturn(1);
        when(mapper.insertGenesisOrder(any())).thenReturn(1);
        when(mapper.insertGenesisHolding(any())).thenReturn(1);
        when(mapper.insertGenesisLedger(any())).thenReturn(1);
        when(mapper.insertCanonicalGenesisLedger(any())).thenReturn(1);

        Map<String,Object> account = new AppMarketSandboxService(mapper,env,Optional.of(saleConfig(20,0)))
                .genesisPurchase(7L,"price-key",new AppGenesisService.PurchaseRequest(1)).getData();

        var order=org.mockito.ArgumentCaptor.forClass(AppMarketSandboxMapper.GenesisOrderWrite.class);
        var ledger=org.mockito.ArgumentCaptor.forClass(AppMarketSandboxMapper.CanonicalLedgerWrite.class);
        verify(mapper).debitCanonicalGenesisWallet(7L,new BigDecimal("7999.000000"));
        verify(mapper).insertCanonicalGenesisLedger(ledger.capture());
        verify(mapper,never()).ensureGenesisWallet(anyString(),anyLong(),any());
        verify(mapper,never()).updateGenesisWallet(anyString(),anyLong(),any(),anyLong());
        verify(mapper).insertGenesisOrder(order.capture());
        assertThat(order.getValue().amount()).isEqualTo(new BigDecimal("7999.000000"));
        assertThat(order.getValue().price()).isEqualTo(new BigDecimal("7999.000000"));
        assertThat(ledger.getValue().bizType()).isEqualTo("GENESIS_PURCHASE");
        assertThat(ledger.getValue().direction()).isEqualTo("OUT");
        assertThat(ledger.getValue().balanceAfter()).isEqualTo(new BigDecimal("12001.000000"));
        assertThat(account).containsEntry("walletBalanceUsdt",new BigDecimal("12001.000000"));
    }

    @Test
    void genesisPurchaseRejectsWhenTheVisibleWalletCannotCoverThePrice() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-visible-insufficient");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.lockGenesisRun("run-visible-insufficient")).thenReturn("run-visible-insufficient");
        when(mapper.holdings("run-visible-insufficient",7L)).thenReturn(List.of());
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("1292.332000"),22L));

        assertThatThrownBy(() -> new AppMarketSandboxService(mapper,env,Optional.of(saleConfig(20,0)))
                .genesisPurchase(7L,"visible-insufficient-key",new AppGenesisService.PurchaseRequest(1)))
                .hasMessage("GENESIS_WALLET_INSUFFICIENT");

        verify(mapper,never()).insertGenesisOrder(any());
        verify(mapper,never()).insertCanonicalGenesisLedger(any());
        verify(mapper,never()).insertGenesisLedger(any());
    }

    @Test
    void genesisPurchaseSerializesRunSupplyAndRejectsOversell() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-sold-out");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.lockGenesisRun("run-sold-out")).thenReturn("run-sold-out");
        when(mapper.holdingCount("run-sold-out")).thenReturn(999L);

        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());
        assertThatThrownBy(()->service.genesisPurchase(7L,"sold-out-key",new AppGenesisService.PurchaseRequest(2)))
                .hasMessage("GENESIS_SOLD_OUT");

        verify(mapper).ensureGenesisRunLock("run-sold-out");
        verify(mapper).lockGenesisRun("run-sold-out");
        verify(mapper,never()).ensureGenesisWallet(anyString(),anyLong(),any());
        verify(mapper,never()).insertGenesisOrder(any());
    }

    @Test
    void secondarySaleRequiresSandboxSellerIdentity() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-seller");
        env.setActiveProfiles("test"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.userSandbox(8L)).thenReturn(0);
        when(mapper.holdingSnapshot("run-seller","holding-8")).thenReturn(new AppMarketSandboxMapper.HoldingView(
                1L,"run-seller","holding-8","order-8",8L,"GENESIS-SANDBOX",BigDecimal.TEN,"LISTED",new BigDecimal("12"),null,null));

        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());
        assertThatThrownBy(()->service.genesisBuy(7L,"holding-8","buy-key"))
                .hasMessage("MARKET_SANDBOX_USER_REQUIRED");
        verify(mapper,never()).updateGenesisWallet(anyString(),anyLong(),any(),anyLong());
    }

    @Test
    void secondaryBuyLocksAccountWalletsBeforeTheListedHolding() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-lock-order");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        AppMarketSandboxMapper.HoldingView listing=new AppMarketSandboxMapper.HoldingView(
                1L,"run-lock-order","holding-8","order-8",8L,"GENESIS-SANDBOX",BigDecimal.TEN,
                "LISTED",new BigDecimal("12"),null,null);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.userSandbox(8L)).thenReturn(1);
        when(mapper.holdingSnapshot("run-lock-order","holding-8")).thenReturn(listing);
        when(mapper.holding("run-lock-order","holding-8")).thenReturn(listing);
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("100"),0L));
        when(mapper.lockCanonicalGenesisWallet(8L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                8L,BigDecimal.ZERO,0L));
        when(mapper.debitCanonicalGenesisWallet(eq(7L),any())).thenReturn(1);
        when(mapper.creditCanonicalGenesisWallet(eq(8L),any())).thenReturn(1);
        when(mapper.insertGenesisOrder(any())).thenReturn(1);
        when(mapper.transferHolding(eq("run-lock-order"),eq("holding-8"),eq(8L),eq(7L),anyString(),any())).thenReturn(1);
        when(mapper.insertCanonicalGenesisLedger(any())).thenReturn(1);
        when(mapper.insertGenesisLedger(any())).thenReturn(1);
        when(mapper.canonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(
                7L,new BigDecimal("88"),1L));

        new AppMarketSandboxService(mapper,env,Optional.of(saleConfig(20,0))).genesisBuy(7L,"holding-8","buy-key");

        var order=inOrder(mapper);
        order.verify(mapper).holdingSnapshot("run-lock-order","holding-8");
        order.verify(mapper).lockCanonicalGenesisWallet(7L);
        order.verify(mapper).lockCanonicalGenesisWallet(8L);
        order.verify(mapper).holding("run-lock-order","holding-8");
    }

    @Test
    void secondaryBuyCannotBypassTheConfiguredHoldingCap() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-secondary-cap");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        AppMarketSandboxMapper.HoldingView listing=new AppMarketSandboxMapper.HoldingView(
                1L,"run-secondary-cap","holding-8","order-8",8L,"GENESIS-SANDBOX",BigDecimal.TEN,
                "LISTED",new BigDecimal("12"),null,null);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.userSandbox(8L)).thenReturn(1);
        when(mapper.holdingSnapshot("run-secondary-cap","holding-8")).thenReturn(listing);
        when(mapper.holding("run-secondary-cap","holding-8")).thenReturn(listing);
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(7L,new BigDecimal("100"),0L));
        when(mapper.lockCanonicalGenesisWallet(8L)).thenReturn(new AppMarketSandboxMapper.CanonicalWallet(8L,BigDecimal.ZERO,0L));
        when(mapper.holdings("run-secondary-cap",7L)).thenReturn(java.util.Collections.nCopies(5,
                new AppMarketSandboxMapper.HoldingView(2L,"run-secondary-cap","owned","owned-order",7L,
                        "GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null)));

        assertThatThrownBy(() -> new AppMarketSandboxService(mapper,env,Optional.of(saleConfig(5,0)))
                .genesisBuy(7L,"holding-8","secondary-cap-key"))
                .hasMessage("GENESIS_USER_CAP_REACHED");

        verify(mapper,never()).debitCanonicalGenesisWallet(anyLong(),any());
        verify(mapper,never()).transferHolding(anyString(),anyString(),anyLong(),anyLong(),anyString(),any());
    }

    @Test
    void genesisEligibilityConsumesG4HolderPolicyAndRunScopedPriorityFacts() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-policy");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.holdings("run-policy",7L)).thenReturn(List.of(
                new AppMarketSandboxMapper.HoldingView(1L,"run-policy","h1","o1",7L,"GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null),
                new AppMarketSandboxMapper.HoldingView(2L,"run-policy","h2","o2",7L,"GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null)));
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "market.genesis.ops.eligibility.enabled" -> Optional.of("true");
            case "market.genesis.ops.eligibility.maxPerUser" -> Optional.of("5");
            case "market.genesis.ops.eligibility.minAccountAgeDays" -> Optional.of("0");
            case "market.genesis.ops.holder.allocationNexPerHolding" -> Optional.of("80000.25");
            case "market.genesis.ops.holder.priorityTop1Percent" -> Optional.of("1");
            case "market.genesis.ops.holder.priorityTop3Percent" -> Optional.of("3");
            case "market.genesis.ops.holder.priorityTop5Percent" -> Optional.of("5");
            case "market.genesis.ops.holder.policyVersion" -> Optional.of("genesis-holder-v1-e2e");
            case "market.genesis.ops.holder.effectiveAt" -> Optional.of("2026-08-17T00:05:00Z");
            default -> Optional.empty();
        });
        when(mapper.currentPriorityRank("run-policy",7L,"GENESIS-SANDBOX")).thenReturn(2);
        when(mapper.activeHolderCount("run-policy","GENESIS-SANDBOX")).thenReturn(3L);

        Map<String,Object> eligibility = new AppMarketSandboxService(mapper,env,Optional.of(config))
                .genesisEligibility(7L).getData();

        assertThat(eligibility.get("reservedAllocation")).isEqualTo(new BigDecimal("160000.50"));
        assertThat(eligibility.get("priorityRank")).isEqualTo(2);
        assertThat(eligibility.get("priorityTier")).isEqualTo("STANDARD");
        assertThat(eligibility.get("policyVersion")).isEqualTo("genesis-holder-v1-e2e");
        assertThat(eligibility.get("effectiveAt")).isEqualTo("2026-08-17T00:05:00Z");
        assertThat(((Map<?,?>) eligibility.get("provenance")).get("runId")).isEqualTo("run-policy");
    }

    @Test
    void genesisEligibilityNeverEstimatesForNonHolderWhenG4PolicyIsUnavailable() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-empty");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(mapper.userSandbox(9L)).thenReturn(1);
        when(mapper.holdings("run-empty",9L)).thenReturn(List.of());
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "market.genesis.ops.eligibility.enabled" -> Optional.of("true");
            case "market.genesis.ops.eligibility.maxPerUser" -> Optional.of("5");
            case "market.genesis.ops.eligibility.minAccountAgeDays" -> Optional.of("0");
            default -> Optional.empty();
        });

        Map<String,Object> eligibility = new AppMarketSandboxService(mapper,env,Optional.of(config))
                .genesisEligibility(9L).getData();

        assertThat(eligibility.get("status")).isEqualTo("CONFIG_UNAVAILABLE");
        assertThat(eligibility.get("reservedAllocation")).isNull();
        assertThat(eligibility.get("priorityRank")).isNull();
        assertThat(eligibility.get("priorityTier")).isEqualTo("NONE");
        assertThat(eligibility.get("policyVersion")).isNull();
        assertThat(eligibility.get("qualificationReasonCodes")).isEqualTo(List.of("NO_ACTIVE_HOLDINGS"));
        verify(mapper,never()).currentPriorityRank(anyString(),anyLong(),anyString());
    }

    @Test
    void matchingRunFixtureProjectsHolderAllocationAndTieRankWithoutProductionRows() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-fixture");
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        AppGenesisSandboxFixtureService fixtures=new AppGenesisSandboxFixtureService(mapper,env);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.userSandbox(8L)).thenReturn(1);
        when(mapper.genesisWallet("run-fixture",7L)).thenReturn(new AppMarketSandboxMapper.GenesisWallet("run-fixture",7L,new BigDecimal("1000"),0L));
        when(mapper.genesisLedger("run-fixture",7L)).thenReturn(List.of());
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "market.genesis.ops.eligibility.enabled" -> Optional.of("true");
            case "market.genesis.ops.eligibility.maxPerUser" -> Optional.of("5");
            case "market.genesis.ops.eligibility.minAccountAgeDays" -> Optional.of("0");
            case "market.genesis.ops.holder.allocationNexPerHolding" -> Optional.of("251");
            case "market.genesis.ops.holder.priorityTop1Percent" -> Optional.of("1");
            case "market.genesis.ops.holder.priorityTop3Percent" -> Optional.of("3");
            case "market.genesis.ops.holder.priorityTop5Percent" -> Optional.of("5");
            case "market.genesis.ops.holder.policyVersion" -> Optional.of("fixture-v1");
            case "market.genesis.ops.holder.effectiveAt" -> Optional.of("2026-08-17T00:05:00Z");
            default -> Optional.empty();
        });
        fixtures.replace("run-fixture",7L,List.of(
                new AppGenesisSandboxFixtureService.HolderSpec(7L,2),
                new AppGenesisSandboxFixtureService.HolderSpec(8L,2)));

        Map<String,Object> eligibility = new AppMarketSandboxService(mapper,env,Optional.of(config))
                .genesisEligibility(7L).getData();

        assertThat(eligibility.get("reservedAllocation")).isEqualTo(new BigDecimal("502.00"));
        assertThat(eligibility.get("priorityRank")).isEqualTo(1);
        assertThat(eligibility.get("priorityTier")).isEqualTo("TOP_1");
        assertThat(eligibility.get("policyVersion")).isEqualTo("fixture-v1");
        verify(mapper,never()).holdings("run-fixture",7L);
        verify(mapper,never()).currentPriorityRank(anyString(),anyLong(),anyString());
    }

    private PlatformConfigFacade saleConfig(int maxPerUser,int minAccountAgeDays) {
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "market.genesis.ops.eligibility.enabled" -> Optional.of("true");
            case "market.genesis.ops.eligibility.maxPerUser" -> Optional.of(String.valueOf(maxPerUser));
            case "market.genesis.ops.eligibility.minAccountAgeDays" -> Optional.of(String.valueOf(minAccountAgeDays));
            default -> Optional.empty();
        });
        return config;
    }

    private void assertInvalidPolicyBlocksPurchases(Optional<String> enabled, Optional<String> maxPerUser,
                                                    Optional<String> minAccountAgeDays, String suffix) {
        String run="run-invalid-policy-"+suffix;
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID",run);
        env.setActiveProfiles("test");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(config.activeValue("market.genesis.ops.eligibility.enabled")).thenReturn(enabled);
        when(config.activeValue("market.genesis.ops.eligibility.maxPerUser")).thenReturn(maxPerUser);
        when(config.activeValue("market.genesis.ops.eligibility.minAccountAgeDays")).thenReturn(minAccountAgeDays);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.userSandbox(8L)).thenReturn(1);
        when(mapper.lockGenesisRun(run)).thenReturn(run);
        when(mapper.lockCanonicalGenesisWallet(7L)).thenReturn(
                new AppMarketSandboxMapper.CanonicalWallet(7L,new BigDecimal("20000"),0L));
        when(mapper.lockCanonicalGenesisWallet(8L)).thenReturn(
                new AppMarketSandboxMapper.CanonicalWallet(8L,BigDecimal.ZERO,0L));
        when(mapper.holdings(run,7L)).thenReturn(List.of());
        AppMarketSandboxMapper.HoldingView listing=new AppMarketSandboxMapper.HoldingView(
                1L,run,"holding-8","order-8",8L,"GENESIS-SANDBOX",BigDecimal.TEN,
                "LISTED",new BigDecimal("12"),null,null);
        when(mapper.holdingSnapshot(run,"holding-8")).thenReturn(listing);
        when(mapper.holding(run,"holding-8")).thenReturn(listing);

        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.of(config));
        assertThatThrownBy(() -> service.genesisPurchase(7L,"primary-"+suffix,
                new AppGenesisService.PurchaseRequest(1)))
                .hasMessage("GENESIS_SALE_POLICY_UNAVAILABLE");
        assertThatThrownBy(() -> service.genesisBuy(7L,"holding-8","secondary-"+suffix))
                .hasMessage("GENESIS_SALE_POLICY_UNAVAILABLE");

        verify(mapper,never()).debitCanonicalGenesisWallet(anyLong(),any());
        verify(mapper,never()).creditCanonicalGenesisWallet(anyLong(),any());
        verify(mapper,never()).insertGenesisOrder(any());
        verify(mapper,never()).insertGenesisHolding(any());
        verify(mapper,never()).transferHolding(anyString(),anyString(),anyLong(),anyLong(),anyString(),any());
        verify(mapper,never()).insertCanonicalGenesisLedger(any());
        verify(mapper,never()).insertGenesisLedger(any());
    }
}
