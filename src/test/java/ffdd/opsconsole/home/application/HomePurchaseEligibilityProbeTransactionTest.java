package ffdd.opsconsole.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.ComputeTaskProofVerifier;
import ffdd.opsconsole.growth.application.GrowthPublicStatsService;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class HomePurchaseEligibilityProbeTransactionTest {

    @Test
    void caughtRequiredDependencyFailureStillPoisonsTheOuterTransaction() throws Exception {
        JdbcTransactions jdbc = new JdbcTransactions();
        AppCanonicalBoundaryService canonical = failingCanonical(jdbc.transactions);

        assertThatThrownBy(() -> new TransactionTemplate(jdbc.transactions).executeWithoutResult(outer -> {
            assertThatThrownBy(() -> canonical.purchaseEligibilityBatch(42L, List.of("sku-1")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(outer.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);

        verify(jdbc.outer).rollback();
        verify(jdbc.outer, never()).commit();
        verify(jdbc.inner, never()).setAutoCommit(false);
    }

    @Test
    void failedOptionalEligibilityRollsBackOnlyItsNewReadTransaction() throws Exception {
        JdbcTransactions jdbc = new JdbcTransactions();
        HomePurchaseEligibilityProbe probe = proxiedProbe(failingCanonical(jdbc.transactions), jdbc.transactions);

        new TransactionTemplate(jdbc.transactions).executeWithoutResult(outer -> {
            assertThatThrownBy(() -> probe.purchaseEligibilityBatch(42L, java.util.List.of("sku-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("dependency unavailable");
            assertThat(outer.isRollbackOnly()).isFalse();
        });

        jdbc.assertIsolatedFailure();
    }

    @Test
    void proxiedHomeReturnsItsProjectionWhenOptionalEligibilityFails() throws Exception {
        JdbcTransactions jdbc = new JdbcTransactions();
        HomePurchaseEligibilityProbe probe = proxiedProbe(failingCanonical(jdbc.transactions), jdbc.transactions);
        AppHomeOverviewMapper mapper = mock(AppHomeOverviewMapper.class);
        when(mapper.userEnvironment(42L)).thenReturn(new AppHomeOverviewMapper.UserEnvironmentRow(false));
        when(mapper.highestActiveDevice(42L, false)).thenReturn(new AppHomeOverviewMapper.OwnedDeviceRow(
                "Your phone", "phone", "TIER-3", "MOBILE", new BigDecimal("0.060000")));
        when(mapper.marketProducts()).thenReturn(List.of(new AppHomeOverviewMapper.MarketProductRow(
                "stellarbox-pro", "StellarBox Pro", "DEVICE", "Pro",
                new BigDecimal("1199.00"), new BigDecimal("13.000000"), 1)));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        AppHomeOverviewService target = new AppHomeOverviewService(mapper,
                mock(ComputeTaskProofVerifier.class), mock(GrowthPublicStatsService.class), probe,
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC), environment);
        AppHomeOverviewService home = proxiedHome(target, jdbc.transactions);

        var result = home.overview(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("doTheMath", null);
        jdbc.assertIsolatedFailure();
        verify(jdbc.outer).setReadOnly(true);
    }

    private AppCanonicalBoundaryService failingCanonical(DataSourceTransactionManager transactions) {
        AppCanonicalBoundaryService target = mock(AppCanonicalBoundaryService.class);
        doThrow(new IllegalStateException("dependency unavailable"))
                .when(target).purchaseEligibilityBatch(any(), any());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        return (AppCanonicalBoundaryService) proxyFactory.getProxy();
    }

    private HomePurchaseEligibilityProbe proxiedProbe(
            AppCanonicalBoundaryService canonical, DataSourceTransactionManager transactions) {
        ProxyFactory proxyFactory = new ProxyFactory(new HomePurchaseEligibilityProbe(canonical));
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        return (HomePurchaseEligibilityProbe) proxyFactory.getProxy();
    }

    private AppHomeOverviewService proxiedHome(
            AppHomeOverviewService target, DataSourceTransactionManager transactions) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        return (AppHomeOverviewService) proxyFactory.getProxy();
    }

    /** Real Spring transaction bookkeeping, with JDBC calls kept off every database. */
    private static final class JdbcTransactions {
        private final Connection outer = mock(Connection.class);
        private final Connection inner = mock(Connection.class);
        private final DataSourceTransactionManager transactions;

        private JdbcTransactions() throws SQLException {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(outer, inner);
            when(outer.getAutoCommit()).thenReturn(true);
            when(inner.getAutoCommit()).thenReturn(true);
            transactions = new DataSourceTransactionManager(dataSource);
        }

        private void assertIsolatedFailure() throws SQLException {
            verify(outer).setAutoCommit(false);
            verify(inner).setAutoCommit(false);
            verify(inner).setReadOnly(true);
            verify(outer).commit();
            verify(outer, never()).rollback();
            verify(inner).rollback();
            verify(inner, never()).commit();
            verify(outer).close();
            verify(inner).close();
        }
    }
}
