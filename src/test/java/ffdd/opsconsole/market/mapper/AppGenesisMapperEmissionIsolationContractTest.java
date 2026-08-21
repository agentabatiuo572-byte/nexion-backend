package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppGenesisMapperEmissionIsolationContractTest {

    @Test
    void emissionReadsAndWritesCannotCrossIntoSandboxUsers() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/market/mapper/AppGenesisMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains(
                "JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0",
                "JOIN nx_user u ON u.id=i.user_id AND COALESCE(u.sandbox,0)=0",
                "WHERE EXISTS (SELECT 1 FROM nx_user u",
                "COALESCE(u.sandbox,0)=0");
    }
}
