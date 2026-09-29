package com.metricplatform.identity.api;

import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenResponse;
import com.metricplatform.identity.api.IdentityDtos.IdentitySummary;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class PlatformIdentityController {
    private final PlatformAuthenticationService authentication;

    @PostMapping("/token")
    public ResponseEntity<AuthTokenResponse> token(
            @Valid @RequestBody AuthTokenRequest request, HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(authentication.login(request, servletRequest.getRemoteAddr()));
    }

    @GetMapping("/me")
    public IdentitySummary me(@AuthenticationPrincipal PlatformPrincipal principal) {
        return authentication.summary(principal);
    }
}
