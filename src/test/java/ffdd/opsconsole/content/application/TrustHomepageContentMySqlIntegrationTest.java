package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TrustHomepageContentMySqlIntegrationTest {

    private static final List<String> SECTION_KEYS = List.of("complianceBadges", "auditsReserves");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void currentPublishedSnapshotAndPcFieldProjectionStayInSync() throws Exception {
        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection()) {
            Map<String, Map<String, String>> canonicalBySection = currentCanonicalFields(connection);

            assertThat(canonicalBySection).containsOnlyKeys(SECTION_KEYS);
            assertThat(canonicalBySection.get("complianceBadges"))
                    .containsKeys("badge1Label", "badge2Label", "badge3Label", "badge4Label",
                            "badge5Label", "badge6Label", "badge7Label")
                    .allSatisfy((key, value) -> {
                        if (key.matches("badge[1-7]Label")) assertThat(value).isNotBlank();
                    });
            assertThat(canonicalBySection.get("auditsReserves"))
                    .containsKeys("homepageProof.zh", "homepageProof.vi", "homepageProof.en");
            for (String language : List.of("zh", "vi", "en")) {
                assertThat(canonicalBySection.get("auditsReserves").get("homepageProof." + language))
                        .isNotBlank();
            }

            for (String sectionKey : SECTION_KEYS) {
                assertThat(currentFieldProjection(connection, sectionKey))
                        .isEqualTo(canonicalBySection.get(sectionKey));
            }
        }
    }

    private Map<String, Map<String, String>> currentCanonicalFields(Connection connection) throws Exception {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.section_key, v.fields_json
                  FROM nx_trust_section s
                  JOIN nx_trust_section_version v
                    ON v.section_key COLLATE utf8mb4_unicode_ci = s.section_key COLLATE utf8mb4_unicode_ci
                   AND v.version_label COLLATE utf8mb4_unicode_ci = s.version_label COLLATE utf8mb4_unicode_ci
                   AND v.is_deleted = 0
                 WHERE s.section_key IN ('complianceBadges', 'auditsReserves')
                   AND s.status = 'PUBLISHED'
                   AND v.status = 'PUBLISHED'
                   AND s.is_deleted = 0
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<Map<String, Object>> fields = JSON.readValue(
                            rows.getString("fields_json"), new TypeReference<>() {});
                    Map<String, String> values = new LinkedHashMap<>();
                    for (Map<String, Object> field : fields) {
                        values.put(String.valueOf(field.get("key")), String.valueOf(field.get("value")));
                    }
                    result.put(rows.getString("section_key"), values);
                }
            }
        }
        return result;
    }

    private Map<String, String> currentFieldProjection(Connection connection, String sectionKey) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT field_key, field_value
                  FROM nx_trust_section_field
                 WHERE section_key = ? AND is_deleted = 0
                 ORDER BY sort_order, id
                """)) {
            statement.setString(1, sectionKey);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString("field_key"), rows.getString("field_value"));
            }
        }
        return result;
    }

    private DataSource dataSource() {
        String url = System.getenv().getOrDefault(
                "NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion"
                        + "?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false"
                        + "&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        return new DriverManagerDataSource(url, username, System.getenv("NEXION_TEST_DB_PASSWORD"));
    }
}
