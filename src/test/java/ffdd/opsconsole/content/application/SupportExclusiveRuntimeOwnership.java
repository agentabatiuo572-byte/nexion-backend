package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the actual exclusive instance; no legacy shared-resource lease is synthesized. */
final class SupportExclusiveRuntimeOwnership {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SupportExclusiveRuntimeOwnership() {}

    static JsonNode validate(JsonNode context, SupportRuntimeTarget target) {
        require(target.analytics() && context.path("schemaVersion").asInt() == 2
                && "EXCLUSIVE_ANALYTICS".equals(context.path("ownershipMode").asText()), "Exclusive analytics context required");
        JsonNode reference = context.path("resourceOwnership");
        try {
            Path path = Path.of(reference.path("path").asText()).toAbsolutePath().normalize();
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.toRealPath().equals(path), "Plain ownership proof required");
            byte[] bytes = Files.readAllBytes(path);
            require(bytes.length <= 1_048_576 && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .equals(reference.path("sha256").asText()), "Ownership proof hash mismatch");
            JsonNode proof = JSON.readTree(bytes);
            require(proof.path("schemaVersion").asInt() == 1
                    && "EXCLUSIVE_ANALYTICS".equals(proof.path("mode").asText())
                    && target.owner().equals(proof.path("owner").asText()), "Exclusive owner mismatch");
            JsonNode database = proof.path("databaseIdentity");
            require(target.database().equals(database.path("database").asText())
                    && database.path("port").asInt() == target.databasePort()
                    && database.path("serverUuid").asText().matches("[0-9a-fA-F-]{36}")
                    && (target.username() + "@127.0.0.1").equals(database.path("currentUser").asText())
                    && !database.path("dataDirectory").asText().isBlank(), "Exclusive database proof incomplete");
            require(proof.path("resourceIdentity").equals(context.path("resourceIdentity")), "Exclusive resource context mismatch");
            require(proof.path("permissions").isArray() && !proof.path("permissions").isEmpty(), "Actual grants proof required");
            return proof;
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Cannot verify exclusive runtime ownership", failure); }
    }

    static void requireActual(JsonNode context, SupportRuntimeTarget target, JdbcTemplate jdbc) {
        JsonNode proof = validate(context, target);
        JsonNode expected = proof.path("databaseIdentity");
        var actual = jdbc.queryForMap("SELECT DATABASE() AS db,@@port AS port,@@server_uuid AS uuid,CURRENT_USER() AS account,@@datadir AS directory");
        require(expected.path("database").asText().equals(String.valueOf(actual.get("db")))
                && expected.path("port").asInt() == ((Number) actual.get("port")).intValue()
                && expected.path("serverUuid").asText().equals(String.valueOf(actual.get("uuid")))
                && expected.path("currentUser").asText().equals(String.valueOf(actual.get("account")))
                && normalize(expected.path("dataDirectory").asText()).equals(normalize(String.valueOf(actual.get("directory")))),
                "Actual exclusive database identity changed");
        List<String> actualGrants = jdbc.queryForList("SHOW GRANTS", String.class).stream().sorted().toList();
        var expectedGrants = new java.util.ArrayList<String>();
        proof.path("permissions").forEach(grant -> expectedGrants.add(grant.asText()));
        expectedGrants.sort(String::compareTo);
        require(actualGrants.equals(expectedGrants), "Actual exclusive database grants changed");
    }

    private static String normalize(String value) { return value.replace('\\', '/').replaceAll("/+$", ""); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
}
