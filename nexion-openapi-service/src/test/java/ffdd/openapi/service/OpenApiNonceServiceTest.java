package ffdd.openapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.common.exception.BizException;
import ffdd.openapi.domain.OpenApiNonce;
import ffdd.openapi.mapper.OpenApiNonceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class OpenApiNonceServiceTest {
    private final OpenApiNonceMapper nonceMapper = mock(OpenApiNonceMapper.class);
    private final OpenApiNonceService nonceService = new OpenApiNonceService(nonceMapper, 300);

    @Test
    void rejectsDuplicateNonceWithinTtl() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(nonceMapper)
                .insert(any(OpenApiNonce.class));

        assertThatThrownBy(() -> nonceService.claim("nxak_test", "nonce-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("nonce already used");
    }

    @Test
    void purgesExpiredNonceBeforeClaiming() {
        nonceService.claim("nxak_test", "nonce-2");

        verify(nonceMapper).delete(any(Wrapper.class));
        verify(nonceMapper).insert(any(OpenApiNonce.class));
    }
}
