package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.ApiResult;

/** Serializable idempotency envelope for mutations without a response body. */
public record CopyVoidMutationResult(int code, String message) {
    public static CopyVoidMutationResult from(ApiResult<Void> result) {
        return new CopyVoidMutationResult(result.getCode(), result.getMessage());
    }

    public ApiResult<Void> toApiResult() {
        return new ApiResult<>(code, message, null);
    }
}
