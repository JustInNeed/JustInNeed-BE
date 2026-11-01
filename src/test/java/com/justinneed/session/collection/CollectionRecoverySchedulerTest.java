package com.justinneed.session.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.justinneed.session.collection.service.CollectionRecoveryScheduler;
import com.justinneed.session.collection.service.CollectionSettings;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

// 스케줄러를 켠 설정으로 앱이 뜨는지 본다 (주기 값 형식 포함). 다른 테스트에서는 꺼 둔다
@SpringBootTest(properties = {
        "app.collection.scheduler-enabled=true",
        "app.collection.scheduler-interval=1h",
        "spring.datasource.url=jdbc:h2:mem:collection-scheduler;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
@DisplayName("복구 스케줄러 등록")
class CollectionRecoverySchedulerTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CollectionSettings settings;

    @Test
    @DisplayName("켜면 등록되고, 설정이 없으면 정한 기본값을 쓴다")
    void registered() {
        assertThat(context.getBeansOfType(CollectionRecoveryScheduler.class)).hasSize(1);
        assertThat(settings).isEqualTo(new CollectionSettings(Duration.ofMinutes(30), Duration.ofMinutes(3),
                Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(30), 3));
    }
}
