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
    void exchangeMutationUsesRunAndAccountScopedWalletAndReplay() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-alpha"); env.setActiveProfiles("dev"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
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
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-beta"); env.setActiveProfiles("dev"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.exchangeWallet("run-beta",7L)).thenReturn(new AppMarketSandboxMapper.ExchangeWallet("run-beta",7L,BigDecimal.ZERO,BigDecimal.ZERO,0L));
        when(mapper.exchangeOrders("run-beta",7L)).thenReturn(List.of()); when(mapper.exchangeLedger("run-beta",7L)).thenReturn(List.of());
        new AppMarketSandboxService(mapper,env,Optional.empty()).exchangeState(7L);
        verify(mapper).exchangeWallet("run-beta",7L); verify(mapper,never()).exchangeWallet("run-alpha",7L);
    }

    @Test
    void dailyCapCannotBeBypassedByDisablingQueueMode() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-caps");
        env.setActiveProfiles("dev"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
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
        env.setActiveProfiles("dev"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.genesisWallet("run-genesis",7L)).thenReturn(new AppMarketSandboxMapper.GenesisWallet("run-genesis",7L,new BigDecimal("1000"),0L));
        when(mapper.holdings("run-genesis",7L)).thenReturn(java.util.Collections.nCopies(19,
                new AppMarketSandboxMapper.HoldingView(1L,"run-genesis","h","o",7L,"GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null)));

        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());
        assertThatThrownBy(()->service.genesisPurchase(7L,"purchase-key",new AppGenesisService.PurchaseRequest(2)))
                .hasMessage("GENESIS_MAX_PER_USER_EXCEEDED");
        verify(mapper,never()).insertGenesisHolding(any());
    }

    @Test
    void secondarySaleRequiresSandboxSellerIdentity() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-seller");
        env.setActiveProfiles("dev"); AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.userSandbox(8L)).thenReturn(0);
        when(mapper.holding("run-seller","holding-8")).thenReturn(new AppMarketSandboxMapper.HoldingView(
                1L,"run-seller","holding-8","order-8",8L,"GENESIS-SANDBOX",BigDecimal.TEN,"LISTED",new BigDecimal("12"),null,null));

        AppMarketSandboxService service=new AppMarketSandboxService(mapper,env,Optional.empty());
        assertThatThrownBy(()->service.genesisBuy(7L,"holding-8","buy-key"))
                .hasMessage("MARKET_SANDBOX_USER_REQUIRED");
        verify(mapper,never()).updateGenesisWallet(anyString(),anyLong(),any(),anyLong());
    }

    @Test
    void genesisEligibilityConsumesG4HolderPolicyAndRunScopedPriorityFacts() {
        MockEnvironment env=new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID","run-policy");
        env.setActiveProfiles("dev");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.holdings("run-policy",7L)).thenReturn(List.of(
                new AppMarketSandboxMapper.HoldingView(1L,"run-policy","h1","o1",7L,"GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null),
                new AppMarketSandboxMapper.HoldingView(2L,"run-policy","h2","o2",7L,"GENESIS-SANDBOX",BigDecimal.TEN,"ACTIVE",null,null,null)));
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
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
        env.setActiveProfiles("dev");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(mapper.userSandbox(9L)).thenReturn(1);
        when(mapper.holdings("run-empty",9L)).thenReturn(List.of());
        when(config.activeValue(anyString())).thenReturn(Optional.empty());

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
        env.setActiveProfiles("dev");
        AppMarketSandboxMapper mapper=mock(AppMarketSandboxMapper.class);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        AppGenesisSandboxFixtureService fixtures=new AppGenesisSandboxFixtureService(mapper,env);
        when(mapper.userSandbox(7L)).thenReturn(1); when(mapper.userSandbox(8L)).thenReturn(1);
        when(mapper.genesisWallet("run-fixture",7L)).thenReturn(new AppMarketSandboxMapper.GenesisWallet("run-fixture",7L,new BigDecimal("1000"),0L));
        when(mapper.genesisLedger("run-fixture",7L)).thenReturn(List.of());
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
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
}
