package ffdd.opsconsole.developer.application;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Shared create-time and delivery-time SSRF policy. */
public final class DeveloperWebhookUrlValidator {
    private DeveloperWebhookUrlValidator() { }

    public static URI validate(String raw, Environment environment) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() < -1) throw invalid("DEVELOPER_WEBHOOK_URL_INVALID");
            boolean explicitLoopback = isOnlyLocalSandbox(environment) && "http".equals(scheme)
                    && Boolean.parseBoolean(environment.getProperty("nexion.developer.webhooks.allow-loopback", "false"))
                    && isLoopbackLiteral(host);
            if (!"https".equals(scheme) && !explicitLoopback) throw invalid("DEVELOPER_WEBHOOK_HTTPS_REQUIRED");
            if (isForbiddenLiteral(host) && !explicitLoopback) throw invalid("DEVELOPER_WEBHOOK_HOST_NOT_ALLOWED");
            if (!explicitLoopback) {
                String allowed = environment.getProperty("nexion.developer.webhooks.allowed-hosts", "");
                boolean matches = Arrays.stream(allowed.split(",")).map(String::trim).filter(v -> !v.isBlank())
                        .anyMatch(v -> host.equalsIgnoreCase(v) || host.toLowerCase(Locale.ROOT).endsWith("." + v.toLowerCase(Locale.ROOT)));
                if (!matches) throw invalid("DEVELOPER_WEBHOOK_HOST_NOT_ALLOWED");
            }
            return uri;
        } catch (ffdd.opsconsole.shared.exception.BizException ex) { throw ex;
        } catch (IllegalArgumentException ex) { throw invalid("DEVELOPER_WEBHOOK_URL_INVALID"); }
    }

    public static void rejectResolvedPrivateAddresses(URI uri) {
        rejectResolvedPrivateAddresses(uri, null);
    }

    /**
     * Delivery-time SSRF guard. The only private address exception is an explicitly opted-in loopback endpoint
     * in the test/acceptance/local-sandbox profiles; all other private, link-local, metadata, and multicast
     * addresses remain fail-closed.
     */
    public static void rejectResolvedPrivateAddresses(URI uri, Environment environment) {
        try {
            resolveAndValidate(uri, environment);
        } catch (java.net.UnknownHostException ex) {
            throw invalid("DEVELOPER_WEBHOOK_HOST_UNRESOLVED");
        }
    }

    /**
     * Resolves exactly once for the network operation and returns only addresses that passed the SSRF
     * policy. The production transport connects directly to one of these addresses, so the validated
     * resolution cannot be replaced by a second resolver lookup between validation and connect.
     */
    static InetAddress[] resolveAndValidate(URI uri, Environment environment) throws java.net.UnknownHostException {
        boolean loopbackOptIn = environment != null && isLoopbackOptIn(uri, environment);
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || "169.254.169.254".equals(address.getHostAddress())) {
                if (loopbackOptIn && address.isLoopbackAddress()) continue;
                throw invalid("DEVELOPER_WEBHOOK_PRIVATE_ADDRESS_FORBIDDEN");
            }
        }
        return addresses;
    }

    private static boolean isForbiddenLiteral(String host) {
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (Set.of("localhost", "localhost.localdomain", "ip6-localhost", "metadata", "metadata.google.internal", "instance-data", "0.0.0.0", "::1").contains(h)) return true;
        if (h.matches("10\\..*") || h.matches("192\\.168\\..*") || h.matches("169\\.254\\..*")) return true;
        return h.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*") || h.matches("127\\..*")
                || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9")
                || h.startsWith("fea") || h.startsWith("feb");
    }

    private static boolean isLoopbackOptIn(URI uri, Environment environment) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return isOnlyLocalSandbox(environment) && "http".equals(scheme)
                && Boolean.parseBoolean(environment.getProperty("nexion.developer.webhooks.allow-loopback", "false"))
                && isLoopbackLiteral(uri.getHost());
    }

    private static boolean isLoopbackLiteral(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return Set.of("localhost", "localhost.localdomain", "ip6-localhost", "::1").contains(h)
                || h.matches("127\\..*");
    }

    private static boolean isOnlyLocalSandbox(Environment environment) {
        if (environment == null) return false;
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 1 && "dev".equalsIgnoreCase(profiles[0]);
    }

    private static ffdd.opsconsole.shared.exception.BizException invalid(String message) { return new ffdd.opsconsole.shared.exception.BizException(422, message); }
}
