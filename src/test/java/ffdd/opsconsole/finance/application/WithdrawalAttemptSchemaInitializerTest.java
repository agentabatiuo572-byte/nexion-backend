package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

class WithdrawalAttemptSchemaInitializerTest {
    static Stream<String> requiredOrderColumns() {
        return WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS.stream();
    }

    @ParameterizedTest
    @MethodSource("requiredOrderColumns")
    void refusesEveryMissingOrderColumnBeforeAnySchemaWrite(String missing) {
        AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
        List<String> columns = new ArrayList<>(WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS);
        columns.remove(missing);
        when(mapper.withdrawalOrderColumns()).thenReturn(columns);

        assertThatThrownBy(() -> new WithdrawalAttemptSchemaInitializer(mapper).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WITHDRAWAL_ORDER_SCHEMA_MIGRATION_REQUIRED")
                .hasMessageContaining(missing)
                .hasMessageContaining("20261015_withdrawal_order_schema_readiness.sql");
        verify(mapper, never()).createWithdrawalAttemptTable();
    }

    @Test
    void completeSchemaAcceptsAdditionalColumnsAndStillChecksTheAttemptFence() {
        AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
        List<String> columns = new ArrayList<>(WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS);
        columns.add("future_optional_column");
        when(mapper.withdrawalOrderColumns()).thenReturn(columns);
        when(mapper.withdrawalAttemptStatusCheckCount()).thenReturn(1);
        when(mapper.withdrawalQuoteExpiryColumnCount()).thenReturn(2);

        assertThatCode(() -> new WithdrawalAttemptSchemaInitializer(mapper).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        var order = inOrder(mapper);
        order.verify(mapper).withdrawalOrderColumns();
        order.verify(mapper).withdrawalQuoteExpiryColumnCount();
        order.verify(mapper).createWithdrawalAttemptTable();
        order.verify(mapper).withdrawalAttemptStatusCheckCount();
    }

    @Test
    void metadataReadFailureStopsStartupWithTheCauseAndMigrationGuidance() {
        AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
        RuntimeException failure = new IllegalStateException("metadata denied");
        when(mapper.withdrawalOrderColumns()).thenThrow(failure);

        assertThatThrownBy(() -> new WithdrawalAttemptSchemaInitializer(mapper).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WITHDRAWAL_ORDER_SCHEMA_CHECK_FAILED")
                .hasMessageContaining("apply_startup_schema_migrations.ps1")
                .hasCause(failure);
        verify(mapper, never()).createWithdrawalAttemptTable();
    }

    @Test
    void incompleteAttemptFenceStillStopsStartup() {
        AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
        when(mapper.withdrawalOrderColumns()).thenReturn(WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS);
        when(mapper.withdrawalQuoteExpiryColumnCount()).thenReturn(2);

        assertThatThrownBy(() -> new WithdrawalAttemptSchemaInitializer(mapper).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WITHDRAWAL_ATTEMPT_STATUS_CHECK_MISSING");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void missingQuoteExpiryTableOrColumnPreventsContextRefresh(int columns) {
        AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
        when(mapper.withdrawalOrderColumns()).thenReturn(WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS);
        when(mapper.withdrawalQuoteExpiryColumnCount()).thenReturn(columns);
        AtomicBoolean refreshed = new AtomicBoolean();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppWithdrawalMapper.class, () -> mapper);
            context.registerBean(WithdrawalAttemptSchemaInitializer.class);
            context.addApplicationListener(event -> {
                if (event instanceof ContextRefreshedEvent) refreshed.set(true);
            });

            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining("WITHDRAWAL_QUOTE_EXPIRY_SCHEMA_MIGRATION_REQUIRED")
                    .hasStackTraceContaining("20260916_bank_beneficiary_verification.sql");
            assertThat(refreshed).isFalse();
            verify(mapper, never()).createWithdrawalAttemptTable();
        }
    }

    @Test
    void quoteExpiryMetadataReadFailureStopsStartupWithItsCause() {
        AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
        when(mapper.withdrawalOrderColumns()).thenReturn(WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS);
        RuntimeException failure = new IllegalStateException("metadata denied");
        when(mapper.withdrawalQuoteExpiryColumnCount()).thenThrow(failure);

        assertThatThrownBy(() -> new WithdrawalAttemptSchemaInitializer(mapper).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WITHDRAWAL_QUOTE_EXPIRY_SCHEMA_CHECK_FAILED")
                .hasMessageContaining("20260916_bank_beneficiary_verification.sql")
                .hasCause(failure);
        verify(mapper, never()).createWithdrawalAttemptTable();
    }

    @Test
    void missingOrderTablePreventsContextRefreshAndScheduledTraffic() {
        AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
        when(mapper.withdrawalOrderColumns()).thenReturn(List.of());
        AtomicBoolean refreshed = new AtomicBoolean();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppWithdrawalMapper.class, () -> mapper);
            context.registerBean(WithdrawalAttemptSchemaInitializer.class);
            context.addApplicationListener(event -> {
                if (event instanceof ContextRefreshedEvent) refreshed.set(true);
            });

            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining("WITHDRAWAL_ORDER_SCHEMA_MIGRATION_REQUIRED");
            assertThat(refreshed).isFalse();
            verify(mapper, never()).createWithdrawalAttemptTable();
        }
    }
}
