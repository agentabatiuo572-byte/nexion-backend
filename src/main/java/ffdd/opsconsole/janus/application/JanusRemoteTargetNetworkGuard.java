package ffdd.opsconsole.janus.application;

import java.net.URI;

/**
 * Resolves every current A/AAAA record and permits a target only when every
 * resolved address is publicly routable. Callers intentionally re-run this
 * guard both while approving and while consuming a target.
 */
public interface JanusRemoteTargetNetworkGuard {
    boolean allows(URI uri);
}
