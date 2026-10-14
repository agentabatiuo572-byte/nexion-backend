package ffdd.opsconsole.content.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import ffdd.opsconsole.shared.exception.BizException;

public final class SupportAvatarRequests {
    private SupportAvatarRequests() {}
    public record DefaultPrepare(String avatarGender,String clientUploadId) {
        @JsonAnySetter public void reject(String field,Object value){throw new BizException(422,"AVATAR_COMMAND_INVALID");}
    }
    public record SelfSave(String assetId,String expectedVersion,String reason) {
        @JsonAnySetter public void reject(String field,Object value){throw new BizException(422,"AVATAR_COMMAND_INVALID");}
    }
}
