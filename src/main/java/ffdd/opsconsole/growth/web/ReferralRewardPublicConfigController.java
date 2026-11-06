package ffdd.opsconsole.growth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import ffdd.opsconsole.device.web.PlatformComputeConfigController;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.platform.dto.PlatformConfigChangedEvent;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** H8 给 App/H5 的只读配置入口；真实结算仍只允许 A2 批准后回放。 */
@RestController
@RequiredArgsConstructor
public class ReferralRewardPublicConfigController {
    private static final long SNAPSHOT_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final OpsReferralRewardService service;
    private final PlatformComputeConfigController platform;
    private final ObjectMapper json;
    private final Map<Boolean, CachedSnapshot> cache = new HashMap<>();
    private long revision;

    @GetMapping("/api/config/referral-rewards")
    public ApiResult<ReferralRewardPublicConfigView> referralRewards() {
        return ApiResult.ok(service.publicConfig());
    }

    @GetMapping(value = "/api/config/platform", params = "snapshot=true")
    public synchronized ApiResult<Snapshot> platformSnapshot(
            @RequestParam(name = "officialLinkFragments", defaultValue = "false") boolean officialLinkFragments,
            @RequestParam(name = "knownVersion", required = false) String knownVersion) {
        CachedSnapshot cached = cache.get(officialLinkFragments);
        if (cached == null || cached.revision() != revision || System.nanoTime() - cached.loadedAt() >= SNAPSHOT_TTL_NANOS) {
            ApiResult<PlatformComputeConfigView> compute = platform.platformConfig(officialLinkFragments);
            if (compute.getCode() != 0) return ApiResult.fail(compute.getCode(), compute.getMessage());
            var next = new Snapshot("", false, compute.getData(), service.publicConfig());
            JsonNode content = content(next);
            String version = cached != null && cached.content().equals(content)
                    ? cached.snapshot().version() : UUID.randomUUID().toString();
            cached = new CachedSnapshot(new Snapshot(version, false, next.platform(), next.referralRewards()),
                    content, System.nanoTime(), revision);
            cache.put(officialLinkFragments, cached);
        }
        Snapshot snapshot = cached.snapshot();
        return ApiResult.ok(snapshot.version().equals(knownVersion)
                ? new Snapshot(snapshot.version(), true, null, null) : snapshot);
    }

    private JsonNode content(Snapshot snapshot) {
        ObjectNode tree = json.valueToTree(snapshot);
        ObjectNode platform = (ObjectNode) tree.get("platform");
        platform.remove("updatedAt");
        // These timestamps describe when the values were observed. Keep the original
        // provenance when the client already has the same values and definitions.
        if (platform.path("publicStats").path("verified") instanceof ObjectNode verified) verified.remove("capturedAt");
        tree.remove("version");
        return tree;
    }

    @Order(0)
    @EventListener
    public synchronized void invalidate(PlatformConfigChangedEvent event) {
        revision += 1;
    }

    private record CachedSnapshot(Snapshot snapshot, JsonNode content, long loadedAt, long revision) { }

    public record Snapshot(String version, boolean unchanged, PlatformComputeConfigView platform,
                           ReferralRewardPublicConfigView referralRewards) { }
}
