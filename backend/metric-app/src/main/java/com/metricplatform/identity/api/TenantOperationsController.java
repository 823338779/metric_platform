package com.metricplatform.identity.api;

import com.metricplatform.identity.TenantAccountService;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class TenantOperationsController {
    public static final String PATH = "/api/ops/tenants";
    private static final String TENANT_ID = "tenant_id";
    private final TenantAccountService accounts;

    /** 运维身份由独立安全链校验。 */
    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> provision(@Valid @RequestBody AuthTokenRequest input) {
        return Map.of(TENANT_ID, accounts.provision(input));
    }
}
