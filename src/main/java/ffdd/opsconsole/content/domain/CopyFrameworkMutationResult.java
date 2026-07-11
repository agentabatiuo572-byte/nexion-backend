package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.ApiResult;

/** Serializable idempotency envelope for I1 experiment-framework mutations. */
public record CopyFrameworkMutationResult(int code, String message, CopyFrameworkParamView data) {
    public static CopyFrameworkMutationResult from(ApiResult<CopyFrameworkParamView> result) {
        return new CopyFrameworkMutationResult(result.getCode(), result.getMessage(), result.getData());
    }

    public ApiResult<CopyFrameworkParamView> toApiResult() {
        return new ApiResult<>(code, message, data);
    }
}
