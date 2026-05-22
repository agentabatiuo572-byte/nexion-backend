package ffdd.openapi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@MapperScan("ffdd.openapi.mapper")
@SpringBootApplication(scanBasePackages = {"ffdd.openapi", "ffdd.common"})
public class OpenApiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenApiServiceApplication.class, args);
    }
}
