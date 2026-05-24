package ffdd.openapi.service;

import java.time.LocalDate;

public interface OpenApiQuotaCounter {
    long incrementQps(String appKey, String apiPath);

    long incrementDaily(String appKey, String apiPath, LocalDate date);
}
