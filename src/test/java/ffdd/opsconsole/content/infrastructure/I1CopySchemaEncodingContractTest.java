package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class I1CopySchemaEncodingContractTest {

    private static final String SET_NAMES = "SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;";

    @Test
    void i1VersionCatalogSqlDeclaresUtf8AndRepairsOnlyUntouchedSeedRowsWithUtf8Hex() throws IOException {
        assertEncodingContract("scripts/migrations/20260712_i1_copy_schema.sql");
        assertEncodingContract("scripts/migrations/20260712_i1_copy_version_encoding_fix.sql");
        assertEncodingContract("scripts/schema.sql");
    }

    private void assertEncodingContract(String relativePath) throws IOException {
        String sql = Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);

        assertThat(sql).contains(SET_NAMES);
        assertThat(sql).contains("last_operator IN ('migration', 'schema')");
        assertThat(sql).contains("AND is_deleted = 0", "AND revision = 1");
        assertThat(sql).contains(
                "CONVERT(0xE78988E69CAC207631 USING utf8mb4)",
                "CONVERT(0xE78988E69CAC207632 USING utf8mb4)",
                "CONVERT(0xE78988E69CAC207633 USING utf8mb4)",
                "CONVERT(0xE78988E69CAC207634 USING utf8mb4)",
                "CONVERT(0xE78988E69CAC207635 USING utf8mb4)",
                "CONVERT(0xE5889DE5A78BE69687E6A188E78988E69CAC USING utf8mb4)",
                "CONVERT(0xE7ACACE4BA8CE78988E69687E6A188 USING utf8mb4)",
                "CONVERT(0xE7ACACE4B889E78988E69687E6A188 USING utf8mb4)",
                "CONVERT(0xE7ACACE59B9BE78988E69687E6A188 USING utf8mb4)",
                "CONVERT(0xE7ACACE4BA94E78988E69687E6A188 USING utf8mb4)");
    }
}
