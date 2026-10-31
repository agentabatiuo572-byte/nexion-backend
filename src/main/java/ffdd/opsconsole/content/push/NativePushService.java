package ffdd.opsconsole.content.push;

import ffdd.opsconsole.content.push.mapper.NativePushMapper;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Installation;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Notification;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NativePushService {
    private final NativePushMapper mapper;
    private final CurrentAppSessionService sessions;

    public NativePushService(NativePushMapper mapper, CurrentAppSessionService sessions) {
        this.mapper = mapper;
        this.sessions = sessions;
    }

    public record Registration(String clientId, String provider, String platform, String permission, String appVersion) { }
    public record Route(String notificationId, String route) { }

    @Transactional
    public void register(Authentication auth, String id, String token, Registration request) {
        checkId(id);
        if (request == null || !validNativeToken(request.provider(), request.platform(), request.clientId())
                || !("android".equals(request.platform()) || "ios".equals(request.platform()))
                || !("granted".equals(request.permission()) || "denied".equals(request.permission()) || "unknown".equals(request.permission()))
                || request.appVersion() == null || request.appVersion().length() > 64)
            throw new BizException(400, "INVALID_PUSH_REGISTRATION");
        String hash = hashToken(token);
        var session = sessions.requireCurrent(auth, id, false);
        Installation old = mapper.lockInstallation(id);
        if (old != null) requireToken(old, hash);
        String cidOwner = mapper.tokenOwner(request.provider(), canonicalToken(request));
        if (cidOwner != null && !id.equals(cidOwner)) throw new BizException(409, "PUSH_TOKEN_ALREADY_BOUND");
        Installation next = new Installation(id, canonicalToken(request), request.provider(), hash, session.getUserId(), session.getSessionChainId(),
                request.platform(), request.permission(), request.appVersion(), old == null ? 1 : old.bindingEpoch(), true);
        try {
            if (old == null) mapper.insert(next);
            else {
                boolean changed = !old.active() || !Objects.equals(old.userId(), next.userId())
                        || !Objects.equals(old.sessionChainId(), next.sessionChainId())
                        || !old.provider().equals(next.provider()) || !old.clientId().equals(next.clientId()) || !old.permission().equals(next.permission())
                        || !old.platform().equals(next.platform());
                if (changed) mapper.cancelPending(id);
                mapper.update(next, changed ? 1 : 0);
            }
        } catch (DuplicateKeyException raced) {
            throw new BizException(409, "PUSH_BINDING_CONFLICT_RETRY");
        }
    }

    @Transactional
    public void remove(Authentication auth, String id, String token) {
        checkId(id);
        var session = sessions.requireCurrent(auth, id, false);
        Installation old = mapper.lockInstallation(id);
        if (old == null) return;
        requireToken(old, hashToken(token));
        if (!Objects.equals(old.userId(), session.getUserId()) || !Objects.equals(old.sessionChainId(), session.getSessionChainId()))
            throw new BizException(403, "PUSH_BINDING_NOT_OWNED");
        mapper.deactivate(id);
        mapper.cancelPending(id);
    }

    @Transactional
    public Route resolve(Authentication auth, long notificationId) {
        long user = sessions.requireSession(auth).getUserId();
        return ownedRoute(notificationId, user);
    }

    @Transactional
    public int opened(Authentication auth, long notificationId, String installationId) {
        checkId(installationId);
        var session = sessions.requireCurrent(auth, installationId, false);
        ownedRoute(notificationId, session.getUserId());
        Installation i = mapper.lockInstallation(installationId);
        if (i == null || !i.active() || !Objects.equals(i.userId(), session.getUserId())
                || !Objects.equals(i.sessionChainId(), session.getSessionChainId())) throw new BizException(403, "PUSH_BINDING_NOT_OWNED");
        return mapper.opened(notificationId, i);
    }

    static Route route(Notification n) {
        if (n == null) throw new BizException(404, "PUSH_NOTIFICATION_NOT_FOUND");
        String route = n.ctaHref();
        boolean allowed = route != null && switch (n.type()) {
            case "DEVICE_EXPIRY" -> route.equals("/pages/me/trial") || route.matches("/pages/earn/device-detail\\?id=[0-9]{1,18}");
            case "DEVICE_ALERT" -> route.matches("/pages/earn/device-detail\\?id=[0-9]{1,18}");
            case "FUNDS_RESULT" -> route.equals("/pages/me/wallet-bills")
                || route.matches("/pages/me/wallet-withdraw-tracking\\?id=WD-[A-Z0-9]{1,93}");
            case "ACCOUNT_SECURITY" -> route.equals("/pages/me/security");
            case "SUPPORT_REPLY" -> route.matches("/pages/support/chat\\?cid=[A-Za-z0-9_-]{1,96}");
            default -> false;
        };
        if (!allowed) throw new BizException(409, "PUSH_ROUTE_UNAVAILABLE");
        return new Route(Long.toString(n.id()), route);
    }

    private Route ownedRoute(long notificationId, long user) {
        Route target = route(mapper.ownedNotification(notificationId, user));
        String path = target.route();
        if (path.startsWith("/pages/earn/device-detail?id=")
                && mapper.ownedDevice(Long.parseLong(path.substring(path.indexOf('=') + 1)), user) == 0)
            throw new BizException(404, "PUSH_TARGET_NOT_FOUND");
        if (path.startsWith("/pages/support/chat?cid=")
                && mapper.ownedConversation(path.substring(path.indexOf('=') + 1), user) == 0)
            throw new BizException(404, "PUSH_TARGET_NOT_FOUND");
        if (path.startsWith("/pages/me/wallet-withdraw-tracking?id=")
                && mapper.ownedWithdrawal(path.substring(path.indexOf('=') + 1), user) == 0)
            throw new BizException(404, "PUSH_TARGET_NOT_FOUND");
        return target;
    }

    static boolean validNativeToken(String provider, String platform, String token) {
        if (token == null || token.length() > 4096) return false;
        if ("APNS".equals(provider) && "ios".equals(platform)) return token.length() % 2 == 0 && token.matches("[a-fA-F0-9]+");
        // Native FCM registration tokens contain the installation prefix and colon; old UniPush CIDs cannot pass.
        return "FCM".equals(provider) && "android".equals(platform)
                && token.length() >= 100 && token.matches("[A-Za-z0-9_-]+:[A-Za-z0-9_:-]+");
    }

    private static String canonicalToken(Registration request) {
        return "APNS".equals(request.provider()) ? request.clientId().toLowerCase(java.util.Locale.ROOT) : request.clientId();
    }

    private static void checkId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{8,128}")) throw new BizException(400, "INVALID_INSTALLATION_ID");
    }

    static String hashToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43,128}")) throw new BizException(403, "PUSH_BINDING_TOKEN_REQUIRED");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void requireToken(Installation old, String hash) {
        if (!MessageDigest.isEqual(old.bindingTokenHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII)))
            throw new BizException(403, "PUSH_BINDING_PROOF_REQUIRED");
    }
}
