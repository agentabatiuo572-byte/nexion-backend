package ffdd.commerce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.commerce.domain.TradeinRule;
import ffdd.commerce.mapper.TradeinRuleMapper;
import ffdd.common.api.ApiResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
public class TradeinConfigController {
    private final TradeinRuleMapper tradeinRuleMapper;

    public TradeinConfigController(TradeinRuleMapper tradeinRuleMapper) {
        this.tradeinRuleMapper = tradeinRuleMapper;
    }

    @GetMapping("/tradein")
    public ApiResult<Map<String, Object>> tradein() {
        return ApiResult.ok(Map.of("rules", tradeinRuleMapper.selectList(new LambdaQueryWrapper<TradeinRule>()
                .eq(TradeinRule::getIsDeleted, 0)
                .eq(TradeinRule::getStatus, 1)
                .orderByDesc(TradeinRule::getSortOrder)
                .orderByAsc(TradeinRule::getId))));
    }
}
