package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GenesisHistoryPaginationContractTest {
    @Test
    void everyHistoryUsesBoundedKeysetAndFormalUserIsolation() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/market/mapper/AppGenesisHistoryMapper.java"));
        assertThat(source).contains("id < #{beforeId}", "LIMIT 101", "COALESCE(u.sandbox,0)=0", "u.is_deleted=0");
        assertThat(source).doesNotContain("OFFSET", "${");
        assertThat(source).contains("o.user_id=#{userId}", "i.user_id=#{userId}");
    }
}
