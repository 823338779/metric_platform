package com.metricplatform.environment.api;

import com.metricplatform.environment.RepositorySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RepositoryController {
    private final RepositorySource source;

    public RepositoryController(RepositorySource source) {
        this.source = source;
    }

    @GetMapping("/api/dbt/repository/branches")
    public RepositorySource.Branches branches() {
        return new RepositorySource.Branches(source.repository(), source.branches());
    }
}
