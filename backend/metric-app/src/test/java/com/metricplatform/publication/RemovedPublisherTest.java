package com.metricplatform.publication;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RemovedPublisherTest {
    @Autowired ApplicationContext context;

    @Test
    void publisherIsNotAssembled() {
        assertThat(context.containsBean("releaseCoordinator")).isFalse();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) continue;
            assertThat(type.getName())
                    .doesNotContain(
                            "ReleaseCoordinator", "PublicationStore", "GitRefresh", "GitWebhook");
            for (var method : type.getDeclaredMethods())
                if (method.isAnnotationPresent(Scheduled.class))
                    assertThat(type.getName()).doesNotContain("publication", "gitsync");
        }
    }
}
