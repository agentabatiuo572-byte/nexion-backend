package ffdd.auth.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.MakerCheckerTask;
import ffdd.auth.dto.MakerCheckerCreateRequest;
import ffdd.auth.dto.MakerCheckerReviewRequest;

public interface MakerCheckerService {
    Page<MakerCheckerTask> page(long current, long size, String status, String resourceType);

    MakerCheckerTask create(MakerCheckerCreateRequest request);

    MakerCheckerTask approve(Long id, MakerCheckerReviewRequest request);

    MakerCheckerTask reject(Long id, MakerCheckerReviewRequest request);
}
