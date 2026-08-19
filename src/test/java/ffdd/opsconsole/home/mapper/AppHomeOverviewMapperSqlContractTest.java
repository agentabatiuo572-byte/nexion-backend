package ffdd.opsconsole.home.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppHomeOverviewMapperSqlContractTest {
    @Test
    void accountFactsCarryUserAndEnvironmentBoundaries() throws Exception {
        String earnings = select("earnings");
        String grid = select("onGrid");
        String devices = select("onGridClients");
        assertTrue(earnings.contains("user_id = #{userId}"));
        assertTrue(earnings.contains("source_environment"));
        assertTrue(grid.contains("source_environment"));
        assertTrue(grid.contains("u.sandbox = #{sandbox}"));
        assertTrue(devices.contains("u.sandbox = #{sandbox}"));
        assertTrue(devices.contains("SHA2("));
        assertTrue(!devices.contains("u.email"));
        assertTrue(!devices.contains("u.phone"));
    }

    private String select(String name) throws Exception {
        Method method = java.util.Arrays.stream(AppHomeOverviewMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        return method.getAnnotation(Select.class).value()[0];
    }
}
