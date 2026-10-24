package ffdd.opsconsole.growth.application;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Parameterized SQL boundary for configured H4 activities. */
@Repository
@RequiredArgsConstructor
public class H4EventRuleRepository {
    private final JdbcTemplate jdbc;
    public List<Map<String,Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> row(String sql,Object... args) {
        var rows=rows(sql,args); return rows.isEmpty()?null:rows.get(0);
    }
    public int write(String sql,Object... args) { return jdbc.update(sql,args); }
}
