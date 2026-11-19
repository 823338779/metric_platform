package com.metricplatform.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.environment.AssetOwnership;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantHistoryPageTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mixedPagesHideUnknownBuildsAndBindCursorToEnvironmentAndFilter() {
        UUID env = UUID.randomUUID(), foreign = UUID.randomUUID(), visible = UUID.randomUUID();
        String tenant = "tn_00002";
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new PlatformPrincipal(
                                        UUID.randomUUID(),
                                        "m",
                                        "m",
                                        null,
                                        1,
                                        tenant,
                                        PlatformPrincipal.Role.TENANT_MANAGER),
                                null,
                                List.of()));
        var store = mock(AssetOwnership.class);
        when(store.hasEnvironment(eq(tenant), any())).thenReturn(true);
        when(store.cursorSecret(tenant)).thenReturn(UUID.randomUUID());
        var assets = new TenantAssets(store);
        var calls = new ArrayList<String>();
        var page =
                assets.<UUID>history(
                        env,
                        null,
                        cursor -> {
                            calls.add(cursor);
                            return cursor == null
                                    ? new TenantAssets.Page<>(List.of(foreign), "more")
                                    : new TenantAssets.Page<>(
                                            List.of(visible, foreign), foreign.toString());
                        },
                        visible::equals,
                        "builds",
                        "main");
        assertThat(calls).containsExactly(null, "more");
        assertThat(page.items()).containsExactly(visible);
        assertThat(
                        new String(
                                Base64.getUrlDecoder().decode(page.nextCursor()),
                                StandardCharsets.ISO_8859_1))
                .doesNotContain(foreign.toString());
        assertThatThrownBy(
                        () ->
                                assets.history(
                                        UUID.randomUUID(),
                                        page.nextCursor(),
                                        c -> {
                                            throw new AssertionError();
                                        },
                                        x -> true,
                                        "builds",
                                        "main"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                assets.history(
                                        env,
                                        page.nextCursor(),
                                        c -> {
                                            throw new AssertionError();
                                        },
                                        x -> true,
                                        "builds",
                                        "other"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
