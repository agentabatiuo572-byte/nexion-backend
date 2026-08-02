package ffdd.opsconsole.shared.canonical.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CanonicalStateMapperDeviceCasSqlContractTest {

    @Test
    void activationUsesTheSubmittedVersionInItsCompareAndSetWhereClause() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"),
                StandardCharsets.UTF_8);

        int activation = mapper.indexOf("int activateOwnedDeviceCas");
        assertThat(activation).isGreaterThanOrEqualTo(0);
        String sql = mapper.substring(Math.max(0, mapper.lastIndexOf("@Update", activation)), activation);
        assertThat(sql).contains(
                "row_version = row_version + 1",
                "AND row_version = #{expectedVersion}");
        assertThat(mapper).contains("@Param(\"expectedVersion\") Long expectedVersion");
    }
}
