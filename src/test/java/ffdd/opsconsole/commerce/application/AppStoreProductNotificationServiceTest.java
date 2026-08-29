package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.commerce.mapper.AppStoreProductNotificationMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class AppStoreProductNotificationServiceTest {
    @Mock AppStoreProductNotificationMapper mapper;
    @Mock StorefrontProductReleasePolicy releasePolicy;
    @Mock Environment environment;
    @InjectMocks AppStoreProductNotificationService service;

    @BeforeEach
    void useProductionAccountAudienceByDefault() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
    }

    @Test
    void subscribeIsIdempotentAndReturnsServerCanonicalState() {
        var product = new AppStoreProductNotificationMapper.ProductRow(
                10L, "stellarbox-pro-v2", "StellarBox Pro v2", "ACTIVE", "P3", LocalDateTime.of(2026, 8, 16, 1, 2, 3));
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.product("stellarbox-pro-v2")).thenReturn(product);
        when(releasePolicy.evaluate("stellarbox-pro-v2", "P3"))
                .thenReturn(StorefrontProductReleasePolicy.Decision.closed("E1_PHASE_NOT_REACHED", "P3"));
        when(mapper.upsert(eq(7L), eq(product), eq("E1_PHASE_NOT_REACHED"), eq("P3"), eq("2026-08-16T01:02:03")))
                .thenReturn(1);
        when(mapper.activeSubscription(7L, "stellarbox-pro-v2"))
                .thenReturn(new AppStoreProductNotificationMapper.SubscriptionRow(
                        1L, 7L, "stellarbox-pro-v2", "E1_PHASE_NOT_REACHED", "P3", "2026-08-16T01:02:03", "nx_product",
                        LocalDateTime.of(2026, 8, 16, 1, 2, 3)));

        ApiResult<?> result = service.subscribe(7L, "stellarbox-pro-v2");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isInstanceOf(AppStoreProductNotificationService.NotificationView.class);
        var view = (AppStoreProductNotificationService.NotificationView) result.getData();
        assertThat(view.serverCanonical()).isTrue();
        assertThat(view.source()).isEqualTo("nx_product");
        assertThat(view.subscribed()).isTrue();
        assertThat(view.revision()).isEqualTo("2026-08-16T01:02:03");
        verify(mapper).upsert(eq(7L), eq(product), eq("E1_PHASE_NOT_REACHED"), eq("P3"), eq("2026-08-16T01:02:03"));
    }

    @Test
    void developmentUsesTheAuthenticatedCanonicalAccountWithProductionProvenance() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        var product = new AppStoreProductNotificationMapper.ProductRow(
                10L, "stellarbox-pro-v2", "StellarBox Pro v2", "ACTIVE", "P3",
                LocalDateTime.of(2026, 8, 16, 1, 2, 3));
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.product("stellarbox-pro-v2")).thenReturn(product);
        when(releasePolicy.evaluate("stellarbox-pro-v2", "P3"))
                .thenReturn(StorefrontProductReleasePolicy.Decision.closed("E1_PHASE_NOT_REACHED", "P3"));
        when(mapper.upsert(eq(7L), eq(product), eq("E1_PHASE_NOT_REACHED"), eq("P3"),
                eq("2026-08-16T01:02:03"))).thenReturn(1);
        when(mapper.activeSubscription(7L, "stellarbox-pro-v2"))
                .thenReturn(new AppStoreProductNotificationMapper.SubscriptionRow(
                        1L, 7L, "stellarbox-pro-v2", "E1_PHASE_NOT_REACHED", "P3",
                        "2026-08-16T01:02:03", "nx_product", LocalDateTime.of(2026, 8, 16, 1, 2, 3)));

        ApiResult<?> result = service.subscribe(7L, "stellarbox-pro-v2");

        assertThat(result.getCode()).isZero();
        var view = (AppStoreProductNotificationService.NotificationView) result.getData();
        assertThat(view.sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(view.runId()).isEmpty();
        verify(mapper).activeUser(7L);
        verify(mapper).upsert(eq(7L), eq(product), eq("E1_PHASE_NOT_REACHED"), eq("P3"),
                eq("2026-08-16T01:02:03"));
    }

    @Test
    void availableProductCannotBeSubscribedTo() {
        var product = new AppStoreProductNotificationMapper.ProductRow(
                10L, "stellarbox-pro", "StellarBox Pro", "ACTIVE", "P2", LocalDateTime.now());
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.product("stellarbox-pro")).thenReturn(product);
        when(releasePolicy.evaluate("stellarbox-pro", "P2"))
                .thenReturn(StorefrontProductReleasePolicy.Decision.open("P2"));

        ApiResult<?> result = service.subscribe(7L, "stellarbox-pro");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("PRODUCT_ALREADY_AVAILABLE");
    }

    @Test
    void subscribeNormalizesProductNumberBeforeEveryAuthorityRead() {
        var product = new AppStoreProductNotificationMapper.ProductRow(
                10L, "stellarbox-pro-v2", "StellarBox Pro v2", "ACTIVE", "P3", LocalDateTime.now());
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.product("stellarbox-pro-v2")).thenReturn(product);
        when(releasePolicy.evaluate("stellarbox-pro-v2", "P3"))
                .thenReturn(StorefrontProductReleasePolicy.Decision.open("P3"));

        ApiResult<?> result = service.subscribe(7L, "  stellarbox-pro-v2  ");

        assertThat(result.getCode()).isEqualTo(409);
        verify(mapper).product("stellarbox-pro-v2");
    }

    @Test
    void statusDoesNotKeepAnOldSubscriptionActiveAfterProductBecomesAvailable() {
        var product = new AppStoreProductNotificationMapper.ProductRow(
                10L, "stellarbox-pro", "StellarBox Pro", "ACTIVE", "P2", LocalDateTime.now());
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.product("stellarbox-pro")).thenReturn(product);
        when(releasePolicy.evaluate("stellarbox-pro", "P2"))
                .thenReturn(StorefrontProductReleasePolicy.Decision.open("P2"));
        when(mapper.activeSubscription(7L, "stellarbox-pro"))
                .thenReturn(new AppStoreProductNotificationMapper.SubscriptionRow(
                        1L, 7L, "stellarbox-pro", "E1_PHASE_NOT_REACHED", "P2", "old", "nx_product",
                        LocalDateTime.now()));

        ApiResult<?> result = service.status(7L, "stellarbox-pro");

        assertThat(result.getCode()).isZero();
        assertThat(((AppStoreProductNotificationService.NotificationView) result.getData()).subscribed()).isFalse();
    }

    @Test
    void deleteIsAccountScopedAndIdempotent() {
        when(mapper.activeUser(8L)).thenReturn(8L);
        when(mapper.deactivate(8L, "stellarbox-pro-v2")).thenReturn(0);

        ApiResult<?> result = service.unsubscribe(8L, "stellarbox-pro-v2");

        assertThat(result.getCode()).isZero();
        var view = (AppStoreProductNotificationService.NotificationView) result.getData();
        assertThat(view.subscribed()).isFalse();
        verify(mapper).deactivate(8L, "stellarbox-pro-v2");
    }
}
