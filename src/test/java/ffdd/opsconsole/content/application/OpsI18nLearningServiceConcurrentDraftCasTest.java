package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OpsI18nLearningServiceConcurrentDraftCasTest {
    private static final String MESSAGE_KEY = "acceptance.i6.concurrent";

    @Test
    void twoTransactionsStartingFromV1AllowExactlyOneDraftAndOneVersionConflict() throws Exception {
        CountDownLatch transactionStart = new CountDownLatch(1);
        CountDownLatch transactionsReady = new CountDownLatch(2);
        CountDownLatch snapshotReadersReady = new CountDownLatch(2);
        AtomicInteger nextVersion = new AtomicInteger(1);
        AtomicInteger successfulMutations = new AtomicInteger();
        I18nMessagePairView v1 = pair("v1", "旧中文", "Old English", "Tiếng Việt cũ");

        I18nLearningRepository repository = (I18nLearningRepository) Proxy.newProxyInstance(
                I18nLearningRepository.class.getClassLoader(),
                new Class<?>[] {I18nLearningRepository.class},
                (proxy, method, args) -> {
                    if ("findMessagePairForUpdate".equals(method.getName())) {
                        snapshotReadersReady.countDown();
                        assertThat(snapshotReadersReady.await(5, TimeUnit.SECONDS)).isTrue();
                        return Optional.of(v1);
                    }
                    if ("saveMessagePair".equals(method.getName())) {
                        int version = nextVersion.incrementAndGet();
                        successfulMutations.incrementAndGet();
                        return pair(
                                "v" + version,
                                String.valueOf(args[1]),
                                String.valueOf(args[2]),
                                String.valueOf(args[3]));
                    }
                    if ("saveMessageDraftCas".equals(method.getName())) {
                        String expectedVersion = String.valueOf(args[4]);
                        if (!("v" + nextVersion.get()).equals(expectedVersion)
                                || !nextVersion.compareAndSet(1, 2)) {
                            throw new IllegalStateException("I18N_MESSAGE_VERSION_CONFLICT");
                        }
                        successfulMutations.incrementAndGet();
                        return pair(
                                "v2",
                                String.valueOf(args[1]),
                                String.valueOf(args[2]),
                                String.valueOf(args[3]));
                    }
                    if ("toString".equals(method.getName())) {
                        return "ConcurrentDraftCasRepository";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        AuditLogService auditLogService = mock(AuditLogService.class);
        EventOutboxService eventOutboxService = mock(EventOutboxService.class);
        AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
        when(lockMapper.countActiveByTarget(any(), any(), any())).thenReturn(0);
        TreasuryCoverageFacade coverageFacade =
                () -> new TreasuryCoverageSnapshot(new BigDecimal("128.4"), new BigDecimal("100"));
        OpsI18nLearningService service = new OpsI18nLearningService(
                repository,
                auditLogService,
                coverageFacade,
                Clock.fixed(Instant.parse("2026-07-29T09:00:00Z"), ZoneOffset.UTC),
                OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                lockMapper,
                eventOutboxService);
        CountingTransactionManager transactionManager = new CountingTransactionManager();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ApiResult<I18nMessagePairView>>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> inTransaction(
                    transactions, transactionsReady, transactionStart, service, "maker", "idem-maker")));
            futures.add(executor.submit(() -> inTransaction(
                    transactions, transactionsReady, transactionStart, service, "checker", "idem-checker")));

            assertThat(transactionsReady.await(5, TimeUnit.SECONDS)).isTrue();
            transactionStart.countDown();

            List<ApiResult<I18nMessagePairView>> results = List.of(
                    futures.get(0).get(5, TimeUnit.SECONDS),
                    futures.get(1).get(5, TimeUnit.SECONDS));

            assertThat(results).extracting(ApiResult::getCode).containsExactlyInAnyOrder(0, 409);
            assertThat(results.stream().filter(result -> result.getCode() == 409).findFirst().orElseThrow().getMessage())
                    .isEqualTo("I18N_MESSAGE_VERSION_CONFLICT");
            assertThat(results.stream().filter(result -> result.getCode() == 0).findFirst().orElseThrow().getData().version())
                    .isEqualTo("v2");
            assertThat(successfulMutations).hasValue(1);
            assertThat(transactionManager.begins).hasValue(2);
            assertThat(transactionManager.commits).hasValue(2);
            assertThat(transactionManager.rollbacks).hasValue(0);
            verify(auditLogService, times(1)).recordRequired(any(AuditLogWriteRequest.class));
            verify(eventOutboxService, never()).publish(any(), any(), any(), any());
        } finally {
            transactionStart.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ApiResult<I18nMessagePairView> inTransaction(
            TransactionTemplate transactions,
            CountDownLatch transactionsReady,
            CountDownLatch transactionStart,
            OpsI18nLearningService service,
            String operator,
            String idempotencyKey) {
        return transactions.execute(status -> {
            transactionsReady.countDown();
            try {
                if (!transactionStart.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("concurrent transaction start timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("concurrent transaction start interrupted", ex);
            }
            return service.saveLocalizedDraft(
                    MESSAGE_KEY,
                    idempotencyKey,
                    new I18nLocalizedCopyRequest(
                            "新中文",
                            "New English",
                            "Tiếng Việt mới",
                            "v1",
                            operator,
                            "验证两个独立运营员不能从同一版本同时保存草稿"));
        });
    }

    private static I18nMessagePairView pair(String version, String zh, String en, String vi) {
        return new I18nMessagePairView(
                MESSAGE_KEY,
                "acceptance",
                en,
                zh,
                vi,
                "draft",
                version,
                List.of());
    }

    private static final class CountingTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicInteger begins = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }
}
