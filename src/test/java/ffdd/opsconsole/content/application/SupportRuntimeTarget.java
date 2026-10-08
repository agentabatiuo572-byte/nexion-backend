package ffdd.opsconsole.content.application;

import java.util.Map;

/** Test-only resource allowlist. Selecting a target never authorizes arbitrary connection overrides. */
public record SupportRuntimeTarget(String database, int databasePort, String username, int httpPort,
        int redisPort, String storageEndpoint, String bucket, String owner) {
    private static final SupportRuntimeTarget LEGACY = new SupportRuntimeTarget(
            "cs_enhance_20261001", 33329, "cs_enhance_runner", 18141, 16341,
            "http://127.0.0.1:19041", "cs-enhance-20261001-private",
            "cs_enhance_20261001|codex/cs-enhance-core-20261001");
    private static final SupportRuntimeTarget ANALYTICS = new SupportRuntimeTarget(
            "cs_analytics_20261007", 33337, "cs_analytics_runner", 18161, 16343,
            "http://127.0.0.1:19043", "cs-analytics-20261007-private",
            "cs_analytics_20261007|codex/cs-analytics-api-20261007");

    public static SupportRuntimeTarget current() { return select(System.getenv()); }

    static SupportRuntimeTarget select(Map<String, String> environment) {
        return switch (environment.getOrDefault("SUPPORT_RUNTIME_TARGET", "legacy")) {
            case "legacy" -> LEGACY;
            case "analytics-20261007" -> ANALYTICS;
            default -> throw new IllegalStateException("Unknown isolated support runtime target");
        };
    }

    public String jdbcPrefix() { return "jdbc:mysql://127.0.0.1:" + databasePort + "/" + database + "?"; }
    public String httpBase() { return "http://127.0.0.1:" + httpPort; }
    public String websocketBase() { return "ws://127.0.0.1:" + httpPort; }
    public boolean analytics() { return equals(ANALYTICS); }

    void requireEnvironment(Map<String, String> environment) {
        String url = environment.get("NEXION_DB_URL");
        if (url == null || !url.startsWith(jdbcPrefix()))
            throw new IllegalStateException("Independent local database required before boot");
        for (var expected : Map.of("NEXION_DB_USERNAME", username,
                "NEXION_REDIS_HOST", "127.0.0.1", "NEXION_REDIS_PORT", String.valueOf(redisPort),
                "NEXION_MINIO_ENDPOINT", storageEndpoint, "NEXION_MINIO_BUCKET", bucket).entrySet()) {
            if (!expected.getValue().equals(environment.get(expected.getKey())))
                throw new IllegalStateException("Independent runtime setting required: " + expected.getKey());
        }
    }
}
