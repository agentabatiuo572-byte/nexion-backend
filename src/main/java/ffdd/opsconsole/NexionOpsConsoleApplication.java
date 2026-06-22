package ffdd.opsconsole;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("ffdd.opsconsole.**.mapper")
public class NexionOpsConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(NexionOpsConsoleApplication.class, args);
    }
}
