package ffdd.opsconsole.shared.canonical.mapper;

import org.apache.ibatis.annotations.Select;

/** Resolves the original standard S1 across the explicit catalog cutover. */
public interface StandardTrialSkuMapper {
    String STANDARD_S1_SQL = "SELECT IF(EXISTS(SELECT 1 FROM nx_config_item "
            + "WHERE config_key='E.catalog.b140.numbered' AND config_value='true' AND is_deleted=0), "
            + "'e-s1-002', 'stellarbox-s1')";

    @Select(STANDARD_S1_SQL)
    String standardS1ProductNo();
}
