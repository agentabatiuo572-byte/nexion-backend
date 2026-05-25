package ffdd.wallet.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.common.rocketmq.RocketMqAclProperties;
import ffdd.wallet.dto.ApplyRiskDecisionRequest;
import ffdd.wallet.dto.RiskDecisionApplyResult;
import ffdd.wallet.dto.RiskDecisionFinalizedPayload;
import ffdd.wallet.service.WalletService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WalletRiskDecisionFinalizedRocketListenerTest {
    private final WalletService walletService = mock(WalletService.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WalletRiskDecisionFinalizedRocketListener listener = new WalletRiskDecisionFinalizedRocketListener(
            walletService,
            objectMapper,
            deliveryService,
            "127.0.0.1:9876",
            "nexion-risk-decision-finalized",
            "wallet-risk-group",
            5,
            new RocketMqAclProperties());

    @Test
    void recordsSuccessfulDeliveryAfterApplyingRiskDecision() throws Exception {
        EventOutboxMessage message = riskDecisionMessage("EVT-RISK-1", "WD-1", "APPROVE");
        when(deliveryService.claim(
                any(EventOutboxMessage.class),
                eq("wallet-risk-group"),
                eq("nexion-risk-decision-finalized"),
                eq("MSG-RISK-1"),
                eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-RISK-1", "PROCESSING", 1));
        when(walletService.applyRiskDecision(any(ApplyRiskDecisionRequest.class)))
                .thenReturn(new RiskDecisionApplyResult("WITHDRAWAL", "WD-1", "PENDING_CHAIN"));

        boolean retry = listener.consumeMessage(
                rocketMessage("MSG-RISK-1", 0, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        ArgumentCaptor<ApplyRiskDecisionRequest> captor =
                ArgumentCaptor.forClass(ApplyRiskDecisionRequest.class);
        verify(walletService).applyRiskDecision(captor.capture());
        assertThat(captor.getValue().getDecisionId()).isEqualTo(701L);
        assertThat(captor.getValue().getBizType()).isEqualTo("WITHDRAWAL");
        assertThat(captor.getValue().getBizNo()).isEqualTo("WD-1");
        assertThat(captor.getValue().getDecision()).isEqualTo("APPROVE");
        verify(deliveryService).markSuccess("wallet-risk-group", "EVT-RISK-1", 1);
    }

    @Test
    void duplicateDeliveryDoesNotApplyRiskDecisionAgain() throws Exception {
        EventOutboxMessage message = riskDecisionMessage("EVT-RISK-DUP", "WD-DUP", "APPROVE");
        when(deliveryService.claim(
                any(EventOutboxMessage.class),
                eq("wallet-risk-group"),
                eq("nexion-risk-decision-finalized"),
                eq("MSG-RISK-DUP"),
                eq(1)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "EVT-RISK-DUP", "SUCCESS", 1));

        boolean retry = listener.consumeMessage(
                rocketMessage("MSG-RISK-DUP", 1, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        verify(walletService, never()).applyRiskDecision(any(ApplyRiskDecisionRequest.class));
    }

    private EventOutboxMessage riskDecisionMessage(String eventId, String bizNo, String decision) throws Exception {
        RiskDecisionFinalizedPayload payload = new RiskDecisionFinalizedPayload();
        payload.setDecisionId(701L);
        payload.setDecisionNo("RISK-WITHDRAWAL-" + bizNo);
        payload.setUserId(10001L);
        payload.setBizType("WITHDRAWAL");
        payload.setBizNo(bizNo);
        payload.setDecision(decision);
        payload.setReason("MANUAL_" + decision);
        payload.setReviewedBy("admin-1");

        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType("RiskDecisionFinalized");
        message.setAggregateType("RISK_DECISION");
        message.setAggregateId(payload.getDecisionNo());
        message.setPayload(objectMapper.writeValueAsString(payload));
        return message;
    }

    private MessageExt rocketMessage(String msgId, int reconsumeTimes, byte[] body) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setReconsumeTimes(reconsumeTimes);
        message.setBody(body);
        return message;
    }
}
