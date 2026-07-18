package com.metricplatform.source;

import com.metricplatform.source.DataSourceDtos.ConnectionSettingsRequest;
import com.metricplatform.source.DataSourceDtos.ConnectionTestResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DataSourceConnectionController {
    private final DataSourceManagementService management;

    @PostMapping("/api/v1/data-sources:validate-connection")
    public ConnectionTestResponse validate(@Valid @RequestBody ConnectionSettingsRequest request) {
        return management.validateConnection(request);
    }
}
