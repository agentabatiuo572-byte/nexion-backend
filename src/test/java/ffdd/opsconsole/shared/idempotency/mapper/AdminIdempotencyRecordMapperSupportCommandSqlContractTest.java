package ffdd.opsconsole.shared.idempotency.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminIdempotencyRecordMapperSupportCommandSqlContractTest {
    @Test
    void supportRecoveryLookupBindsTheOpaqueKeyAndEveryAllowedScope() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/shared/idempotency/mapper/AdminIdempotencyRecordMapper.java"));

        assertThat(source).contains("selectSupportCommand")
                .contains("idempotency_key = #{idempotencyKey}")
                .contains("AND scope IN")
                .contains("#{scope}")
                .doesNotContain("${idempotencyKey}")
                .doesNotContain("${scope}");
    }
}
