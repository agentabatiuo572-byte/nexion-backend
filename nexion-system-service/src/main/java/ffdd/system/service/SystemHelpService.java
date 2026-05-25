package ffdd.system.service;

import ffdd.system.dto.HelpArticleCreateRequest;
import ffdd.system.dto.HelpArticleResponse;
import ffdd.system.dto.HelpArticleUpdateRequest;
import java.util.List;

public interface SystemHelpService {
    List<HelpArticleResponse> list(String query, Integer status, int limit);

    HelpArticleResponse getActiveByCode(String articleCode);

    HelpArticleResponse create(HelpArticleCreateRequest request);

    HelpArticleResponse update(Long id, HelpArticleUpdateRequest request);
}
