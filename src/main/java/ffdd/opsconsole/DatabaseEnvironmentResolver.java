package ffdd.opsconsole;

import java.util.Map;
import java.util.Objects;

final class DatabaseEnvironmentResolver {

    static final String DEFAULT_JDBC_URL = "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true"
            + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    static final String DEFAULT_USERNAME = "root";

    private DatabaseEnvironmentResolver() {}

    static ResolvedDatabase resolve(Map<String, String> environment) {
        String nexionUrl = environment.get("NEXION_DB_URL");
        String nexionUsername = environment.get("NEXION_DB_USERNAME");
        String nexionPassword = environment.get("NEXION_DB_PASSWORD");
        String springUrl = environment.get("SPRING_DATASOURCE_URL");
        String springUsername = environment.get("SPRING_DATASOURCE_USERNAME");
        String springPassword = environment.get("SPRING_DATASOURCE_PASSWORD");

        boolean nexionPresent = present(nexionUrl) || present(nexionUsername) || present(nexionPassword);
        boolean springPresent = present(springUrl) || present(springUsername) || present(springPassword);

        if (springPresent && !(present(springUrl) && present(springUsername) && present(springPassword))) {
            throw new IllegalStateException("SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, and "
                    + "SPRING_DATASOURCE_PASSWORD must be supplied as one complete bundle; "
                    + "cross-bundle assembly is forbidden.");
        }

        if (nexionPresent && springPresent) {
            if (!(present(nexionUrl) && present(nexionUsername) && present(nexionPassword))) {
                throw new IllegalStateException("NEXION_DB_URL, NEXION_DB_USERNAME, and NEXION_DB_PASSWORD "
                        + "must be supplied as one complete bundle when legacy SPRING_DATASOURCE_* variables "
                        + "are also present; cross-bundle assembly is forbidden.");
            }
            if (!(Objects.equals(nexionUrl, springUrl)
                    && Objects.equals(nexionUsername, springUsername)
                    && Objects.equals(nexionPassword, springPassword))) {
                throw new IllegalStateException("Refusing conflicting database environment bundles: "
                        + "NEXION_DB_* is authoritative and does not match SPRING_DATASOURCE_*.");
            }
        }

        if (nexionPresent) {
            return new ResolvedDatabase(
                    "NEXION_DB",
                    present(nexionUrl) ? nexionUrl : DEFAULT_JDBC_URL,
                    present(nexionUsername) ? nexionUsername : DEFAULT_USERNAME,
                    nexionPassword == null ? "" : nexionPassword);
        }
        if (springPresent) {
            return new ResolvedDatabase(
                    "SPRING_DATASOURCE_COMPATIBILITY", springUrl, springUsername, springPassword);
        }
        return new ResolvedDatabase("NEXION_DB", DEFAULT_JDBC_URL, DEFAULT_USERNAME, "");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    record ResolvedDatabase(String source, String jdbcUrl, String username, String password) {}
}
