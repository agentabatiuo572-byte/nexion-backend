package ffdd.opsconsole.shared.api;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private long total;
    private long pageNum;
    private long pageSize;
    private List<T> records;
}

