package ffdd.compliance;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@MapperScan("ffdd.compliance.mapper")
@EnableFeignClients(basePackages = "ffdd.compliance.client")
@SpringBootApplication(scanBasePackages = {"ffdd.compliance", "ffdd.common"})
public class ComplianceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComplianceServiceApplication.class, args);
    }
}
