package com.metricplatform.identity;

import com.metricplatform.identity.IdentityDtos.CreatePlatformUserRequest;
import com.metricplatform.identity.IdentityDtos.DatasetGrantRequest;
import com.metricplatform.identity.IdentityDtos.IdentitySummary;
import com.metricplatform.identity.IdentityDtos.ReplaceDatasetGrantsRequest;
import com.metricplatform.identity.IdentityDtos.ReplacePlatformPasswordRequest;
import com.metricplatform.identity.IdentityDtos.SetPlatformUserStatusRequest;
import com.metricplatform.security.PlatformPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/platform-users")
@RequiredArgsConstructor
public class PlatformIdentityAdminController {
    private final PlatformIdentityAdminService identities;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentitySummary create(
            @Valid @RequestBody CreatePlatformUserRequest request,
            @AuthenticationPrincipal PlatformPrincipal actor) {
        return identities.createUser(request, actor);
    }

    @PostMapping("/{identityId}:replace-password")
    public IdentitySummary replacePassword(
            @PathVariable UUID identityId,
            @Valid @RequestBody ReplacePlatformPasswordRequest request,
            @AuthenticationPrincipal PlatformPrincipal actor) {
        return identities.replacePassword(identityId, request, actor);
    }

    @PostMapping("/{identityId}:set-status")
    public IdentitySummary setStatus(
            @PathVariable UUID identityId,
            @Valid @RequestBody SetPlatformUserStatusRequest request,
            @AuthenticationPrincipal PlatformPrincipal actor) {
        return identities.setStatus(identityId, request, actor);
    }

    @PutMapping("/{identityId}/dataset-grants")
    public List<DatasetGrantRequest> replaceDatasetGrants(
            @PathVariable UUID identityId,
            @Valid @RequestBody ReplaceDatasetGrantsRequest request,
            @AuthenticationPrincipal PlatformPrincipal actor) {
        return identities.replaceDatasetGrants(identityId, request.grants(), actor);
    }
}
