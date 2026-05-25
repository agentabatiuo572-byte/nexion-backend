package ffdd.system.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class I18nMessageResponse {
    private Long id;
    private String messageKey;
    private String locale;
    private String messageValue;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
