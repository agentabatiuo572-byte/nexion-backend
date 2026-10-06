package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.team.domain.DirectReferralPolicy;
import ffdd.opsconsole.team.dto.DirectReferralPolicyRequest;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SevenLayerPolicyServiceTest {
    private final DirectReferralMapper mapper=mock(DirectReferralMapper.class);
    private final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    private final NexMarketMapper prices=mock(NexMarketMapper.class);
    private final TreasuryCoverageFacade coverage=mock(TreasuryCoverageFacade.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final DirectReferralPolicyService service=new DirectReferralPolicyService(mapper,config,prices,coverage,mock(AuditLogService.class),json,
            Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"),ZoneOffset.UTC),new MockEnvironment().withProperty("unused","fixture"));
    private DirectReferralPolicy.Rule split(boolean enabled,String share){return new DirectReferralPolicy.Rule(enabled,BigDecimal.TEN,new BigDecimal(share),0);}
    @Test void payoutDirectionUsesActualCurrenciesAndNeedsNoPriceForKnownDirection(){
        when(mapper.sevenLayerReference()).thenReturn(List.of(Map.of("nexReward",new BigDecimal("2"),"usdtPct",BigDecimal.TEN)));
        when(prices.latestNexUsdtPrice()).thenReturn(new BigDecimal("0.01"));
        assertThat(service.purchaseAmplifies(split(true,"60"),DirectReferralPolicy.DISABLED)).isTrue();
        assertThat(service.purchaseAmplifies(split(false,"60"),split(true,"60"))).isTrue();
        assertThat(service.purchaseAmplifies(split(true,"50"),split(true,"60"))).isTrue();
        assertThat(service.purchaseAmplifies(split(true,"70"),split(true,"60"))).isTrue();
        when(prices.latestNexUsdtPrice()).thenReturn(null);
        assertThat(service.purchaseAmplifies(split(false,"60"),DirectReferralPolicy.DISABLED)).isFalse();
        assertThat(service.purchaseAmplifies(split(true,"60"),split(true,"60"))).isFalse();
        assertThatThrownBy(()->service.purchaseAmplifies(split(true,"60"),DirectReferralPolicy.DISABLED)).hasMessage("DIRECT_REFERRAL_PRICE_UNAVAILABLE");
        when(mapper.sevenLayerReference()).thenReturn(List.of(Map.of("nexReward",BigDecimal.ZERO)));
        assertThat(service.purchaseAmplifies(split(true,"60"),DirectReferralPolicy.DISABLED)).isTrue();
        when(mapper.sevenLayerReference()).thenReturn(List.of(Map.of("nexReward",new BigDecimal("50"))));
        when(prices.latestNexUsdtPrice()).thenReturn(new BigDecimal("0.01"));
        assertThat(service.purchaseAmplifies(split(true,"60"),DirectReferralPolicy.DISABLED)).isFalse();
    }
    @Test void oldEnabledPurchaseIsNotTheBaselineForNewSevenLayerSplit() throws Exception {
        when(config.activeValue(DirectReferralPolicyService.CUTOVER_KEY)).thenReturn(Optional.of("2026-10-05T00:00:00Z"));
        when(config.activeValueForUpdate(anyString())).thenReturn(Optional.of("0"));when(mapper.latestVersionForUpdate()).thenReturn(1L);
        var old=new DirectReferralPolicy.Rule(true,new BigDecimal("20"),new BigDecimal("60"),30);
        when(mapper.policyAtForUpdate(any())).thenReturn(new DirectReferralMapper.PolicyRow(1,LocalDateTime.of(2026,10,5,0,0),json.writeValueAsString(old),json.writeValueAsString(DirectReferralPolicy.DISABLED)));
        when(mapper.sevenLayerReferenceForUpdate()).thenReturn(List.of(Map.of("nexReward",new BigDecimal("2"))));when(prices.latestNexUsdtPriceForUpdate()).thenReturn(new BigDecimal("0.01"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("50"),new BigDecimal("100"),true));
        var request=new DirectReferralPolicyRequest(2,1L,0L,new DirectReferralPolicyRequest.PurchaseSplit(true,new BigDecimal("60")),null,DirectReferralPolicy.DISABLED,"B1 must use original seven layer composition");
        A2ReplayContext.enterReplay("SEVEN-V1-BASELINE");try{assertThatThrownBy(()->service.publish("SEVEN-V1-BASELINE",request)).hasMessage("COVERAGE_BELOW_REDLINE");}finally{A2ReplayContext.exitReplay();}
        verify(mapper,never()).insertPolicy(anyLong(),any(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void v2CannotCarryASecondPurchaseBudgetOrLegacyPayload(){
        var valid=new DirectReferralPolicyRequest(2,1L,0L,new DirectReferralPolicyRequest.PurchaseSplit(true,new BigDecimal("60")),null,DirectReferralPolicy.DISABLED,"approved split policy validation");
        DirectReferralPolicyService.validate(valid,Instant.now());
        var invalid=new DirectReferralPolicyRequest(2,1L,0L,valid.purchaseSplit(),split(true,"60"),DirectReferralPolicy.DISABLED,valid.reason());
        assertThatThrownBy(()->DirectReferralPolicyService.validate(invalid,Instant.now())).hasMessage("DIRECT_REFERRAL_POLICY_SCHEMA_INVALID");
    }
}
