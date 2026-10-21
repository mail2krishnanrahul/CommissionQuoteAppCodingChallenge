package com.lendingplatform.vendormock.chaos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The filter order end to end, with MOCK_CHAOS_FAILURE_RATE=1: auth is deterministic, chaos only follows it. */
@SpringBootTest(properties = {"mock.api-keys=test-key", "mock.chaos.failure-rate=1", "mock.chaos.slow-rate=0"})
@AutoConfigureMockMvc
class ChaosAfterAuthTest {

    private static final String BODY = """
            {"loanAmount":500000,"loanTermInMonths":360,"riskBand":"MEDIUM"}""";

    @Autowired
    private MockMvc mvc;

    @RepeatedTest(5)
    @DisplayName("a request without a key is always a 401, never a random 5xx")
    void missingKeyIsAlways401() throws Exception {
        mvc.perform(post("/v1/commission-quotes").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @RepeatedTest(5)
    @DisplayName("with failure-rate 1, every authenticated call fails with a 500 or 503")
    void everyAuthenticatedCallFails() throws Exception {
        mvc.perform(post("/v1/commission-quotes").header("api-key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(Matchers.oneOf(500, 503)))
                .andExpect(jsonPath("$.code", Matchers.startsWith("SIMULATED_")));
    }

    @Test
    @DisplayName("health stays up however flaky the API is")
    void healthIsUnaffected() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
