package ffdd.system.service;

import ffdd.system.dto.I18nBatchQueryRequest;
import ffdd.system.dto.I18nMessageCreateRequest;
import ffdd.system.dto.I18nMessageResponse;
import ffdd.system.dto.I18nMessageUpdateRequest;
import java.util.List;

public interface SystemI18nService {
    List<I18nMessageResponse> list(String locale, String query, Integer status, int limit);

    I18nMessageResponse getActive(String messageKey, String locale);

    List<I18nMessageResponse> batchGetActive(I18nBatchQueryRequest request);

    I18nMessageResponse create(I18nMessageCreateRequest request);

    I18nMessageResponse update(Long id, I18nMessageUpdateRequest request);
}
