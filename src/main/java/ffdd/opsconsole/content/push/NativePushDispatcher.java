package ffdd.opsconsole.content.push;

import ffdd.opsconsole.content.push.mapper.NativePushMapper;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Delivery;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class NativePushDispatcher {
    private final NativePushMapper mapper;
    private final AuthSessionMapper sessions;
    private final UserAccountBlocklistVerifier blocklist;
    private final PlatformConfigFacade config;
    private final DirectPushClient provider;
    private final Environment env;
    private final TransactionTemplate transaction;

    public NativePushDispatcher(NativePushMapper mapper, AuthSessionMapper sessions, UserAccountBlocklistVerifier blocklist,
            PlatformConfigFacade config, DirectPushClient provider, Environment env, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.sessions = sessions; this.blocklist = blocklist;
        this.config = config; this.provider = provider; this.env = env; this.transaction = new TransactionTemplate(transactions);
    }

    public void tick() {
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(env).orElse(null);
        if (audience == null) return;
        int sandbox = audience == UserAuthEnvironment.SANDBOX ? 1 : 0;
        transaction.executeWithoutResult(tx -> {
            Long low = mapper.lockCursor();
            if (low == null) throw new IllegalStateException("Native push migration/cutover missing");
            // Keep the migration cutover fixed. An advancing MAX(id) cursor would skip lower IDs committed out of order.
            // The 24-hour window and unique notification/installation key bound recovery and prevent replay.
            mapper.discover(low, Long.MAX_VALUE, sandbox);
            mapper.expireAmbiguousClaims();
        });
        for (int count = 0; count < 20; count++) {
            Delivery delivery = transaction.execute(tx -> {
                Delivery next = mapper.lockNext();
                if (next == null) return null;
                String token = UUID.randomUUID().toString();
                mapper.claim(next.id(), token);
                return new Delivery(next.id(), next.notificationId(), next.installationId(), next.userId(), next.bindingEpoch(), next.attempts() + 1, token);
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
            if (delivery.attempts() > 5) { finish(delivery, "FAILED", "ATTEMPTS_EXHAUSTED", null, 0); return; }
            var result = provider.send(delivery.id(), delivery.notificationId(), installation.provider(), installation.clientId(), notification.language());
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
}
