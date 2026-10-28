package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Action;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade.Before;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Simulates JDBC/mapper readback, including Spring proxy rollback; no real MySQL or funds. */
class SupportFundJournalBindingServiceTest {
    private static final String INC = "12345678-1234-1234-1234-123456789abc";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 11, 0, 0);
    private final FakeMapper mapper = new FakeMapper();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final TestHolder holder = new TestHolder(connection);
    private final SupportFundJournalBindingService service = new SupportFundJournalBindingService(mapper, dataSource, new ObjectMapper());

    @BeforeEach void transaction() throws Exception {
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        mapper.wallets.put(7L, wallet(7, INC));
    }
    @AfterEach void clear() {
        if (TransactionSynchronizationManager.hasResource(dataSource)) TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.clear();
    }

    @Test void mandatoryEntryPointsAndExplicitPositiveScope() throws Exception {
        assertThat(service.getClass().getMethod("begin", java.util.Collection.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(service.getClass().getMethod("bind", Before.class, ReceiptInput.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(service.getClass().getMethod("beginBirth", long.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        for (List<Long> scope : List.of(List.<Long>of(), List.of(0L), List.of(-1L)))
            assertThatThrownBy(() -> service.begin(scope)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.begin(null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void locksCustomersAscendingAndDeduplicatesScope() {
        mapper.wallets.put(8L, wallet(8, "22345678-1234-1234-1234-123456789abc"));
        Before token = service.begin(List.of(8L, 7L, 8L));
        assertThat(mapper.locks).containsExactly(7L, 8L);
        var receipt = service.bind(token, input("[]"));
        assertThat(mapper.parties).extracting(r -> r.get("customerId")).containsExactly(7L, 8L);
        assertThat(mapper.customerOperations(7)).containsExactly(receipt.operationId());
        assertThat(mapper.customerOperations(8)).containsExactly(receipt.operationId());
    }
    @Test void rejectsNoTransactionReadOnlyOrUnboundDatasource() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("WRITABLE_FUND_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("WRITABLE_FUND_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.unbindResource(dataSource);
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("PHYSICAL_FUND_TRANSACTION_REQUIRED");
    }
    @Test void rejectsArbitraryOrUnsynchronizedResource() {
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource, new Object());
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("PHYSICAL_FUND_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        holder.setSynchronizedWithTransaction(false);
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("PHYSICAL_FUND_TRANSACTION_REQUIRED");
    }
    @Test void rejectsAutocommitReadonlyClosedOrFailedConnection() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("PHYSICAL_WRITABLE_FUND_TRANSACTION_REQUIRED");
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(true);
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("PHYSICAL_WRITABLE_FUND_TRANSACTION_REQUIRED");
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.isClosed()).thenReturn(true);
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("PHYSICAL_WRITABLE_FUND_TRANSACTION_REQUIRED");
        when(connection.isClosed()).thenThrow(new java.sql.SQLException("offline"));
        assertThatThrownBy(() -> service.begin(List.of(7L))).hasMessage("FUND_TRANSACTION_CONNECTION_FAILED");
    }
    @Test void rejectsForgedForeignOwnerResourceOrCompletedTokens() {
        assertThatThrownBy(() -> service.bind(new Before() {}, input("[]"))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
        Before token = service.begin(List.of(7L));
        var other = new SupportFundJournalBindingService(mapper, dataSource, new ObjectMapper());
        assertThatThrownBy(() -> other.bind(token, input("[]"))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
        TransactionSynchronizationManager.unbindResource(dataSource);
        ConnectionHolder replacement = new ConnectionHolder(connection); replacement.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(dataSource, replacement);
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
        TransactionSynchronizationManager.unbindResource(dataSource); TransactionSynchronizationManager.bindResource(dataSource, holder);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
    }
    @Test void sameHolderWithDifferentPhysicalConnectionAndRollbackCompletionInvalidateToken() {
        Before token = service.begin(List.of(7L));
        holder.replace(mock(Connection.class));
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
        holder.replace(connection);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void tokenCannotBeUsedFromAnotherThreadEvenWithForgedResourceIdentity() throws Exception {
        Before token = service.begin(List.of(7L));
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.bindResource(dataSource, holder);
            try { service.bind(token, input("[]")); }
            catch (Throwable ex) { error.set(ex); }
            finally { TransactionSynchronizationManager.unbindResource(dataSource); TransactionSynchronizationManager.clear(); }
        });
        thread.start(); thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(error.get()).isInstanceOf(IllegalStateException.class).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
        assertThat(service.bind(token, input("[]")).journalLegs()).isZero();
    }
    @Test void allAffectedCustomersBindTheirOwnCompleteRevisionChains() {
        String other = "22345678-1234-1234-1234-123456789abc";
        mapper.wallets.put(8L, wallet(8, other));
        Before token = service.begin(List.of(8L, 7L));
        change(7, "usdtAvailable", new BigDecimal("3")); change(8, "nexAvailable", new BigDecimal("4"));
        assertThat(service.bind(token, input("[]")).journalLegs()).isEqualTo(2);
        assertThat(mapper.legs).extracting(r -> r.get("incarnation")).containsExactly(INC, other);
        assertThat(mapper.locks).containsExactly(7L, 8L, 7L, 8L);
    }
    @Test void mysqlTinyintBooleanAndTimestampMappingsPreserveFullTuple() {
        mapper.wallets.get(7L).put("sandbox", false); mapper.wallets.get(7L).put("isDeleted", false);
        mapper.wallets.get(7L).put("createdAt", java.sql.Timestamp.valueOf(AT));
        assertThat(service.bind(service.begin(List.of(7L)), input("[]")).journalLegs()).isZero();
    }
    @Test void bindsEveryRevisionAndReadsBackEveryLeg() {
        Before token = service.begin(List.of(7L));
        change(7, "usdtAvailable", new BigDecimal("13.250000"));
        change(7, "nexAvailable", new BigDecimal("2.500000"));
        var receipt = service.bind(token, input(deposit()));
        assertThat(receipt.operationId()).isEqualTo(101);
        assertThat(receipt.journalLegs()).isEqualTo(2);
        assertThat(receipt.receiptSha256()).matches("[a-f0-9]{64}");
        assertThat(mapper.legs).extracting(r -> r.get("revision")).containsExactly(1L, 2L);
        assertThat(mapper.journalQueries).containsExactly(INC + ":0:2");
        assertThat(mapper.operationReads).isEqualTo(2);
        assertThatThrownBy(() -> service.bind(token, input(deposit()))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
    }
    @Test void noCashPhaseCreatesReceiptWithNoLegWithoutClaimingBirth() {
        var receipt = service.bind(service.begin(List.of(7L)), input("[]"));
        assertThat(receipt.journalLegs()).isZero();
        assertThat(mapper.operation).containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat(mapper.journalQueries).containsExactly(INC + ":0:0");
        assertThat(mapper.operation).doesNotContainKeys("ready", "birth", "known");
        assertThat(mapper.parties).containsExactly(Map.of("operationId", receipt.operationId(), "customerId", 7L));
        assertThat(mapper.customerOperations(7)).containsExactly(receipt.operationId());
    }
    @Test void registrationCandidateBindsZeroRevisionAndSubsequentChanges() {
        birth();
        Before token = service.beginBirth(7);
        change(7, "nexAvailable", new BigDecimal("4"));
        var receipt = service.bind(token, input("[]"));
        assertThat(receipt.journalLegs()).isEqualTo(2);
        assertThat(mapper.legs).extracting(r -> r.get("revision")).containsExactly(0L, 1L);
        assertThat(mapper.operation).doesNotContainKeys("birthProven", "ready", "known");
    }
    @Test void ordinaryOperationNeverRetroactivelyBindsExistingZeroBirth() {
        birth();
        var receipt = service.bind(service.begin(List.of(7L)), input("[]"));
        assertThat(receipt.journalLegs()).isZero();
        assertThat(mapper.legs).isEmpty();
    }
    @Test void registrationCandidateRejectsMissingOldNonzeroOrUnprovenBirth() {
        assertThatThrownBy(() -> service.beginBirth(7)).hasMessage("AMBIGUOUS_WALLET_BIRTH_CANDIDATE");
        birth(); mapper.journal.get(0).put("observation", "NEW_UNPROVEN");
        assertThatThrownBy(() -> service.beginBirth(7)).hasMessage("INVALID_WALLET_BIRTH_CANDIDATE");
        mapper.journal.get(0).put("observation", "NEW_ZERO"); mapper.journal.get(0).put("oldId", 107L);
        assertThatThrownBy(() -> service.beginBirth(7)).hasMessage("INVALID_WALLET_BIRTH_CANDIDATE");
        mapper.journal.get(0).remove("oldId"); mapper.wallets.get(7L).put("version", 1L);
        assertThatThrownBy(() -> service.beginBirth(7)).hasMessage("INVALID_ZERO_WALLET_CANDIDATE");
        mapper.wallets.get(7L).put("version", 0L); mapper.wallets.get(7L).put("usdtAvailable", BigDecimal.ONE);
        assertThatThrownBy(() -> service.beginBirth(7)).hasMessage("INVALID_ZERO_WALLET_CANDIDATE");
    }
    @Test void registrationCandidateRejectsPredecessorAndRechecksRowZeroAtBind() {
        birth();
        Map<String,Object> predecessor = new HashMap<>(mapper.journal.get(0));
        predecessor.put("incarnation", "22345678-1234-1234-1234-123456789abc"); mapper.journal.add(predecessor);
        assertThatThrownBy(() -> service.beginBirth(7)).hasMessage("AMBIGUOUS_WALLET_BIRTH_CANDIDATE");
        mapper.journal.remove(1); Before token = service.beginBirth(7);
        mapper.journal.get(0).put("newCregisRiskHeld", BigDecimal.ONE);
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("INVALID_WALLET_BIRTH_CANDIDATE");
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void identicalNoCashReceiptReplayDoesNotInsertAgain() {
        var first = service.bind(service.begin(List.of(7L)), input("[]"));
        var replay = service.bind(service.begin(List.of(7L)), input("[]"));
        assertThat(replay).isEqualTo(first);
        assertThat(mapper.operationInserts).isEqualTo(1);
        assertThat(mapper.operationCalls).containsExactly("INSERT", "READ", "READ", "INSERT", "READ", "READ");
    }
    @Test void insertsBeforeAnyOperationLockingRead() {
        service.bind(service.begin(List.of(7L)), input("[]"));
        assertThat(mapper.operationCalls).containsExactly("INSERT", "READ", "READ");
    }
    @Test void duplicateWithoutExistingReceiptRethrowsAndOtherIntegrityErrorsNeverRead() {
        var duplicate = new DuplicateKeyException("missing conflicting row");
        mapper.insertFailure = duplicate;
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]"))).isSameAs(duplicate);
        assertThat(mapper.operationCalls).containsExactly("INSERT", "READ");
        mapper.operationCalls.clear();
        var integrity = new DataIntegrityViolationException("bad receipt");
        mapper.insertFailure = integrity;
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]"))).isSameAs(integrity);
        assertThat(mapper.operationCalls).containsExactly("INSERT");
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void duplicateChecksEveryReceiptFieldIncludingCanonicalJsonAndHash() {
        service.bind(service.begin(List.of(7L)), input("[]"));
        for (String field : List.of("operationKey", "businessType", "phase", "sourceTable", "sourceKey",
                "sourceEnvironment", "orderedEventsJson", "sourceSnapshotJson", "sourceProofJson", "protocolVersion", "receiptSha256")) {
            Object original = mapper.operation.get(field);
            mapper.operation.put(field, field.equals("orderedEventsJson") ? deposit() : field.endsWith("Json") ? "{\"changed\":1}" : "changed");
            assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]")))
                .as(field).hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
            mapper.operation.put(field, original);
        }
        assertThat(mapper.operationInserts).isEqualTo(1);
        assertThat(mapper.legs).isEmpty();
    }
    @Test void duplicateReceiptWithJournalLegsChecksEachIdentityAndDoesNotReinsert() {
        Before firstToken = service.begin(List.of(7L)); change(7, "usdtAvailable", new BigDecimal("1"));
        var first = service.bind(firstToken, input(deposit()));
        Map<String,Object> after = new HashMap<>(mapper.wallets.get(7L));
        mapper.wallets.put(7L, wallet(7, INC)); Before replayToken = service.begin(List.of(7L)); mapper.wallets.put(7L, after);
        assertThat(service.bind(replayToken, input(deposit()))).isEqualTo(first);
        assertThat(mapper.operationInserts).isEqualTo(1); assertThat(mapper.legs).hasSize(1);
        Map<String,Object> original = mapper.legs.get(0);
        for (String field : List.of("operationId", "incarnation", "revision")) {
            Map<String,Object> bad = new HashMap<>(original);
            bad.put(field, field.equals("incarnation") ? "22345678-1234-1234-1234-123456789abc" : 999L);
            mapper.legs.set(0, bad);
            mapper.wallets.put(7L, wallet(7, INC)); Before token = service.begin(List.of(7L)); mapper.wallets.put(7L, after);
            assertThatThrownBy(() -> service.bind(token, input(deposit()))).as(field).hasMessage("FUND_OPERATION_LEG_MISMATCH");
        }
        assertThat(mapper.operationInserts).isEqualTo(1); assertThat(mapper.legs).hasSize(1);
    }
    @Test void duplicateReadbackCannotIncludeExtraOutOfScopeLeg() {
        service.bind(service.begin(List.of(7L)), input("[]"));
        mapper.legs.add(Map.of("operationId", 101L, "incarnation", "22345678-1234-1234-1234-123456789abc", "revision", 1L));
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]"))).hasMessage("FUND_OPERATION_LEG_MISMATCH");
        assertThat(mapper.operationInserts).isEqualTo(1);
    }
    @Test void replayCannotTakeAnotherRevisionOrChangeReceipt() {
        service.bind(service.begin(List.of(7L)), input("[]"));
        Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", new BigDecimal("3"));
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
        assertThat(mapper.legs).isEmpty();
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input(deposit()))).hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
    }
    @Test void replayCannotChangeExplicitCustomerScope() {
        service.bind(service.begin(List.of(7L)), input("[]"));
        mapper.wallets.put(8L, wallet(8, "22345678-1234-1234-1234-123456789abc"));
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L, 8L)), input("[]"))).hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
        assertThatThrownBy(() -> service.bind(service.begin(List.of(8L)), input("[]"))).hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
    }
    @Test void replayRequiresAllAndOnlyOriginalPartiesIncludingLegacyReceiptWithNone() {
        service.bind(service.begin(List.of(7L)), input("[]"));
        for (List<Map<String,Object>> bad : List.of(List.<Map<String,Object>>of(),
                List.of(Map.<String,Object>of("operationId", 101L, "customerId", 8L)),
                List.of(Map.<String,Object>of("operationId", 999L, "customerId", 7L)),
                List.of(Map.<String,Object>of("operationId", 101L, "customerId", 7L), Map.<String,Object>of("operationId", 101L, "customerId", 8L)))) {
            mapper.parties.clear(); mapper.parties.addAll(bad);
            assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]"))).hasMessage("FUND_OPERATION_PARTY_MISMATCH");
            assertThat(mapper.parties).containsExactlyElementsOf(bad);
        }
        assertThat(mapper.operationInserts).isEqualTo(1);
        assertThat(mapper.partyInserts).isEqualTo(1);
    }
    @Test void partyReadbackOrderNullOrMissingFieldNeverCertifiesReplay() {
        mapper.wallets.put(8L, wallet(8, "22345678-1234-1234-1234-123456789abc"));
        service.bind(service.begin(List.of(8L, 7L)), input("[]"));
        java.util.Collections.reverse(mapper.parties);
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L, 8L)), input("[]"))).hasMessage("FUND_OPERATION_PARTY_MISMATCH");
        java.util.Collections.reverse(mapper.parties);
        mapper.nullParties = true;
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L, 8L)), input("[]"))).hasMessage("FUND_OPERATION_PARTY_MISMATCH");
        mapper.nullParties = false; mapper.parties.set(0, Map.of("operationId", 101L));
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L, 8L)), input("[]"))).hasMessage("INVALID_FUND_ROW:customerId");
    }
    @Test void partyInsertCountAndConflictAreNeverIgnoredOrFollowedByLegWrites() {
        Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", BigDecimal.ONE);
        mapper.failParty = true;
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("FUND_OPERATION_PARTY_INSERT_FAILED");
        assertThat(mapper.legs).isEmpty();
        mapper.operation = null; mapper.failParty = false;
        RuntimeException conflict = new DuplicateKeyException("party collision"); mapper.partyFailure = conflict;
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]"))).isSameAs(conflict);
        assertThat(mapper.legs).isEmpty();
    }
    @Test void partyFailureCaughtOutsideSpringProxyStillRollsBack() throws Exception {
        clear(); when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true, false, false, false);
        var manager = new DataSourceTransactionManager(dataSource);
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var proxy = (SupportFundJournalFacade) factory.getProxy();
        mapper.partyFailure = new DuplicateKeyException("party collision");
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            Before token = proxy.begin(List.of(7L)); change(7, "usdtAvailable", BigDecimal.ONE);
            assertThatThrownBy(() -> proxy.bind(token, input("[]"))).isSameAs(mapper.partyFailure);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void semanticJsonOrderingAndNumericFormattingHaveStableReceiptHash() {
        var first = service.bind(service.begin(List.of(7L)), input("[]"));
        ReceiptInput reordered = new ReceiptInput("op-1", "WITHDRAW", "SUCCESS", "nx_withdraw_order", "w-1", "PRODUCTION", "[]", "{\"b\":2.000,\"a\":1}", "{\"source\":\"w-1\"}");
        var second = service.bind(service.begin(List.of(7L)), reordered);
        assertThat(second).isEqualTo(first);
    }
    @Test void missingMiddleFirstOrLastRevisionIsRejectedBeforeInsert() {
        for (int missing : List.of(0, 1, 2)) {
            mapper.wallets.put(7L, wallet(7, INC)); mapper.journal.clear();
            Before token = service.begin(List.of(7L));
            change(7, "usdtAvailable", new BigDecimal("1")); change(7, "nexAvailable", new BigDecimal("2")); change(7, "pendingWithdraw", new BigDecimal("3"));
            mapper.journal.remove(missing);
            assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("FUND_JOURNAL_GAP");
        }
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void corruptJournalRevisionObservationProtocolIncarnationOrRecordedTimeFails() {
        for (String key : List.of("revision", "observation", "protocolSha256", "incarnation", "recordedDbUtc")) {
            mapper.wallets.put(7L, wallet(7, INC)); mapper.journal.clear();
            Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", new BigDecimal("1"));
            mapper.journal.get(0).put(key, key.equals("revision") ? 2L : "bad");
            assertThatThrownBy(() -> service.bind(token, input("[]"))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void checksEveryOldAndNewTupleFieldIncludingBothCurrenciesRiskAndVersion() {
        for (String field : List.of("id", "userId", "usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld", "version", "sandbox", "isDeleted", "createdAt")) {
            for (String side : List.of("old", "new")) {
                mapper.wallets.put(7L, wallet(7, INC)); mapper.journal.clear();
                Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", new BigDecimal("1"));
                String key = side + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                Object old = mapper.journal.get(0).get(key);
                mapper.journal.get(0).put(key, old instanceof BigDecimal b ? b.add(BigDecimal.ONE) : old instanceof LocalDateTime t ? t.plusSeconds(1) : ((Number) old).longValue() + 1);
                assertThatThrownBy(() -> service.bind(token, input("[]"))).isInstanceOf(IllegalStateException.class);
            }
        }
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void adjacentMiddleTupleAndUnjournaledTailMismatchAreRejected() {
        Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", new BigDecimal("1")); change(7, "usdtAvailable", new BigDecimal("2"));
        mapper.journal.get(1).put("oldUsdtAvailable", new BigDecimal("9"));
        Before brokenAdjacent = token;
        assertThatThrownBy(() -> service.bind(brokenAdjacent, input("[]"))).hasMessage("FUND_JOURNAL_TUPLE_MISMATCH");
        mapper.journal.clear(); mapper.wallets.put(7L, wallet(7, INC)); token = service.begin(List.of(7L));
        mapper.wallets.get(7L).put("cregisRiskHeld", new BigDecimal("4"));
        Before unjournaled = token;
        assertThatThrownBy(() -> service.bind(unjournaled, input("[]"))).hasMessage("FUND_JOURNAL_TAIL_MISMATCH");
    }
    @Test void currentWalletMissingRebornOrSandboxChangedFails() {
        Before token = service.begin(List.of(7L)); mapper.wallets.remove(7L);
        Before missing = token;
        assertThatThrownBy(() -> service.bind(missing, input("[]"))).hasMessage("MISSING_OR_AMBIGUOUS_WALLET");
        mapper.wallets.put(7L, wallet(7, INC)); token = service.begin(List.of(7L));
        mapper.wallets.get(7L).put("incarnation", "22345678-1234-1234-1234-123456789abc");
        Before reborn = token; assertThatThrownBy(() -> service.bind(reborn, input("[]"))).hasMessage("WALLET_IDENTITY_CHANGED");
        mapper.wallets.put(7L, wallet(7, INC)); token = service.begin(List.of(7L)); mapper.wallets.get(7L).put("sandbox", 1);
        Before sandbox = token; assertThatThrownBy(() -> service.bind(sandbox, input("[]"))).hasMessage("INVALID_PRODUCTION_WALLET");
    }
    @Test void strictJsonRejectsDuplicateKeysTrailingValuesUnknownEventFieldsAndCoercion() {
        for (String events : List.of("[{\"id\":\"a\",\"id\":\"b\"}]", "[] {}", "{}", "null", "[null]",
            deposit().replace("\"amount\":1", "\"amount\":1,\"secret\":1"),
            deposit().replace("\"amount\":1", "\"amount\":\"1\""),
            deposit().replace("\"customerId\":7", "\"customerId\":7.5"),
            deposit().replace("\"customerId\":7", "\"customerId\":7.0000000000000000001"),
            deposit().replace("\"customerId\":7", "\"customerId\":9223372036854775808.0"),
            deposit().replace("\"customerId\":7", "\"customerId\":\"7\""),
            deposit().replace("\"customerId\":7", "\"customerId\":null"))) {
            assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input(events))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mapper.operationInserts).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"from","to"})
    void mysqlIntegralCustomerNumbersKeepOriginalReceiptSignature(String endpoint) throws Exception {
        mapper.wallets.put(6270L, wallet(6270, INC));
        String events=deposit().replace("\"customerId\":7", "\"customerId\":6270");
        if("from".equals(endpoint))events=events.replace("\"to\":", "\"from\":").replace("\"DEPOSIT\"", "\"CONSUME\"");
        service.bind(service.begin(List.of(6270L)), input(events));
        var original=Map.copyOf(mapper.operation);
        String encoded=(String)original.get("orderedEventsJson");
        assertThat(encoded).contains("6.27E+3");
        for(String representation:List.of("6270.0","6.270E+3")) {
            var stored=new HashMap<>(original);
            stored.put("orderedEventsJson",encoded.replace("6.27E+3",representation));
            var decoded=service.decodeReceipt(stored,List.of(6270L),mapper.legs);
            assertThat(decoded).singleElement().satisfies(event->
                assertThat("from".equals(endpoint)?event.from().customerId():event.to().customerId()).isEqualTo(6270L));
            assertThat(stored.get("receiptSha256")).isEqualTo(original.get("receiptSha256"));
            assertThat(service.canonical(service.read((String)stored.get("orderedEventsJson"))).toString()).isEqualTo(encoded);
        }
        assertThat(mapper.operation).isEqualTo(original);
    }
    @Test void strictActionsRejectNumericQuotedNumericUnknownAndNonExactNamesEvenWithTolerantParentMapper() {
        var tolerant = new SupportFundJournalBindingService(mapper, dataSource,
            new ObjectMapper().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS));
        for (String action : List.of("0", "1", "12", "-1", "0.0", "\"0\"", "\"12\"", "\"UNKNOWN\"",
                "\"deposit\"", "\" DEPOSIT \"", "null", "true", "{}", "[]")) {
            String events = deposit().replace("\"DEPOSIT\"", action);
            assertThatThrownBy(() -> tolerant.bind(tolerant.begin(List.of(7L)), input(events)))
                .as(action).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void allNamedActionsWithTheirValidShapesRemainAccepted() {
        mapper.wallets.put(8L, wallet(8, "22345678-1234-1234-1234-123456789abc"));
        String from = "\"from\":{\"customerId\":7,\"currency\":\"USDT\",\"slot\":\"available\"}";
        for (Action action : Action.values()) {
            String shape = switch (action) {
                case DEPOSIT, EARNINGS, PROTECTED_EARNINGS -> "\"to\":{\"customerId\":7,\"currency\":\"USDT\",\"slot\":\"available\"},\"amount\":1";
                case CONSUME, FEE, RESERVE -> from + ",\"amount\":1";
                case REFUND, REVOKE_DEPOSIT, RESTORE_DEPOSIT -> "\"reference\":\"original\",\"amount\":1";
                case REORG_REFUND -> "\"to\":{\"customerId\":7,\"currency\":\"USDT\",\"slot\":\"available\"},\"reference\":\"original\",\"amount\":1";
                case CONFIRM, RETURN -> "\"reference\":\"original\"";
                case RELEASE -> from + ",\"reference\":\"original\"";
                case RECOVER, CLAWBACK, REORG_DEBIT -> from + ",\"reference\":\"original\",\"amount\":1";
                case MOVE -> from + ",\"to\":{\"customerId\":7,\"currency\":\"USDT\",\"slot\":\"held:move\"},\"amount\":1";
                case EXCHANGE -> from + ",\"to\":{\"customerId\":7,\"currency\":\"UVT\",\"slot\":\"available\"},\"amount\":1,\"targetAmount\":2";
                case TRADE -> from + ",\"to\":{\"customerId\":8,\"currency\":\"USDT\",\"slot\":\"available\"},\"amount\":1,\"targetAmount\":1";
            };
            mapper.operation = null; mapper.parties.clear();
            String events = "[{\"id\":\"valid-" + action + "\",\"action\":\"" + action + "\"," + shape + "}]";
            assertThat(service.bind(service.begin(List.of(7L, 8L)), input(events)).journalLegs()).as(action.name()).isZero();
            assertThat((String) mapper.operation.get("orderedEventsJson")).as(action.name()).contains("\"action\":\"" + action + "\"");
        }
        assertThat(mapper.operationInserts).isEqualTo(Action.values().length);
    }
    @Test void everyCashBucketAndVersionMustBeNonnegativeAtBeginAndBindWithoutCorrection() {
        for (String field : List.of("usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld", "version")) {
            mapper.wallets.put(7L, wallet(7, INC));
            Object negative = field.equals("version") ? -1L : new BigDecimal("-0.000001");
            mapper.wallets.get(7L).put(field, negative);
            assertThatThrownBy(() -> service.begin(List.of(7L))).as(field).hasMessage("INVALID_FUND_ROW:" + field);
            assertThat(mapper.wallets.get(7L).get(field)).isEqualTo(negative);
            mapper.wallets.put(7L, wallet(7, INC)); Before token = service.begin(List.of(7L));
            mapper.wallets.get(7L).put(field, negative);
            assertThatThrownBy(() -> service.bind(token, input("[]"))).as(field).hasMessage("INVALID_FUND_ROW:" + field);
            assertThat(mapper.wallets.get(7L).get(field)).isEqualTo(negative);
        }
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void everyOldAndNewJournalCashBucketAndVersionRejectsNegativeValues() {
        for (String side : List.of("old", "new")) {
            for (String field : List.of("usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld", "version")) {
                mapper.wallets.put(7L, wallet(7, INC)); mapper.journal.clear();
                Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", BigDecimal.ONE);
                String key = side + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                mapper.journal.get(0).put(key, field.equals("version") ? -1L : new BigDecimal("-0.000001"));
                assertThatThrownBy(() -> service.bind(token, input("[]"))).as(key).hasMessage("INVALID_FUND_ROW:" + key);
            }
        }
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void zeroAndMaximumValidDecimalBucketsAndVersionRemainAccepted() {
        for (String field : List.of("usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld"))
            mapper.wallets.get(7L).put(field, new BigDecimal("999999999999.999999"));
        mapper.wallets.get(7L).put("version", Long.MAX_VALUE);
        assertThat(service.bind(service.begin(List.of(7L)), input("[]")).journalLegs()).isZero();
    }
    @Test void failureThroughSpringProxyMarksOuterTransactionRollbackOnlyEvenWhenCaught() throws Exception {
        clear(); when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true, false, false, false);
        var manager = new DataSourceTransactionManager(dataSource);
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var proxy = (SupportFundJournalFacade) factory.getProxy();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            Before token = proxy.begin(List.of(7L));
            assertThatThrownBy(() -> proxy.bind(token, input("bad-json"))).isInstanceOf(IllegalArgumentException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void eventContractRejectsInvalidMoneyCurrencySlotDuplicateAndOutOfScope() {
        for (String events : List.of(deposit().replace("\"amount\":1", "\"amount\":0"), deposit().replace("\"amount\":1", "\"amount\":1.0000001"),
            deposit().replace("USDT", "USD"), deposit().replace("available", "unknown"), deposit().replace("\"customerId\":7", "\"customerId\":8"),
            "[" + deposit().substring(1, deposit().length()-1) + "," + deposit().substring(1, deposit().length()-1) + "]",
            deposit().replace("DEPOSIT", "CONSUME")))
            assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input(events))).isInstanceOf(IllegalArgumentException.class);
        assertThat(mapper.operationInserts).isZero();
    }
    @Test void snapshotProofRequireNonemptyStrictObjectsAndProductionIdentity() {
        for (String bad : List.of("{}", "[]", "null", "{\"a\":1,\"a\":2}", "{\"a\":1}{}")) {
            var base = input("[]");
            assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), new ReceiptInput(base.operationKey(), base.businessType(), base.phase(), base.sourceTable(), base.sourceKey(), base.sourceEnvironment(), "[]", bad, base.sourceProofJson()))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), new ReceiptInput(base.operationKey(), base.businessType(), base.phase(), base.sourceTable(), base.sourceKey(), base.sourceEnvironment(), "[]", base.sourceSnapshotJson(), bad))).isInstanceOf(IllegalArgumentException.class);
        }
        var base = input("[]");
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), new ReceiptInput(" op ", "WITHDRAW", "SUCCESS", "nx_withdraw_order", "w-1", "PRODUCTION", "[]", base.sourceSnapshotJson(), base.sourceProofJson()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), new ReceiptInput("op", "WITHDRAW", "SUCCESS", "nx_withdraw_order", "w-1", "UNKNOWN", "[]", base.sourceSnapshotJson(), base.sourceProofJson()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectedInputConsumesTokenRatherThanReusingSameBeforeObservation() {
        Before token = service.begin(List.of(7L));
        assertThatThrownBy(() -> service.bind(token, input("bad"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("INVALID_FUND_TRANSACTION_TOKEN");
    }
    @Test void corruptReceiptReadbackAndLegReadbackFailWithoutSilentSuccess() {
        mapper.corruptReceipt = true;
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]"))).hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
        mapper.corruptReceipt = false; mapper.operation = null; mapper.parties.clear(); mapper.corruptLeg = true;
        Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", new BigDecimal("1"));
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("FUND_OPERATION_LEG_MISMATCH");
    }
    @Test void conflictingRevisionOwnerOrOperationInsertIsNeverIgnored() {
        mapper.failInsert = true;
        assertThatThrownBy(() -> service.bind(service.begin(List.of(7L)), input("[]"))).hasMessage("FUND_OPERATION_INSERT_FAILED");
        mapper.failInsert = false; mapper.failLeg = true;
        Before token = service.begin(List.of(7L)); change(7, "usdtAvailable", new BigDecimal("1"));
        assertThatThrownBy(() -> service.bind(token, input("[]"))).hasMessage("FUND_OPERATION_LEG_INSERT_FAILED");
    }

    private static ReceiptInput input(String events) {
        return new ReceiptInput("op-1", "WITHDRAW", "SUCCESS", "nx_withdraw_order", "w-1", "PRODUCTION", events,
            "{\"a\":1.0,\"b\":2}", "{\"source\":\"w-1\"}");
    }
    private static String deposit() { return "[{\"id\":\"deposit-1\",\"action\":\"DEPOSIT\",\"to\":{\"customerId\":7,\"currency\":\"USDT\",\"slot\":\"available\"},\"amount\":1}]"; }
    private static Map<String,Object> wallet(long customer, String incarnation) {
        Map<String,Object> row = new HashMap<>();
        row.put("id", customer + 100); row.put("userId", customer); row.put("incarnation", incarnation); row.put("revision", 0L);
        for (String field : List.of("usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld")) row.put(field, BigDecimal.ZERO.setScale(6));
        row.put("version", 0L); row.put("sandbox", 0); row.put("isDeleted", 0); row.put("createdAt", AT);
        return row;
    }
    private void birth() {
        Map<String,Object> journal = new HashMap<>();
        Map<String,Object> row = mapper.wallets.get(7L);
        for (String name : List.of("id", "userId", "usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld", "version", "sandbox", "isDeleted", "createdAt"))
            journal.put("new" + Character.toUpperCase(name.charAt(0)) + name.substring(1), row.get(name));
        journal.put("id", 1L); journal.put("incarnation", INC); journal.put("revision", 0L); journal.put("observation", "NEW_ZERO"); journal.put("recordedDbUtc", AT);
        try { journal.put("protocolSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("SUPPORT_WALLET_JOURNAL_V1".getBytes(StandardCharsets.UTF_8)))); }
        catch (Exception ex) { throw new AssertionError(ex); }
        mapper.journal.add(journal);
    }
    private void change(long customer, String field, Object value) {
        Map<String,Object> row = mapper.wallets.get(customer);
        Map<String,Object> journal = new HashMap<>();
        for (String name : List.of("id", "userId", "usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld", "version", "sandbox", "isDeleted", "createdAt"))
            journal.put("old" + Character.toUpperCase(name.charAt(0)) + name.substring(1), row.get(name));
        row.put(field, value); row.put("revision", ((Number) row.get("revision")).longValue() + 1);
        row.put("version", ((Number) row.get("version")).longValue() + 1);
        for (String name : List.of("id", "userId", "usdtAvailable", "nexAvailable", "pendingWithdraw", "cregisRiskHeld", "version", "sandbox", "isDeleted", "createdAt"))
            journal.put("new" + Character.toUpperCase(name.charAt(0)) + name.substring(1), row.get(name));
        journal.put("id", (long) mapper.journal.size() + 1); journal.put("incarnation", row.get("incarnation")); journal.put("revision", row.get("revision"));
        journal.put("observation", "CHANGE"); journal.put("recordedDbUtc", AT);
        try { journal.put("protocolSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("SUPPORT_WALLET_JOURNAL_V1".getBytes(StandardCharsets.UTF_8)))); }
        catch (Exception ex) { throw new AssertionError(ex); }
        mapper.journal.add(journal);
    }
    private static final class TestHolder extends ConnectionHolder {
        TestHolder(Connection connection) { super(connection); }
        void replace(Connection connection) { setConnection(connection); }
    }
    private static final class FakeMapper implements SupportFundJournalMapper {
        final Map<Long,Map<String,Object>> wallets = new HashMap<>();
        final List<Map<String,Object>> journal = new ArrayList<>(), legs = new ArrayList<>(), parties = new ArrayList<>();
        final List<Long> locks = new ArrayList<>();
        final List<String> journalQueries = new ArrayList<>();
        final List<String> operationCalls = new ArrayList<>();
        Map<String,Object> operation;
        RuntimeException insertFailure, partyFailure;
        int operationInserts, operationReads, partyInserts;
        boolean corruptReceipt, corruptLeg, failInsert, failLeg, failParty, nullParties;
        public List<Map<String,Object>> lockWallet(long customerId) { locks.add(customerId); return wallets.containsKey(customerId) ? List.of(new HashMap<>(wallets.get(customerId))) : List.of(); }
        public List<Map<String,Object>> journal(String incarnation, long before, long after) {
            journalQueries.add(incarnation + ":" + before + ":" + after);
            return journal.stream().filter(r -> incarnation.equals(r.get("incarnation")) && ((Number) r.get("revision")).longValue() > before && ((Number) r.get("revision")).longValue() <= after).toList();
        }
        public List<Map<String,Object>> birthCandidates(long walletId, long customerId) {
            return journal.stream().filter(r -> Long.valueOf(walletId).equals(r.get("newId")) || Long.valueOf(walletId).equals(r.get("oldId"))
                || Long.valueOf(customerId).equals(r.get("newUserId")) || Long.valueOf(customerId).equals(r.get("oldUserId"))).toList();
        }
        public Map<String,Object> operation(String key) {
            operationCalls.add("READ");
            operationReads++;
            if (operation == null) return null;
            Map<String,Object> copy = new HashMap<>(operation);
            if (corruptReceipt) copy.put("receiptSha256", "f".repeat(64));
            return copy;
        }
        public List<Map<String,Object>> legs(long id) {
            if (corruptLeg) return List.of();
            return List.copyOf(legs);
        }
        public List<Map<String,Object>> parties(long id) { return nullParties ? null : List.copyOf(parties); }
        public List<Long> customerOperations(long customerId) {
            return parties.stream().filter(r -> Long.valueOf(customerId).equals(r.get("customerId")))
                .map(r -> ((Number) r.get("operationId")).longValue()).sorted().toList();
        }
        public int insertParty(long operationId, long customerId) {
            if (partyFailure != null) throw partyFailure;
            if (failParty) return 0;
            Map<String,Object> row = Map.of("operationId", operationId, "customerId", customerId);
            if (parties.contains(row)) throw new DuplicateKeyException("existing party");
            partyInserts++; parties.add(row); return 1;
        }
        public int insertOperation(Map<String,Object> receipt) {
            operationCalls.add("INSERT");
            if (insertFailure != null) throw insertFailure;
            if (operation != null) throw new DuplicateKeyException("existing operation key");
            if (failInsert) return 0;
            operationInserts++; operation = new HashMap<>(receipt); operation.put("id", 101L); operation.put("recordedDbUtc", AT); return 1;
        }
        public int insertLeg(long operationId, String incarnation, long revision) {
            if (failLeg) return 0;
            legs.add(Map.of("operationId", operationId, "incarnation", incarnation, "revision", revision)); return 1;
        }
    }
}
