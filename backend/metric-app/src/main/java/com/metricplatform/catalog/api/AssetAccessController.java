package com.metricplatform.catalog.api;

import com.metricplatform.catalog.AssetAccessService;
import com.metricplatform.catalog.AssetAccessService.View;
import com.metricplatform.catalog.AssetGrantRepository.Account;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.security.TenantAssets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 账号授权管理及本租户的资源目录。 */
@RestController
@RequiredArgsConstructor
public class AssetAccessController {
    private static final String ACCOUNTS = "/api/v1/tenant/accounts",
            ASSETS = "/assets",
            MINE = "/api/v1/assets",
            ACCOUNT_ASSETS = ACCOUNTS + "/{userId}" + ASSETS,
            GRANT = ACCOUNT_ASSETS + "/{assetId}",
            BUILD = "/api/dbt/builds/{buildId}",
            CATALOG = BUILD + "/catalog",
            OPTIONS = BUILD + "/available-dimensions";
    private final AssetAccessService access;
    private final TenantAssets ownership;
    private final BuildGateway gateway;

    @GetMapping(ACCOUNTS)
    public List<Account> readers() {
        return access.readers();
    }

    @GetMapping(MINE)
    public List<View> mine() {
        return access.list(null);
    }

    @GetMapping(ACCOUNT_ASSETS)
    public List<View> assets(@PathVariable UUID userId) {
        return access.list(userId);
    }

    @PutMapping(GRANT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@PathVariable UUID userId, @PathVariable UUID assetId) {
        access.set(userId, assetId, true);
    }

    @DeleteMapping(GRANT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID userId, @PathVariable UUID assetId) {
        access.set(userId, assetId, false);
    }

    @GetMapping(CATALOG)
    public ResponseEntity<?> catalog(@PathVariable UUID buildId) {
        ownership.requireTenantBuild(buildId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(access.catalog(buildId).toString());
    }

    @PostMapping(OPTIONS)
    public AvailableDimensionsView dimensions(
            @PathVariable UUID buildId, @RequestBody String request) {
        ownership.requireTenantBuild(buildId);
        var input = BuildRequestParser.parse(request, AvailableDimensionsRequest.class);
        return gateway.availableDimensions(buildId, input);
    }
}
