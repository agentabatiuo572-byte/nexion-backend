package ffdd.opsconsole.team.acceptance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only, secret-free manifest for one isolated team acceptance run. */
record TeamTestRunManifest(
        String runId,
        String databaseName,
        String configHash,
        List<Account> accounts,
        List<String> orderNumbers,
        List<Long> commissionEventIds) {

    static final List<String> ROLE_ORDER = List.of(
            "R", "A", "A1", "A11", "A12", "A13", "A14", "Buyer",
            "A2", "A3", "B", "B1", "B2", "B3", "C", "Q", "O");

    static Map<String, String> sponsorRoles() {
        Map<String, String> sponsors = new LinkedHashMap<>();
        sponsors.put("R", null);
        sponsors.put("A", "R");
        sponsors.put("A1", "A");
        sponsors.put("A11", "A1");
        sponsors.put("A12", "A11");
        sponsors.put("A13", "A12");
        sponsors.put("A14", "A13");
        sponsors.put("Buyer", "A14");
        sponsors.put("A2", "A");
        sponsors.put("A3", "A");
        sponsors.put("B", "R");
        sponsors.put("B1", "B");
        sponsors.put("B2", "B");
        sponsors.put("B3", "B");
        sponsors.put("C", "R");
        sponsors.put("Q", "R");
        sponsors.put("O", null);
        return Collections.unmodifiableMap(new LinkedHashMap<>(sponsors));
    }

    static TeamTestRunManifest empty(String runId, String databaseName, String canonicalConfigJson) {
        return new TeamTestRunManifest(
                requireRunId(runId),
                requireAcceptanceDatabase(databaseName),
                sha256(canonicalConfigJson == null ? "" : canonicalConfigJson),
                List.of(),
                List.of(),
                List.of());
    }

    TeamTestRunManifest {
        requireRunId(runId);
        requireAcceptanceDatabase(databaseName);
        if (configHash == null || !configHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("configHash must be SHA-256 hex");
        }
        accounts = List.copyOf(accounts == null ? List.of() : accounts);
        orderNumbers = List.copyOf(orderNumbers == null ? List.of() : orderNumbers);
        commissionEventIds = List.copyOf(commissionEventIds == null ? List.of() : commissionEventIds);
    }

    List<String> buyerUplineRoles() {
        Map<String, String> sponsors = sponsorRoles();
        List<String> result = new ArrayList<>();
        String role = "Buyer";
        while (sponsors.get(role) != null && result.size() < 7) {
            role = sponsors.get(role);
            result.add(role);
        }
        return List.copyOf(result);
    }

    private static String requireRunId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")) {
            throw new IllegalArgumentException("invalid runId");
        }
        return value;
    }

    private static String requireAcceptanceDatabase(String value) {
        if (value == null || !value.matches("nexion_team_acceptance_[a-z0-9_]{8,64}")) {
            throw new IllegalArgumentException("unsafe acceptance database name");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Account(String role, Long userId, String sponsorRole, String referralCode, String maskedPhone) {
        Account {
            if (!ROLE_ORDER.contains(role)) throw new IllegalArgumentException("unknown role");
            if (userId != null && userId <= 0) throw new IllegalArgumentException("invalid userId");
            if (referralCode != null && !referralCode.matches("[A-Z0-9-]{4,32}")) {
                throw new IllegalArgumentException("invalid referralCode");
            }
        }
    }
}
