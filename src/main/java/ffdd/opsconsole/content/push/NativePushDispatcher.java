package ffdd.opsconsole.content.push;

import ffdd.opsconsole.content.push.mapper.NativePushMapper;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Delivery;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.util.UUID;
import java.util.Map;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@Service
public class NativePushDispatcher {
    private final NativePushMapper mapper;
    private final AuthSessionMapper sessions;
    private final UserAccountBlocklistVerifier blocklist;
    private final PlatformConfigFacade config;
    private final DirectPushClient provider;
    private final Environment env;
    private final TransactionTemplate transaction;
    private final TransactionTemplate discoveryTransaction;
    @Autowired private OpsKillSwitchService killSwitch;
    @Autowired private NativePushService pushService;

    public NativePushDispatcher(NativePushMapper mapper, AuthSessionMapper sessions, UserAccountBlocklistVerifier blocklist,
            PlatformConfigFacade config, DirectPushClient provider, Environment env, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.sessions = sessions; this.blocklist = blocklist;
        this.config = config; this.provider = provider; this.env = env; this.transaction = new TransactionTemplate(transactions);
        this.discoveryTransaction = new TransactionTemplate(transactions);
        this.discoveryTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void tick() {
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(env).orElse(null);
        if (audience == null) return;
        int sandbox = audience == UserAuthEnvironment.SANDBOX ? 1 : 0;
        discoveryTransaction.executeWithoutResult(tx -> {
            Long low = mapper.lockCursor();
            if (low == null) throw new IllegalStateException("Native push migration/cutover missing");
            // Keep the migration cutover fixed. An advancing MAX(id) cursor would skip lower IDs committed out of order.
            // The 24-hour window and unique notification/installation key bound recovery and prevent replay.
            mapper.discover(low, Long.MAX_VALUE, sandbox);
            if (testBusinessAlerts()) mapper.discoverNova(sandbox, NativePushService.TEST_APNS_TOPIC);
            mapper.expireAmbiguousClaims();
        });
        for (int count = 0; count < 20; count++) {
            Delivery delivery = transaction.execute(tx -> {
                Delivery next = mapper.lockNext();
                if (next == null) return null;
                String token = UUID.randomUUID().toString();
                mapper.claim(next.id(), token);
                return new Delivery(next.id(), next.notificationId(), next.installationId(), next.userId(), next.bindingEpoch(), next.attempts() + 1, token, next.expiresAt(), next.threadId());
            });
            if (delivery == null) return;
            send(delivery, sandbox);
        }
    }

    void send(Delivery delivery, int sandbox) {
        // ponytail: serialize one installation through the bounded HTTP call. Replace with a fenced gateway if throughput requires it.
        // Locks stay held through send so an accepted registration/removal cannot race with an old binding's send.
        transaction.executeWithoutResult(tx -> {
            boolean account = sessions.lockAccount(delivery.userId()) != null;
            var installation = mapper.lockInstallation(delivery.installationId());
            if (mapper.lockClaim(delivery.id(), delivery.leaseToken()) == null) return;
            if (!account || installation == null || !installation.active()
                    || installation.userId().longValue() != delivery.userId() || installation.bindingEpoch() != delivery.bindingEpoch()
                    || !NativePushService.validNativeToken(installation.provider(), installation.platform(), installation.clientId())
                    || !"granted".equals(installation.permission()) || blocklist.isBlocked(delivery.userId())
                    || mapper.activeSession(installation, sandbox, idleDays()) == 0
                    || mapper.stillEligible(delivery.notificationId(), delivery.userId()) == 0) {
                finish(delivery, "CANCELLED", "BINDING_OR_SESSION_EXPIRED", null, 0);
                return;
            }
            var notification = mapper.ownedNotification(delivery.notificationId(), delivery.userId());
            if (notification == null) { finish(delivery, "CANCELLED", "NOTIFICATION_REMOVED", null, 0); return; }
            java.time.Instant effectiveExpiry = effectiveExpiry(delivery, installation);
            if (effectiveExpiry != null && !java.time.Instant.now().isBefore(effectiveExpiry)) {
                finish(delivery, "CANCELLED", "PUSH_EXPIRED", null, 0); return;
            }
            boolean earning = notification.type().startsWith("EARNING_");
            if (earning && !earningEligible(notification.type(), delivery.userId(), installation)) {
                finish(delivery, "CANCELLED", "EARNING_INELIGIBLE", null, 0); return;
            }
            if (earning) {
                try {
                    // Push carries only the notification ID. Resolve checks the target again when tapped.
                    pushService.earningRoute(notification, delivery.userId());
                } catch (BizException invalidRoute) {
                    finish(delivery, "CANCELLED", "EARNING_RECEIPT_UNAVAILABLE", null, 0); return;
                }
            }
            if (delivery.attempts() > 5) { finish(delivery, "FAILED", "ATTEMPTS_EXHAUSTED", null, 0); return; }
            boolean nova = notification.isNova();
            boolean enhanced = testBusinessAlerts();
            boolean appUpdate = "APP_UPDATE".equals(notification.type());
            if (appUpdate && (!enhanced || !"ios".equals(installation.platform())
                    || !NativePushService.TEST_APNS_TOPIC.equals(installation.apnsTopic())
                    || installation.appBuild() == null || notification.updateBuild() == null
                    || installation.appBuild() >= notification.updateBuild()
                    || mapper.activeIosUpdate(notification.updateBuild(), env.getProperty("nexion.app-updates.test-ios-install-url", "")) == 0
                    || killSwitch.notificationEmergencyStop())) {
                finish(delivery, "CANCELLED", "APP_UPDATE_NOT_APPLICABLE", null, 0);
                return;
            }
            if (nova && (!enhanced || mapper.novaChannelEnabled(notification.id()) == 0 || killSwitch.notificationEmergencyStop())) {
                finish(delivery, "CANCELLED", "NOVA_DISABLED", null, 0);
                return;
            }
            boolean support = enhanced && "SUPPORT_REPLY".equals(notification.type());
            boolean newSignIn = enhanced && "ACCOUNT_SECURITY".equals(notification.type())
                    && NativePushService.TEST_APNS_TOPIC.equals(installation.apnsTopic())
                    && mapper.isNewSignInNotice(notification.id(), delivery.userId());
            DirectPushClient.Result result;
            if (nova || appUpdate || support || earning || installation.apnsTopic() != null) {
                String summary = DirectPushClient.message(notification.language());
                if (support) summary = DirectPushClient.supportReply(notification.language());
                if (newSignIn) summary = notification.body();
                Map<String,String> alert = nova || appUpdate || earning
                        ? Map.of("title", notification.title(), "body", notification.body())
                        : Map.of("title", "UVEL", "body", summary);
                boolean configuredAlert = nova || appUpdate || support || newSignIn || earning;
                if (earning || delivery.expiresAt() != null || delivery.threadId() != null) {
                    result = provider.send(delivery.id(), notification.id(), installation.provider(), installation.clientId(), alert,
                            installation.apnsTopic(), configuredAlert,
                            effectiveExpiry,
                            delivery.threadId());
                } else {
                    result = provider.send(delivery.id(), notification.id(), installation.provider(), installation.clientId(), alert,
                            installation.apnsTopic(), configuredAlert);
                }
            } else {
                result = provider.send(delivery.id(), notification.id(), installation.provider(), installation.clientId(), notification.language());
            }
            String status = result.status();
            if ("INVALID_TOKEN".equals(status)) {
                mapper.deactivate(installation.installationId());
                // Mark this claim first; cancellation retires other deliveries of the same invalid token.
                finish(delivery, "INVALID_TOKEN", result.code(), result.taskId(), 0);
                mapper.cancelPending(installation.installationId());
            } else {
                if ("RETRY".equals(status) && delivery.attempts() >= 5) status = "FAILED";
                finish(delivery, status, result.code(), result.taskId(), "BLOCKED".equals(status) ? 300 : Math.max(result.retryAfterSeconds(), Math.min(3600, 30 << Math.min(delivery.attempts(), 6))));
            }
        });
    }

    private void finish(Delivery d, String status, String error, String task, int delay) {
        mapper.finish(d.id(), d.leaseToken(), status, error, task, delay);
    }

    private int idleDays() {
        try {
            int value = config.activeValue("auth.session.idle_ttl_days").map(Integer::parseInt).orElse(30);
            return value >= 7 && value <= 90 ? value : 30;
        } catch (NumberFormatException invalid) { return 30; }
    }

    private java.time.Instant effectiveExpiry(Delivery delivery, NativePushMapper.Installation installation) {
        if (delivery.expiresAt() == null) return null;
        java.time.Instant base = delivery.expiresAt().toInstant(java.time.ZoneOffset.UTC);
        if (installation.timeZone() == null) return base;
        try {
            java.time.ZoneId zone = java.time.ZoneId.of(installation.timeZone());
            java.time.ZonedDateTime local = java.time.Instant.now().atZone(zone);
            java.time.ZonedDateTime boundary = local.toLocalDate().atTime(23, 0).atZone(zone);
            if (!local.isBefore(boundary)) boundary = boundary.plusDays(1);
            java.time.Instant shortened = boundary.toInstant();
            return shortened.isBefore(base) ? shortened : base;
        } catch (RuntimeException failure) {
            return base;
        }
    }

    private boolean earningEligible(String type, long userId, NativePushMapper.Installation installation) {
        if (!installation.earningPushEligible() || installation.timeZone() == null) return false;
        var preference = mapper.earningEligibility(userId);
        String mode = preference == null || preference.mode() == null ? "DAILY" : preference.mode();
        boolean system = preference == null || !Boolean.FALSE.equals(preference.system());
        if (!system || "OFF".equals(mode)) return false;
        if ("EARNING_REALTIME".equals(type) && !"REALTIME".equals(mode)) return false;
        if ("EARNING_DAILY".equals(type) && !"DAILY".equals(mode)) return false;
        if ("EARNING_HALF_HOURLY".equals(type) && !"HALF_HOURLY".equals(mode)) return false;
        java.time.ZoneId zone;
        try { zone = java.time.ZoneId.of(installation.timeZone()); }
        catch (RuntimeException failure) { return false; }
        int hour = java.time.ZonedDateTime.now(zone).getHour();
        return hour < 23 && hour >= 8;
    }

    private boolean testBusinessAlerts() {
        return Boolean.TRUE.equals(env.getProperty("nexion.deployment.public-test", Boolean.class));
    }
}
