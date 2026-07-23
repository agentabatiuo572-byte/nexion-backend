package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MybatisMetaObjectHandlerTest {

    @BeforeAll
    static void initializeMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AdminIdempotencyRecordEntity.class);
    }

    @Test
    void insertAndUpdateTimestampsUseTheConfiguredBusinessClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00.900Z"), ZoneId.of("Asia/Shanghai"));
        MybatisMetaObjectHandler handler = new MybatisMetaObjectHandler(clock);
        AdminIdempotencyRecordEntity entity = new AdminIdempotencyRecordEntity();

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 20, 8, 0));
        assertThat(entity.getUpdatedAt()).isEqualTo(entity.getCreatedAt());
        assertThat(entity.getCreatedAt().getNano()).isZero();
    }
}
