package com.metricplatform.environment.api;

import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.environment.EnvironmentService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 环境配置入口，所有操作使用现有平台登录身份。 */
@RestController
@RequestMapping("/api/v4/dbt/environments")
public class EnvironmentController {
    /** 平台环境用例。 */
    private final EnvironmentService environments;

    public EnvironmentController(EnvironmentService environments) {
        this.environments = environments;
    }

    @GetMapping
    public EnvironmentPage list() {
        return environments.list();
    }

    @GetMapping("/{id}")
    public EnvironmentView get(@PathVariable UUID id) {
        return environments.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentView create(@RequestBody EnvironmentInput input) {
        return environments.create(input);
    }

    @PutMapping("/{id}")
    public EnvironmentView update(
            @PathVariable UUID id,
            @RequestParam long expectedConfigVersion,
            @RequestBody EnvironmentInput input) {
        return environments.update(id, expectedConfigVersion, input);
    }
}
