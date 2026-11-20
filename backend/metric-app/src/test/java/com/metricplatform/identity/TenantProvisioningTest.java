package com.metricplatform.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.identity.api.IdentityDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = "metric-platform.operations.token=fixture-only")
@AutoConfigureMockMvc
class TenantProvisioningTest {
    @Autowired MockMvc mvc;
    private static final String PATH = "/api/ops/tenants";
    @Autowired TenantFixtureMapper fixtures;
    @Autowired TenantAccountService accounts;

    @Test
    void concurrentNormalizedLoginsOnlyCommitOneTenant() throws Exception {
        int before = fixtures.tenantCount();
        String name = UUID.randomUUID().toString();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = new ArrayList<Future<Integer>>();
            for (String login : List.of(name, " " + name.toUpperCase(Locale.ROOT) + " ")) {
                attempts.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        accounts.provision(
                                                new IdentityDtos.AuthTokenRequest(
                                                        login, "fixture-password"));
                                        return 201;
                                    } catch (ResponseStatusException e) {
                                        return e.getStatusCode().value();
                                    }
                                }));
            }
            gate.countDown();
            Assertions.assertThat(List.of(attempts.getFirst().get(), attempts.getLast().get()))
                    .containsExactlyInAnyOrder(201, 409);
        }
        Assertions.assertThat(fixtures.tenantCount()).isEqualTo(before + 1);
    }

    @Test
    void exhaustedTenantNumbersReportConflict() {
        long saved = fixtures.tenantSequenceValue();
        boolean called = fixtures.tenantSequenceCalled();
        try {
            fixtures.setTenantSequence(99999, true);
            Assertions.assertThatThrownBy(
                            () ->
                                    accounts.provision(
                                            new IdentityDtos.AuthTokenRequest(
                                                    "exhaustion", "fixture-password")))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> Assertions.assertThat(e.getReason()).isEqualTo("租户编号已耗尽"));
        } finally {
            fixtures.setTenantSequence(saved, called);
        }
    }

    @Test
    void onlyOperationsCredentialCanOpenTenantAndDuplicatesConflict() throws Exception {
        String body =
                "{\"username\":\"" + UUID.randomUUID() + "\",\"password\":\"fixture-password\"}";
        mvc.perform(post(PATH).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(PATH)
                                .header("X-Operations-Token", "fixture-only")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant_id").isString());
        mvc.perform(
                        post(PATH)
                                .header("X-Operations-Token", "fixture-only")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isConflict());
    }
}
