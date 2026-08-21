package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.AppStoreProductNotificationMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppStoreProductNotificationService {
    private static final Pattern PRODUCT_NO = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final AppStoreProductNotificationMapper mapper;
    private final StorefrontProductReleasePolicy releasePolicy;
    private final WheelSandboxProfile wheelSandboxProfile;
    private final Environment environment;

    @Transactional
    public ApiResult<NotificationView> subscribe(Long userId, String productNo) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (!validProductNo(productNo)) return ApiResult.fail(400, "PRODUCT_NO_INVALID");
        String normalizedProductNo = productNo.trim();
        Scope scope = scope(userId);
        if (activeUser(userId) == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        AppStoreProductNotificationMapper.ProductRow product = mapper.product(normalizedProductNo);
        if (product == null) return ApiResult.fail(404, "PRODUCT_NOT_FOUND");
        if (!saleStatus(product.status())) return ApiResult.fail(404, "PRODUCT_NOT_FOUND");
        StorefrontProductReleasePolicy.Decision release = releasePolicy.evaluate(product.productNo(), product.unlockPhase());
        if (release.available()) return ApiResult.fail(409, "PRODUCT_ALREADY_AVAILABLE");
        String revision = revision(product.updatedAt());
        if (upsert(userId, scope, product, release.reason(), release.releasePhaseId(), revision) < 1) {
            return ApiResult.fail(503, "PRODUCT_NOTIFICATION_UNAVAILABLE");
        }
        AppStoreProductNotificationMapper.SubscriptionRow row = activeSubscription(userId, scope, product.productNo());
        return row == null
                ? ApiResult.fail(503, "PRODUCT_NOTIFICATION_UNAVAILABLE")
                : ApiResult.ok(view(row, scope, release.reason(), release.releasePhaseId(), revision));
    }

    @Transactional(readOnly = true)
    public ApiResult<NotificationView> status(Long userId, String productNo) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (!validProductNo(productNo)) return ApiResult.fail(400, "PRODUCT_NO_INVALID");
        String normalizedProductNo = productNo.trim();
        Scope scope = scope(userId);
        if (activeUser(userId) == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        AppStoreProductNotificationMapper.ProductRow product = mapper.product(normalizedProductNo);
        if (product == null || !saleStatus(product.status())) return ApiResult.fail(404, "PRODUCT_NOT_FOUND");
        StorefrontProductReleasePolicy.Decision release = releasePolicy.evaluate(product.productNo(), product.unlockPhase());
        String revision = revision(product.updatedAt());
        AppStoreProductNotificationMapper.SubscriptionRow row = activeSubscription(userId, scope, product.productNo());
        if (release.available()) {
            return ApiResult.ok(new NotificationView(true, "nx_product", false, revision,
                    product.productNo(), release.reason(), release.releasePhaseId(),
                    scope.sourceEnvironment(), scope.runId()));
        }
        return ApiResult.ok(row == null
                ? new NotificationView(true, "nx_product", false, revision, product.productNo(), release.reason(), release.releasePhaseId(),
                    scope.sourceEnvironment(), scope.runId())
                : view(row, scope, release.reason(), release.releasePhaseId(), revision));
    }

    @Transactional(readOnly = true)
    public ApiResult<NotificationListView> list(Long userId) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        Scope scope = scope(userId);
        if (activeUser(userId) == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        List<AppStoreProductNotificationMapper.SubscriptionRow> rows = activeSubscriptions(userId, scope);
        if (rows == null) return ApiResult.fail(503, "PRODUCT_NOTIFICATION_UNAVAILABLE");
        List<NotificationView> pending = new ArrayList<>();
        for (AppStoreProductNotificationMapper.SubscriptionRow row : rows) {
            AppStoreProductNotificationMapper.ProductRow product = mapper.product(row.productNo());
            if (product == null || !saleStatus(product.status())) continue;
            StorefrontProductReleasePolicy.Decision release = releasePolicy.evaluate(product.productNo(), product.unlockPhase());
            if (release.available()) continue;
            pending.add(view(row, scope, release.reason(), release.releasePhaseId(), revision(product.updatedAt())));
        }
        return ApiResult.ok(new NotificationListView(true, "nx_product", List.copyOf(pending),
                scope.sourceEnvironment(), scope.runId()));
    }

    @Transactional
    public ApiResult<NotificationView> unsubscribe(Long userId, String productNo) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (!validProductNo(productNo)) return ApiResult.fail(400, "PRODUCT_NO_INVALID");
        Scope scope = scope(userId);
        if (activeUser(userId) == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        String normalizedProductNo = productNo.trim();
        AppStoreProductNotificationMapper.SubscriptionRow current = activeSubscription(userId, scope, normalizedProductNo);
        deactivate(userId, scope, normalizedProductNo);
        return current == null
                ? ApiResult.ok(new NotificationView(true, "nx_product", false, "", normalizedProductNo, null, null,
                    scope.sourceEnvironment(), scope.runId()))
                : ApiResult.ok(new NotificationView(true, text(current.source(), "nx_product"), false,
                        text(current.revision(), revision(current.updatedAt())), current.productNo(),
                        current.releaseState(), current.releasePhaseId(), scope.sourceEnvironment(), scope.runId()));
    }

    private NotificationView view(AppStoreProductNotificationMapper.SubscriptionRow row,
                                  Scope scope,
                                  String releaseState, String releasePhaseId, String revision) {
        return new NotificationView(true, text(row.source(), "nx_product"), true,
                text(row.revision(), revision), row.productNo(), releaseState, releasePhaseId,
                scope.sourceEnvironment(), scope.runId());
    }

    private Scope scope(Long userId) {
        if (wheelSandboxProfile == null) return new Scope("PRODUCTION", "", false);
        wheelSandboxProfile.requireKnownRuntime();
        return switch (wheelSandboxProfile.mode()) {
            case SANDBOX -> new Scope("SANDBOX", wheelSandboxProfile.requireRunId(), true);
            case PRODUCTION -> new Scope("PRODUCTION", "", false);
            case UNKNOWN -> throw new BizException(503, "PRODUCT_NOTIFICATION_RUNTIME_UNSUPPORTED");
        };
    }

    private Long activeUser(Long userId) {
        UserAuthEnvironment authEnvironment = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "PRODUCT_NOTIFICATION_RUNTIME_UNSUPPORTED"));
        return authEnvironment == UserAuthEnvironment.SANDBOX
                ? mapper.activeSandboxUser(userId) : mapper.activeUser(userId);
    }

    private AppStoreProductNotificationMapper.SubscriptionRow activeSubscription(Long userId, Scope scope, String productNo) {
        return scope.sandbox()
                ? mapper.activeSubscriptionScoped(userId, productNo, scope.sourceEnvironment(), scope.runId())
                : mapper.activeSubscription(userId, productNo);
    }

    private List<AppStoreProductNotificationMapper.SubscriptionRow> activeSubscriptions(Long userId, Scope scope) {
        return scope.sandbox()
                ? mapper.activeSubscriptionsScoped(userId, scope.sourceEnvironment(), scope.runId())
                : mapper.activeSubscriptions(userId);
    }

    private int upsert(Long userId, Scope scope, AppStoreProductNotificationMapper.ProductRow product,
                       String releaseState, String releasePhaseId, String revision) {
        return scope.sandbox()
                ? mapper.upsertScoped(userId, product, releaseState, releasePhaseId, revision,
                    scope.sourceEnvironment(), scope.runId())
                : mapper.upsert(userId, product, releaseState, releasePhaseId, revision);
    }

    private int deactivate(Long userId, Scope scope, String productNo) {
        return scope.sandbox()
                ? mapper.deactivateScoped(userId, productNo, scope.sourceEnvironment(), scope.runId())
                : mapper.deactivate(userId, productNo);
    }

    private boolean validUser(Long userId) { return userId != null && userId > 0; }
    private boolean validProductNo(String value) { return StringUtils.hasText(value) && PRODUCT_NO.matcher(value.trim()).matches(); }
    private boolean saleStatus(String status) { return "ACTIVE".equalsIgnoreCase(status) || "ON_SALE".equalsIgnoreCase(status); }
    private String revision(LocalDateTime value) { return value == null ? "" : value.toString(); }
    private String text(String value, String fallback) { return StringUtils.hasText(value) ? value : fallback; }

    public record NotificationView(boolean serverCanonical, String source, boolean subscribed,
                                   String revision, String productNo, String releaseState,
                                   String releasePhaseId, String sourceEnvironment, String runId) {
        public NotificationView(boolean serverCanonical, String source, boolean subscribed,
                                String revision, String productNo, String releaseState, String releasePhaseId) {
            this(serverCanonical, source, subscribed, revision, productNo, releaseState, releasePhaseId, "PRODUCTION", "");
        }
    }

    public record NotificationListView(boolean serverCanonical, String source,
                                       List<NotificationView> subscriptions,
                                       String sourceEnvironment, String runId) {
        public NotificationListView(boolean serverCanonical, String source, List<NotificationView> subscriptions) {
            this(serverCanonical, source, subscriptions, "PRODUCTION", "");
        }
    }

    private record Scope(String sourceEnvironment, String runId, boolean sandbox) { }
}
