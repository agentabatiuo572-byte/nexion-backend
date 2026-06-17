package ffdd.opsconsole;

import ffdd.opsconsole.shared.audit.AuditLogController;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("ffdd.opsconsole.**.mapper")
@ComponentScan(
        basePackages = {"ffdd.opsconsole"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuditLogController.class))
public class NexionOpsConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(NexionOpsConsoleApplication.class, args);
    }
}
