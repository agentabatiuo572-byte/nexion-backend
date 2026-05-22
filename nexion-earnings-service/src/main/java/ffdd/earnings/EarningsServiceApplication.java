package ffdd.earnings;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@MapperScan("ffdd.earnings.mapper")
@EnableFeignClients(basePackages = "ffdd.earnings.client")
@SpringBootApplication(scanBasePackages = {"ffdd.earnings", "ffdd.common"})
public class EarningsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EarningsServiceApplication.class, args);
    }
}
