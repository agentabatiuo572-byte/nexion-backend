package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankConfigRow;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.domain.VRankPromotionContext;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Real promotion engine and Spring transaction interceptor; all persistence is isolated in memory. */
class VRankPassiveEvaluationConsumerTest {
    private final TeamCommissionRepository repository = mock(TeamCommissionRepository.class);
    private final VRankPerformanceRepository performance = mock(VRankPerformanceRepository.class);
    private final TeamCommissionMapper mapper = mock(TeamCommissionMapper.class);
    private final UnilevelCommissionService oldNetwork = mock(UnilevelCommissionService.class);
    private final VRankRewardDispatcher rewards = mock(VRankRewardDispatcher.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Map<Long, String> ranks = new HashMap<>();
    private final Map<Long, VRankEvaluationSnapshot> snapshots = new HashMap<>();
    private final List<RankLog> logs = new ArrayList<>();
    private GenericApplicationContext context;
    private VRankPromotionEngine engine;
    private VRankPassiveEvaluationConsumer consumer;

    @BeforeEach
    void setUp() {
        when(repository.vRankConfigRows()).thenReturn(List.of(
                rank("V0", "0", 0, "0", 0),
                rank("V1", "500", 3, "0", 1),
                rank("V2", "0", 0, "5000", 2)));
        when(repository.currentMemberVRank(anyLong())).thenAnswer(i -> ranks.getOrDefault(i.getArgument(0), "V0"));
        when(repository.updateMemberVRank(anyLong(), anyString())).thenAnswer(i -> {
            ranks.put(i.getArgument(0), i.getArgument(1));
            return true;
        });
        when(repository.insertUserLevelLog(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(i -> {
            logs.add(new RankLog(i.getArgument(0), i.getArgument(2), i.getArgument(6)));
            return true;
        });
        when(performance.computeSnapshot(anyLong())).thenAnswer(i -> snapshots.getOrDefault(
                i.getArgument(0), VRankEvaluationSnapshot.empty()));
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.of("on"));
        ObjectMapper json = new ObjectMapper();
        engine = spy(new VRankPromotionEngine(repository, performance, config, json, rewards, outbox));
        when(transactions.getTransaction(any())).thenAnswer(i -> new FixtureTransaction());
        doAnswer(i -> {
            FixtureTransaction tx = i.getArgument(0);
            ranks.clear();
            ranks.putAll(tx.beforeRanks);
            logs.clear();
            logs.addAll(tx.beforeLogs);
            return null;
        }).when(transactions).rollback(any());
        ProxyFactory proxy = new ProxyFactory(engine);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        VRankPromotionEngine transactionalEngine = (VRankPromotionEngine) proxy.getProxy();
        context = new GenericApplicationContext();
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(VRankPromotionEngine.class, () -> transactionalEngine);
        context.registerBean(UnilevelCommissionService.class, () -> oldNetwork);
        context.registerBean(TeamCommissionMapper.class, () -> mapper);
        // Constructor autowiring exercises the unchanged test against both the old and repaired consumer.
        context.registerBean(VRankPassiveEvaluationConsumer.class,
                bd -> ((AbstractBeanDefinition) bd).setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR));
        context.refresh();
        consumer = context.getBean(VRankPassiveEvaluationConsumer.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void paidChildQualifiesDirectParentWithoutChildPromotion() {
        eligible(20L);
        snapshots.put(10L, snapshot("1299", "0", 0));
        when(mapper.listUplineChain(10L, 7)).thenReturn(List.of(upline(20L, 1)));

        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"user_id\":10}"));

        assertThat(ranks.getOrDefault(10L, "V0")).isEqualTo("V0");
        assertThat(ranks.get(20L)).isEqualTo("V1");
        assertThat(logs).containsExactly(new RankLog(20L, "V1", "CHECKOUT-1"));
        var order = inOrder(engine);
        order.verify(engine).evaluate(ctx(10L));
        order.verify(engine).evaluate(ctx(20L));
        verify(rewards).dispatch(eq(20L), eq("V1"), any(), eq(ctx(20L)));
        verifyNoInteractions(oldNetwork);
    }

    @Test
    void unchangedIntermediateRankDoesNotStopGrandparentEvaluation() {
        eligible(30L);
        when(mapper.listUplineChain(10L, 7)).thenReturn(List.of(upline(20L, 1), upline(30L, 2)));

        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"userId\":10}"));

        assertThat(ranks.getOrDefault(20L, "V0")).isEqualTo("V0");
        assertThat(ranks.get(30L)).isEqualTo("V1");
        assertThat(logs).containsExactly(new RankLog(30L, "V1", "CHECKOUT-1"));
        verify(outbox).publish(eq("vrank"), eq("30"), eq("VRANK_PROMOTION_COMPLETED"), anyMap());
    }

    @Test
    void teamVolumeCanQualifyAncestorV2WithoutIntermediatePromotion() {
        ranks.put(30L, "V1");
        snapshots.put(30L, snapshot("1338.8", "5000", 3));
        when(mapper.listUplineChain(10L, 7)).thenReturn(List.of(upline(20L, 1), upline(30L, 2)));

        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"user_id\":10}"));

        assertThat(ranks.get(30L)).isEqualTo("V2");
        assertThat(logs).containsExactly(new RankLog(30L, "V2", "CHECKOUT-1"));
    }

    @Test
    void duplicateAndCyclicQueryRowsEvaluateEachAffectedUserOnceThroughDepthSeven() {
        eligible(20L);
        eligible(30L);
        eligible(40L);
        when(mapper.listUplineChain(10L, 7)).thenReturn(List.of(
                upline(20L, 1), upline(20L, 2), upline(10L, 3), upline(30L, 7), upline(40L, 8)));

        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"user_id\":10}"));

        verify(engine).evaluate(ctx(10L));
        verify(engine).evaluate(ctx(20L));
        verify(engine).evaluate(ctx(30L));
        verify(engine, never()).evaluate(ctx(40L));
        assertThat(logs).extracting(RankLog::userId).containsExactly(20L, 30L);
        verifyNoInteractions(oldNetwork);
    }

    @Test
    void buyerRewardFailureRollsBackBuyerAndStillEvaluatesQualifiedParent() {
        eligible(10L);
        eligible(20L);
        when(mapper.listUplineChain(10L, 7)).thenReturn(List.of(upline(20L, 1)));
        doThrow(new IllegalStateException("reward transaction failed"))
                .when(rewards).dispatch(eq(10L), anyString(), any(), any());

        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"user_id\":10}"));

        assertThat(ranks).containsEntry(20L, "V1").doesNotContainKey(10L);
        assertThat(logs).containsExactly(new RankLog(20L, "V1", "CHECKOUT-1"));
        verify(transactions).rollback(any());
        verify(transactions).commit(any());
        verify(outbox, never()).publish(eq("vrank"), eq("10"), anyString(), anyMap());
    }

    @Test
    void oneAncestorTransactionFailureDoesNotBlockOtherAffectedAncestors() {
        eligible(20L);
        eligible(30L);
        when(mapper.listUplineChain(10L, 7)).thenReturn(List.of(upline(20L, 1), upline(30L, 2)));
        doThrow(new IllegalStateException("reward transaction failed"))
                .when(rewards).dispatch(eq(20L), anyString(), any(), any());

        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"user_id\":10}"));

        assertThat(ranks).containsEntry(30L, "V1").doesNotContainKey(20L);
        assertThat(logs).containsExactly(new RankLog(30L, "V1", "CHECKOUT-1"));
        verify(transactions).rollback(any());
        verify(transactions, times(2)).commit(any());
    }

    @Test
    void registerStillEvaluatesOnlyRegisteredUserWithOriginalEventTrace() {
        eligible(10L);
        eligible(20L);

        consumer.onPassiveEvalTrigger(event("auth.register_completed", "{\"userId\":10}"));

        assertThat(logs).containsExactly(new RankLog(10L, "V1", "CHECKOUT-1"));
        verify(engine).evaluate(ctx(10L));
        verifyNoInteractions(mapper, oldNetwork);
    }

    @Test
    void replayAtStableRankDoesNotDuplicatePromotionLogOrReward() {
        eligible(20L);
        when(mapper.listUplineChain(10L, 7)).thenReturn(List.of(upline(20L, 1)));
        EventOutboxMessage message = event("checkout.completed", "{\"user_id\":10}");

        consumer.onPassiveEvalTrigger(message);
        consumer.onPassiveEvalTrigger(message);

        assertThat(logs).containsExactly(new RankLog(20L, "V1", "CHECKOUT-1"));
        verify(rewards).dispatch(eq(20L), eq("V1"), any(), any());
        verify(outbox).publish(eq("vrank"), eq("20"), anyString(), anyMap());
        verifyNoInteractions(oldNetwork);
    }

    @Test
    void unsupportedOrInvalidPayloadsRetainExistingNoEvaluationBehavior() {
        consumer.onPassiveEvalTrigger(null);
        consumer.onPassiveEvalTrigger(event("commission.paid", "{\"user_id\":10}"));
        consumer.onPassiveEvalTrigger(event("checkout.completed", "{}"));
        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"user_id\":0}"));
        consumer.onPassiveEvalTrigger(event("checkout.completed", "not-json"));

        verifyNoInteractions(engine, mapper, oldNetwork, transactions);
        assertThat(logs).isEmpty();
    }

    @Test
    void uplineLookupFailureKeepsCompletedBuyerEvaluationAndExistingBackfillContract() {
        eligible(10L);
        when(mapper.listUplineChain(10L, 7)).thenThrow(new IllegalStateException("lookup failed"));

        consumer.onPassiveEvalTrigger(event("checkout.completed", "{\"user_id\":10}"));

        assertThat(logs).containsExactly(new RankLog(10L, "V1", "CHECKOUT-1"));
        verify(mapper).listUplineChain(10L, 7);
        verifyNoInteractions(oldNetwork);
    }

    private void eligible(long userId) {
        snapshots.put(userId, snapshot("1338.8", "3936.8", 3));
    }

    private VRankEvaluationSnapshot snapshot(String self, String team, int refs) {
        return new VRankEvaluationSnapshot(new BigDecimal(self), new BigDecimal(team), refs, Map.of());
    }

    private VRankConfigRow rank(String code, String self, int refs, String team, int order) {
        return new VRankConfigRow(code, new BigDecimal(self), refs, new BigDecimal(team), null, 0,
                order, null, BigDecimal.ZERO, 0);
    }

    private Map<String, Object> upline(long userId, int layer) {
        return Map.of("userId", userId, "layer", layer, "vRank", "V0");
    }

    private EventOutboxMessage event(String type, String payload) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventType(type);
        message.setEventId("CHECKOUT-1");
        message.setPayload(payload);
        return message;
    }

    private VRankPromotionContext ctx(long userId) {
        return new VRankPromotionContext(userId, VRankPromotionContext.TriggerType.SYSTEM_EVALUATION,
                "CHECKOUT-1", "ENGINE");
    }

    private record RankLog(Long userId, String toRank, String triggerEventId) {}

    /** Local fixture rollback only; this test does not claim MySQL transaction acceptance. */
    private class FixtureTransaction extends SimpleTransactionStatus {
        private final Map<Long, String> beforeRanks = new HashMap<>(ranks);
        private final List<RankLog> beforeLogs = new ArrayList<>(logs);
    }
}
