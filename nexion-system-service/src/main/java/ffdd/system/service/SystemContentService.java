package ffdd.system.service;

import ffdd.system.dto.ContentPageCreateRequest;
import ffdd.system.dto.ContentPageResponse;
import ffdd.system.dto.ContentPageUpdateRequest;
import java.util.List;

public interface SystemContentService {
    List<ContentPageResponse> list(String query, Integer status, int limit);

    ContentPageResponse getActiveByCode(String pageCode);

    ContentPageResponse create(ContentPageCreateRequest request);

    ContentPageResponse update(Long id, ContentPageUpdateRequest request);
}
