package ffdd.team;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("ffdd.team.mapper")
@EnableScheduling
@EnableFeignClients(basePackages = "ffdd.team.client")
@SpringBootApplication(scanBasePackages = {"ffdd.team", "ffdd.common"})
public class TeamServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TeamServiceApplication.class, args);
    }
}
