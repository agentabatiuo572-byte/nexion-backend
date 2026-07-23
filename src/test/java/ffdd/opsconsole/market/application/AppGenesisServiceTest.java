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
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppGenesisServiceTest {
    private final AppGenesisMapper mapper=mock(AppGenesisMapper.class);
    private final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox=mock(EventOutboxService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final AppGenesisService service=new AppGenesisService(mapper,config,idempotency,outbox,audit,
            Clock.fixed(Instant.parse("2026-07-22T04:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings({"rawtypes","unchecked"})
    void setUp(){
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.controlValue(anyString())).thenReturn(null);
        when(mapper.activeSeries()).thenReturn(series());
        when(mapper.lockActiveSeries()).thenReturn(series());
        when(mapper.holdingCount("genesis-main")).thenReturn(0L);
        when(mapper.lockHoldingCount("genesis-main")).thenReturn(0L);
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.userPolicy(42L)).thenReturn(new AppGenesisMapper.UserPolicyRow(42L,"APPROVED","VN","P1",4,"2026-W30"));
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
    void kycFailureStopsBeforeWalletMutation(){
        when(mapper.userPolicy(42L)).thenReturn(new AppGenesisMapper.UserPolicyRow(42L,"PENDING","VN","P1",4,"2026-W30"));
        assertThatThrownBy(()->service.purchase(42L,"purchase-kyc",new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_KYC_REQUIRED");
        verify(mapper,never()).debitWallet(any(),any());
    }

    @Test
    void j1PauseWinsBeforePurchase(){
        when(mapper.controlValue("killswitch.genesis")).thenReturn("disabled");
        assertThatThrownBy(()->service.purchase(42L,"purchase-paused",new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_MARKET_PAUSED");
        verify(mapper,never()).debitWallet(any(),any());
    }

    @Test
    void finalSlotUsesCurrentLockingHoldingCountBeforeAnyWalletMutation(){
        when(mapper.lockHoldingCount("genesis-main")).thenReturn(1000L);

        assertThatThrownBy(()->service.purchase(42L,"purchase-sold-out",new AppGenesisService.PurchaseRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("GENESIS_SOLD_OUT");

        verify(mapper,never()).debitWallet(any(),any());
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
    void seriesMutexMakesSecondLastSlotBuyerObserveSoldOutWithoutSideEffects(){
        when(mapper.lockHoldingCount("genesis-main")).thenReturn(999L,1000L);
        when(mapper.updateSoldSupply(1L,1000L)).thenReturn(1);
        when(mapper.lockActiveUser(43L)).thenReturn(43L);
        when(mapper.userPolicy(43L)).thenReturn(new AppGenesisMapper.UserPolicyRow(
                43L,"APPROVED","VN","P1",2,"2026-W30"));

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

    private AppGenesisMapper.SeriesRow series(){
        return new AppGenesisMapper.SeriesRow(1L,"genesis-main","Genesis Node",1000,
                new BigDecimal("9999"),250,new BigDecimal("0.1"),"ACTIVE");
    }
}
