package ffdd.commerce.genesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.commerce.client.CommerceComplianceClient;
import ffdd.commerce.client.CommerceWalletClient;
import ffdd.commerce.client.dto.ComplianceGateResponse;
import ffdd.commerce.client.dto.WalletLedgerResponse;
import ffdd.commerce.genesis.domain.GenesisHolding;
import ffdd.commerce.genesis.domain.GenesisOrder;
import ffdd.commerce.genesis.domain.GenesisSeries;
import ffdd.commerce.genesis.dto.GenesisPurchaseRequest;
import ffdd.commerce.genesis.mapper.GenesisHoldingMapper;
import ffdd.commerce.genesis.mapper.GenesisOrderMapper;
import ffdd.commerce.genesis.mapper.GenesisSeriesMapper;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GenesisServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final GenesisSeriesMapper seriesMapper = mock(GenesisSeriesMapper.class);
    private final GenesisOrderMapper orderMapper = mock(GenesisOrderMapper.class);
    private final GenesisHoldingMapper holdingMapper = mock(GenesisHoldingMapper.class);
    private final CommerceComplianceClient complianceClient = mock(CommerceComplianceClient.class);
    private final CommerceWalletClient walletClient = mock(CommerceWalletClient.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final GenesisService service = new GenesisService(
            seriesMapper, orderMapper, holdingMapper, complianceClient, walletClient, outboxService, CLOCK);

    @Test
    void purchaseApprovedOrderDebitsWalletAllocatesHoldingsAndPublishesEvent() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(seriesMapper.selectOne(any(Wrapper.class))).thenReturn(series("GENESIS-2026", 1000, 10, "9999.000000"));
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(gate(77L, "APPROVE")));
        when(seriesMapper.update(eq(null), any())).thenReturn(1);
        when(walletClient.postDebit(any())).thenReturn(ApiResult.ok(ledger(88L)));
        AtomicReference<String> insertedStatus = new AtomicReference<>();
        doAnswer(invocation -> {
            insertedStatus.set(invocation.getArgument(0, GenesisOrder.class).getStatus());
            return 1;
        }).when(orderMapper).insert(any(GenesisOrder.class));

        GenesisOrder order = service.purchase(request("GENESIS-2026", 2, "client-1"));

        ArgumentCaptor<GenesisOrder> completedOrder = ArgumentCaptor.forClass(GenesisOrder.class);
        ArgumentCaptor<GenesisHolding> holding = ArgumentCaptor.forClass(GenesisHolding.class);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);

        verify(orderMapper).insert(any(GenesisOrder.class));
        verify(orderMapper).updateById(completedOrder.capture());
        verify(holdingMapper, times(2)).insert(holding.capture());
        verify(outboxService).publish(eq("GENESIS_ORDER"), eq(order.getOrderNo()), eq("GenesisPurchased"), payload.capture());

        assertThat(insertedStatus.get()).isEqualTo("PENDING_PAYMENT");
        assertThat(completedOrder.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(completedOrder.getValue().getWalletLedgerId()).isEqualTo(88L);
        assertThat(order.getStatus()).isEqualTo("COMPLETED");
        assertThat(order.getAmountUsdt()).isEqualByComparingTo("19998.000000");
        assertThat(holding.getAllValues()).extracting(GenesisHolding::getSeriesCode)
                .containsOnly("GENESIS-2026");
        assertThat(payload.getValue())
                .containsEntry("orderNo", order.getOrderNo())
                .containsEntry("seriesCode", "GENESIS-2026")
                .containsEntry("quantity", 2);
    }

    @Test
    void purchaseInReviewCreatesReviewOrderWithoutWalletDebitOrSupplyClaim() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(seriesMapper.selectOne(any(Wrapper.class))).thenReturn(series("GENESIS-2026", 1000, 10, "9999.000000"));
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(gate(78L, "REVIEW")));

        GenesisOrder order = service.purchase(request("GENESIS-2026", 1, "client-review"));

        ArgumentCaptor<GenesisOrder> insertedOrder = ArgumentCaptor.forClass(GenesisOrder.class);
        verify(orderMapper).insert(insertedOrder.capture());
        verify(seriesMapper, never()).update(any(), any());
        verifyNoInteractions(walletClient, holdingMapper, outboxService);
        assertThat(insertedOrder.getValue().getStatus()).isEqualTo("REVIEWING");
        assertThat(order.getStatus()).isEqualTo("REVIEWING");
        assertThat(order.getRiskDecisionId()).isEqualTo(78L);
    }

    @Test
    void purchaseRejectsSoldOutSeriesBeforeWalletDebit() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(seriesMapper.selectOne(any(Wrapper.class))).thenReturn(series("GENESIS-2026", 1000, 1000, "9999.000000"));

        assertThatThrownBy(() -> service.purchase(request("GENESIS-2026", 1, "client-sold-out")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sold out");

        verifyNoInteractions(complianceClient, walletClient, holdingMapper, outboxService);
    }

    @Test
    void purchaseReusesExistingOrderForSameClientRequestNo() {
        GenesisOrder existing = new GenesisOrder();
        existing.setOrderNo("GEN-EXISTING");
        existing.setUserId(9001L);
        existing.setClientRequestNo("client-dup");
        existing.setStatus("COMPLETED");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        GenesisOrder order = service.purchase(request("GENESIS-2026", 1, "client-dup"));

        assertThat(order).isSameAs(existing);
        verifyNoInteractions(seriesMapper, complianceClient, walletClient, holdingMapper, outboxService);
    }

    private GenesisPurchaseRequest request(String seriesCode, int quantity, String clientRequestNo) {
        GenesisPurchaseRequest request = new GenesisPurchaseRequest();
        request.setUserId(9001L);
        request.setSeriesCode(seriesCode);
        request.setQuantity(quantity);
        request.setClientRequestNo(clientRequestNo);
        return request;
    }

    private GenesisSeries series(String seriesCode, int totalSupply, int soldSupply, String price) {
        GenesisSeries series = new GenesisSeries();
        series.setId(1L);
        series.setSeriesCode(seriesCode);
        series.setName("Nexion Genesis Node");
        series.setTotalSupply(totalSupply);
        series.setSoldSupply(soldSupply);
        series.setPriceUsdt(new BigDecimal(price));
        series.setStatus("ACTIVE");
        series.setRoyaltyBps(800);
        series.setIsDeleted(0);
        return series;
    }

    private ComplianceGateResponse gate(Long decisionId, String decision) {
        ComplianceGateResponse response = new ComplianceGateResponse();
        response.setDecisionId(decisionId);
        response.setDecision(decision);
        response.setReason("test");
        return response;
    }

    private WalletLedgerResponse ledger(Long id) {
        WalletLedgerResponse response = new WalletLedgerResponse();
        response.setId(id);
        response.setBizNo("GEN");
        response.setAsset("USDT");
        response.setDirection("OUT");
        response.setAmount(new BigDecimal("19998.000000"));
        response.setStatus("SUCCESS");
        return response;
    }
}
