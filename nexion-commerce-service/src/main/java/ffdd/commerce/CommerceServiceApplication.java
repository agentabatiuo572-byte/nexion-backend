package ffdd.commerce;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("ffdd.commerce.mapper")
@EnableScheduling
@EnableFeignClients(basePackages = "ffdd.commerce.client")
@SpringBootApplication(scanBasePackages = {"ffdd.commerce", "ffdd.common"})
public class CommerceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommerceServiceApplication.class, args);
    }
}
