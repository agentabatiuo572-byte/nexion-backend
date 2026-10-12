package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromotionSnapshotCleanupTest {
    @Configuration @EnableScheduling static class Scheduling {}

    @Test void springSchedulesTheCleanupWithoutAListRequest() throws Exception {
        var db=mock(PromotionMapper.class);var executed=new CountDownLatch(2);
        when(db.write(anyString(),any(Object[].class))).thenAnswer(call->{executed.countDown();return 0;});
        try(var context=new AnnotationConfigApplicationContext()){
            context.registerBean(PromotionMapper.class,()->db);
            context.register(Scheduling.class,PromotionSnapshotCleanup.class);context.refresh();
            assertTrue(executed.await(2,TimeUnit.SECONDS),"Idle instances must reclaim expired lists too");
        }
    }
}
