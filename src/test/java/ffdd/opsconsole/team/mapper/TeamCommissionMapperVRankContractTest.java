package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeamCommissionMapperVRankContractTest {
    @Test
    void rankEvaluationSerializesOnTheCanonicalSelfLoopRow() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/mapper/TeamCommissionMapper.java"));
        int method = source.indexOf("String currentMemberVRank");
        int query = source.lastIndexOf("@Select", method);
        String contract = source.substring(query, method);

        assertThat(contract)
                .contains("member_user_id = #{userId}")
                .contains("FOR UPDATE");
    }
}
