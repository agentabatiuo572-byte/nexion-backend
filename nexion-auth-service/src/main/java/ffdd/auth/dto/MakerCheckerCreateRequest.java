package ffdd.auth.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MakerCheckerCreateRequest {
    @NotBlank
    private String actionType;
    @NotBlank
    private String resourceType;
    private String resourceId;
    @NotBlank
    private String title;
    private String detail;
    private JsonNode payload;
    @NotBlank
    private String maker;
}
