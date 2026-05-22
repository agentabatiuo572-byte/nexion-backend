package ffdd.mission;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("ffdd.mission.mapper")
@SpringBootApplication(scanBasePackages = {"ffdd.mission", "ffdd.common"})
public class MissionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MissionServiceApplication.class, args);
    }
}
