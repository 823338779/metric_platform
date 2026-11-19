package com.metricplatform.identity.api;

import com.metricplatform.identity.TenantAccountService;
import com.metricplatform.identity.api.IdentityDtos.*;
import com.metricplatform.security.PlatformPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class TenantAccountController {
    private static final String ACCOUNTS = "/api/v1/tenant/accounts";
    private final TenantAccountService service;

    @PostMapping(ACCOUNTS)
    @ResponseStatus(HttpStatus.CREATED)
    public IdentitySummary create(
            @AuthenticationPrincipal PlatformPrincipal actor,
            @Valid @RequestBody AuthTokenRequest input) {
        return service.createReader(actor, input);
    }
}
