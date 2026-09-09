package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class DayOneInstanceMapperAssemblyTest {

    @Test
    void registersTheDynamicSnapshotBindingStatementWithoutOpeningADatabaseConnection() {
        Configuration configuration = new Configuration();

        assertThatCode(() -> {
            configuration.addMapper(DayOneInstanceMapper.class);
            configuration.addMapper(AppGrowthEngagementMapper.class);
        }).doesNotThrowAnyException();

        assertThat(configuration.hasStatement(
                "ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.listInWindowSnapshotBindings"))
                .isTrue();
        assertThat(configuration.hasStatement(
                "ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.dayOneSnapshotState"))
                .isTrue();
    }
}
