package ffdd.opsconsole.shared.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisMetaObjectHandler implements MetaObjectHandler {
    private final Clock clock;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }
}
