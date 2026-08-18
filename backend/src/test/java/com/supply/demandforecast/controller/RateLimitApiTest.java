package com.supply.demandforecast.controller;

import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rate limiter over HTTP: a burst past the budget is refused with 429, a {@code Retry-After},
 * and the same RFC-7807 body every other error carries.
 *
 * <p>Runs in a context of its own — {@code @TestPropertySource} turns the limiter on with a tiny
 * capacity, which the shared {@code test} profile keeps off. That isolation matters twice over: the
 * low capacity would trip other classes' uploads, and their uploads would spend this one's budget,
 * since the limiter is a singleton whose buckets outlive a single request.
 *
 * <p>Every assertion is in one method deliberately: the two allowed uploads and the refused third
 * share a single bucket, so splitting them across methods would leave the second depending on the
 * first's leftover tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
@TestPropertySource(properties = {
        "ratelimit.enabled=true",
        "ratelimit.capacity=2",
        "ratelimit.refill-per-minute=1"
})
class RateLimitApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WithTestUsers users;

    @BeforeEach
    void seed() {
        users.reset();
    }

    @Test
    void aBurstPastTheBudgetIsRefusedWith429AndARetryAfter() throws Exception {
        // The first two land within the capacity of two...
        upload().andExpect(status().isOk());
        upload().andExpect(status().isOk());

        // ...and the third is over budget: 429, a Retry-After telling the caller how long to wait,
        // and a ProblemDetail the dashboard can render like any other error.
        upload()
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, notNullValue()))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(notNullValue()));
    }

    private org.springframework.test.web.servlet.ResultActions upload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv",
                "sku,name\nX-1,X\n".getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(MockMvcRequestBuilders.multipart("/api/products/import")
                .file(file)
                .with(users.admin()));
    }
}
