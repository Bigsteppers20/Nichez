package app.nichez;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class ApiApplicationTests {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @Test
    void contextLoads() {
    }
}
