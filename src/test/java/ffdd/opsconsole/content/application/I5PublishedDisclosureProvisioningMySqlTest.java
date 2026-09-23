package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * BUG #60: six jurisdiction mappings referenced a version_label with no
 * nx_disclosure_draft row, so AppRiskDisclosureService.loadCurrent always
 * returned RISK_DISCLOSURE_PUBLISHED_VERSION_NOT_FOUND and the App risk-disclosure
 * page could never render a body. This fixture reproduces that exact database
 * state (mapping present, draft absent) and proves the startup migration makes
 * every mapping resolvable while leaving operator-owned rows alone.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_I5_PROVISIONING_IT", matches = "true")
class I5PublishedDisclosureProvisioningMySqlTest {

    private static final Path MIGRATION = Path.of(
            "scripts/migrations/20260920_i5_published_disclosure_provisioning.sql");
    private static final List<String> JURISDICTIONS = List.of("CN", "US", "EU", "SG", "SBV");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    @Test
    void brokenMatrixMappingsBecomeReadableAndOperatorVersionsAreNeverOverwritten() throws Exception {
        String schema = "nexion_i5_prov_it_" + UUID.randomUUID().toString().replace("-", "");
        String base = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL", "jdbc:mysql://127.0.0.1:3306/");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        try (Connection admin = DriverManager.getConnection(base + OPTIONS, username, password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection connection = DriverManager.getConnection(base + schema + OPTIONS, username, password)) {
                createI5Tables(connection);
                seedBrokenMatrix(connection);
                seedOperatorOwnedJurisdiction(connection);

                String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);
                executeScript(connection, migration);
                // Rerunnable: a second startup pass must not duplicate or mutate rows.
                executeScript(connection, migration);

                // Every App-resolvable jurisdiction now has exactly one published draft
                // with all seven chapters in every language the scope promises.
                for (String code : JURISDICTIONS) {
                    assertThat(publishedVersion(connection, code))
                            .as("published version for %s", code)
                            .isEqualTo("v1");
                    assertThat(chapterCount(connection, code, "v1")).as("chapters for %s", code).isEqualTo(7);
                    assertThat(blankChapterBodies(connection, code, "v1"))
                            .as("blank localized chapter bodies for %s", code).isEmpty();
                    assertThat(contentHashLength(connection, code, "v1")).as("content hash for %s", code).isEqualTo(64);
                    assertThat(countryCodes(connection, code)).as("routing for %s", code).isNotBlank();
                }
                assertThat(duplicatePublishedDrafts(connection)).as("single published slot per jurisdiction").isEmpty();

                // Operator-owned content is authoritative: the existing v3 publication
                // and its chapters must survive untouched.
                assertThat(publishedVersion(connection, "SFC")).isEqualTo("v3");
                assertThat(chapterCount(connection, "SFC", "v3")).isEqualTo(7);
                assertThat(draftCount(connection, "SFC")).as("no extra draft provisioned for SFC").isEqualTo(1);

                // A mapping that already points at a readable version keeps that version.
                assertThat(mappingVersion(connection, "US")).isEqualTo("v1");
            } finally {
                admin.createStatement().execute("DROP DATABASE " + schema);
            }
        }
    }

    /**
     * zentao #60 的残留那一半:沙箱夹具行泄漏进共享库后,一直指着规范迁移不提供的
     * 'v-local-1'。退役迁移必须清掉它,同时**绝不**碰运营内容。
     */
    @Test
    void leakedLocalSandboxFixtureIsRetiredWithoutTouchingOperatorContent() throws Exception {
        Path retirement = Path.of(
                "scripts/migrations/20260921_i5_local_sandbox_fixture_retirement.sql");
        String schema = "nexion_i5_sandbox_it_" + UUID.randomUUID().toString().replace("-", "");
        String base = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL", "jdbc:mysql://127.0.0.1:3306/");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        try (Connection admin = DriverManager.getConnection(base + OPTIONS, username, password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection connection = DriverManager.getConnection(base + schema + OPTIONS, username, password)) {
                createI5Tables(connection);
                // 规范辖区先就绪(模拟 provisioning 迁移已跑过)。
                executeScript(connection, Files.readString(MIGRATION, StandardCharsets.UTF_8));
                seedOperatorOwnedJurisdiction(connection);
                seedLeakedLocalSandboxFixture(connection);

                String script = Files.readString(retirement, StandardCharsets.UTF_8);
                executeScript(connection, script);
                // 可重跑:第二次启动不得再改任何行。
                executeScript(connection, script);

                // 夹具行退出读路径:矩阵行、目录行、草稿、章节全部不再可见。
                assertThat(mappingVersion(connection, "LOCAL-SANDBOX"))
                        .as("fixture matrix row must leave the read path").isNull();
                assertThat(catalogStatus(connection, "LOCAL-SANDBOX"))
                        .as("fixture catalog entry must be archived").isEqualTo("ARCHIVED");
                assertThat(draftCount(connection, "LOCAL-SANDBOX")).as("fixture draft retired").isZero();
                assertThat(chapterCount(connection, "LOCAL-SANDBOX", "v-local-1")).as("fixture chapters retired").isZero();

                // 运营内容一字未动。
                assertThat(publishedVersion(connection, "SFC")).isEqualTo("v3");
                assertThat(chapterCount(connection, "SFC", "v3")).isEqualTo(7);
                assertThat(draftCount(connection, "SFC")).isEqualTo(1);
                assertThat(mappingVersion(connection, "SFC")).isEqualTo("v3");
                assertThat(catalogStatus(connection, "SFC")).isEqualTo("ACTIVE");
                // 规范辖区仍可读。
                assertThat(publishedVersion(connection, "CN")).isEqualTo("v1");

                // Any single operator takeover must preserve the whole group,
                // including a catalog row whose own operator marker stayed stale.
                for (String table : List.of("nx_disclosure_jurisdiction", "nx_disclosure_jurisdiction_catalog",
                        "nx_disclosure_draft", "nx_disclosure_chapter")) {
                    restoreLocalSandboxFixture(connection);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("UPDATE " + table + " SET last_operator = 'operator' "
                                + "WHERE jurisdiction_code = 'LOCAL-SANDBOX'"
                                + (table.equals("nx_disclosure_chapter") ? " AND chapter_no = '01'" : ""));
                    }
                    executeScript(connection, script);
                    assertThat(mappingVersion(connection, "LOCAL-SANDBOX")).as(table).isEqualTo("v-local-1");
                    assertThat(catalogStatus(connection, "LOCAL-SANDBOX")).as(table).isEqualTo("ACTIVE");
                    assertThat(draftCount(connection, "LOCAL-SANDBOX")).as(table).isEqualTo(1);
                    assertThat(chapterCount(connection, "LOCAL-SANDBOX", "v-local-1")).as(table).isEqualTo(7);
                }
            } finally {
                admin.createStatement().execute("DROP DATABASE " + schema);
            }
        }
    }

    /** 沙箱夹具泄漏后的库内状态:辖区/目录/草稿/章节四表都有它,版本号是 v-local-1。 */
    private void seedLeakedLocalSandboxFixture(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO nx_disclosure_jurisdiction_catalog
                      (jurisdiction_code, jurisdiction_name, status, revision, last_operator, is_deleted)
                    VALUES ('LOCAL-SANDBOX','本地沙箱风险披露','ACTIVE',1,
                            'local-sandbox:risk-disclosure-fixture',0)
                    """);
            statement.execute("""
                    INSERT INTO nx_disclosure_jurisdiction
                      (jurisdiction_code, jurisdiction_name, country_codes, version_label, status,
                       published_at_label, affected_count, ack_progress_pct, blocked_count, last_operator, is_deleted)
                    VALUES ('LOCAL-SANDBOX','本地沙箱风险披露','LOCAL-SANDBOX','v-local-1','PUBLISHED',
                            '07-01',0,0,0,'local-sandbox:risk-disclosure-fixture',0)
                    """);
            statement.execute("""
                    INSERT INTO nx_disclosure_draft
                      (jurisdiction_code, version_label, language_scope, effective_date, requires_reack,
                       zh_body, vi_body, en_body, status, revision, content_hash, last_operator, is_deleted)
                    VALUES ('LOCAL-SANDBOX','v-local-1','zh+vi+en','2026-07-01',1,
                            '本地沙箱演示风险披露','Công bố rủi ro sandbox','Local sandbox disclosure',
                            'PUBLISHED',1,'local-sandbox-fixture','local-sandbox:risk-disclosure-fixture',0)
                    """);
            for (int chapter = 1; chapter <= 7; chapter++) {
                String no = String.format("%02d", chapter);
                statement.execute("""
                        INSERT INTO nx_disclosure_chapter
                          (jurisdiction_code, version_label, chapter_no, zh_title, vi_title, en_title,
                           zh_body, vi_body, en_body, sort_order, last_operator, is_deleted)
                        VALUES ('LOCAL-SANDBOX','v-local-1','%s','演示性质','Trình diễn','Demo',
                                '沙箱演示','Sandbox','Sandbox demo',%d,'local-sandbox:risk-disclosure-fixture',0)
                        """.formatted(no, chapter));
            }
        }
    }

    private void restoreLocalSandboxFixture(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String table : List.of("nx_disclosure_jurisdiction", "nx_disclosure_draft",
                    "nx_disclosure_chapter")) {
                statement.execute("UPDATE " + table + " SET is_deleted = 0, "
                        + "last_operator = 'local-sandbox:risk-disclosure-fixture' "
                        + "WHERE jurisdiction_code = 'LOCAL-SANDBOX'");
            }
            statement.execute("""
                    UPDATE nx_disclosure_jurisdiction_catalog
                       SET status = 'ACTIVE', last_operator = 'local-sandbox:risk-disclosure-fixture'
                     WHERE jurisdiction_code = 'LOCAL-SANDBOX'
                    """);
        }
    }

    private void createI5Tables(Connection connection) throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);
        for (String table : List.of("nx_disclosure_jurisdiction_catalog", "nx_disclosure_jurisdiction",
                "nx_disclosure_chapter", "nx_disclosure_draft")) {
            String ddl = createTableBlock(schema, table);
            if (ddl == null) throw new IllegalStateException("schema.sql is missing " + table);
            connection.createStatement().execute(ddl);
        }
    }

    private String createTableBlock(String schema, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table + " (";
        int start = schema.indexOf(marker);
        if (start < 0) return null;
        int end = schema.indexOf("ENGINE=", start);
        if (end < 0) return null;
        int terminator = schema.indexOf(';', end);
        return schema.substring(start, terminator + 1);
    }

    /** The reproduced defect: published mappings whose referenced draft does not exist. */
    private void seedBrokenMatrix(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO nx_disclosure_jurisdiction_catalog
                      (jurisdiction_code, jurisdiction_name, status, revision, last_operator, is_deleted)
                    VALUES
                      ('CN','中国大陆','ACTIVE',1,'seed',0),
                      ('US','美国','ACTIVE',1,'seed',0),
                      ('EU','欧盟','ACTIVE',1,'seed',0),
                      ('SG','新加坡','ACTIVE',1,'seed',0),
                      ('SBV','越南国家银行','ACTIVE',1,'seed',0)
                    """);
            statement.execute("""
                    INSERT INTO nx_disclosure_jurisdiction
                      (jurisdiction_code, jurisdiction_name, country_codes, version_label, status,
                       published_at_label, affected_count, ack_progress_pct, blocked_count, last_operator, is_deleted)
                    VALUES
                      ('CN','中国大陆','','v4','PUBLISHED','07-01',0,0,0,'seed',0),
                      ('US','美国','','v4','PUBLISHED','07-01',0,0,0,'seed',0),
                      ('EU','欧盟','','v4','PUBLISHED','07-01',0,0,0,'seed',0),
                      ('SG','新加坡','','v4','PUBLISHED','07-01',0,0,0,'seed',0),
                      ('SBV','越南国家银行','','v4','PUBLISHED','07-01',0,0,0,'seed',0)
                    """);
        }
    }

    /** Operator-owned jurisdiction with a readable publication the migration must not touch. */
    private void seedOperatorOwnedJurisdiction(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO nx_disclosure_jurisdiction_catalog
                      (jurisdiction_code, jurisdiction_name, status, revision, last_operator, is_deleted)
                    VALUES ('SFC','香港','ACTIVE',1,'operator',0)
                    """);
            statement.execute("""
                    INSERT INTO nx_disclosure_jurisdiction
                      (jurisdiction_code, jurisdiction_name, country_codes, version_label, status,
                       published_at_label, affected_count, ack_progress_pct, blocked_count, last_operator, is_deleted)
                    VALUES ('SFC','香港','HK','v3','PUBLISHED','06-01',0,0,0,'operator',0)
                    """);
            statement.execute("""
                    INSERT INTO nx_disclosure_draft
                      (jurisdiction_code, version_label, language_scope, effective_date, requires_reack,
                       zh_body, vi_body, en_body, status, revision, content_hash, last_operator, is_deleted)
                    VALUES ('SFC','v3','zh+vi+en','2026-06-01',1,'运营正文','Nội dung','Operator body',
                            'PUBLISHED',4,'operator-hash','operator',0)
                    """);
            for (int chapter = 1; chapter <= 7; chapter++) {
                String no = String.format("%02d", chapter);
                statement.execute("""
                        INSERT INTO nx_disclosure_chapter
                          (jurisdiction_code, version_label, chapter_no, zh_title, vi_title, en_title,
                           zh_body, vi_body, en_body, sort_order, last_operator, is_deleted)
                        VALUES ('SFC','v3','%s','运营标题','Tiêu đề','Operator title','运营正文','Nội dung','Operator body',
                                %d,'operator',0)
                        """.formatted(no, chapter));
            }
        }
    }

    private void executeScript(Connection connection, String script) throws Exception {
        // The migration is plain DDL-free SQL: strip line comments, then split on ';'
        // outside of string literals (apostrophes inside comments would otherwise
        // desynchronize the literal state).
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\r?\n")) {
            int comment = line.indexOf("--");
            cleaned.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        StringBuilder statement = new StringBuilder();
        boolean inLiteral = false;
        try (Statement executor = connection.createStatement()) {
            String body = cleaned.toString();
            for (int index = 0; index < body.length(); index++) {
                char current = body.charAt(index);
                if (current == '\'') inLiteral = !inLiteral;
                if (current == ';' && !inLiteral) {
                    String sql = statement.toString().trim();
                    statement.setLength(0);
                    if (!sql.isEmpty()) executor.execute(sql);
                    continue;
                }
                statement.append(current);
            }
            String tail = statement.toString().trim();
            if (!tail.isEmpty()) executor.execute(tail);
        }
    }

    private String publishedVersion(Connection connection, String jurisdiction) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version_label FROM nx_disclosure_draft
                      WHERE jurisdiction_code = '%s' AND status = 'PUBLISHED' AND is_deleted = 0
                     """.formatted(jurisdiction))) {
            List<String> versions = new ArrayList<>();
            while (rows.next()) versions.add(rows.getString(1));
            assertThat(versions).as("exactly one published draft").hasSize(1);
            return versions.get(0);
        }
    }

    private String mappingVersion(Connection connection, String jurisdiction) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version_label FROM nx_disclosure_jurisdiction
                      WHERE jurisdiction_code = '%s' AND is_deleted = 0
                     """.formatted(jurisdiction))) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    /** 辖区目录行的状态;夹具退役后应为 ARCHIVED。 */
    private String catalogStatus(Connection connection, String jurisdiction) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT status FROM nx_disclosure_jurisdiction_catalog
                      WHERE jurisdiction_code = '%s' AND is_deleted = 0
                     """.formatted(jurisdiction))) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private String countryCodes(Connection connection, String jurisdiction) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT country_codes FROM nx_disclosure_jurisdiction
                      WHERE jurisdiction_code = '%s' AND is_deleted = 0
                     """.formatted(jurisdiction))) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private int chapterCount(Connection connection, String jurisdiction, String version) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT COUNT(*) FROM nx_disclosure_chapter
                      WHERE jurisdiction_code = '%s' AND version_label = '%s' AND is_deleted = 0
                     """.formatted(jurisdiction, version))) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    /** A scope of zh+vi+en promises three languages; every chapter must carry all three. */
    private Map<String, String> blankChapterBodies(Connection connection, String jurisdiction, String version)
            throws Exception {
        Map<String, String> blanks = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT chapter_no, zh_body, vi_body, en_body FROM nx_disclosure_chapter
                      WHERE jurisdiction_code = '%s' AND version_label = '%s' AND is_deleted = 0
                      ORDER BY sort_order
                     """.formatted(jurisdiction, version))) {
            while (rows.next()) {
                for (String column : List.of("zh_body", "vi_body", "en_body")) {
                    String value = rows.getString(column);
                    if (value == null || value.isBlank()) {
                        blanks.put(rows.getString(1), column);
                    }
                }
            }
        }
        return blanks;
    }

    private int contentHashLength(Connection connection, String jurisdiction, String version) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT CHAR_LENGTH(COALESCE(content_hash,'')) FROM nx_disclosure_draft
                      WHERE jurisdiction_code = '%s' AND version_label = '%s' AND is_deleted = 0
                     """.formatted(jurisdiction, version))) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private int draftCount(Connection connection, String jurisdiction) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT COUNT(*) FROM nx_disclosure_draft
                      WHERE jurisdiction_code = '%s' AND is_deleted = 0
                     """.formatted(jurisdiction))) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    /** uk_disclosure_single_published already enforces this; assert it held for every repaired row. */
    private List<String> duplicatePublishedDrafts(Connection connection) throws Exception {
        List<String> duplicates = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT jurisdiction_code FROM nx_disclosure_draft
                      WHERE status = 'PUBLISHED' AND is_deleted = 0
                      GROUP BY jurisdiction_code HAVING COUNT(*) > 1
                     """)) {
            while (rows.next()) duplicates.add(rows.getString(1));
        }
        return duplicates;
    }
}
