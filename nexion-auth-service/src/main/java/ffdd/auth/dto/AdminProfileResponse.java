package ffdd.auth.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminProfileResponse {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private Integer superAdmin;
    private Integer status;
    private List<Long> roleIds;
    private List<String> authorities;
    private List<String> menuPaths;
    private List<AdminMenuResponse> menus;
}
