package com.lendingplatform.vendormock.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"mock.api-keys=current-key,rotated-key", "mock.chaos.enabled=false"})
@AutoConfigureMockMvc
class ApiKeyFilterTest {

    private static final String QUOTES = "/v1/commission-quotes";
    private static final String VALID_BODY = """
            {"loanAmount":500000,"loanTermInMonths":360,"riskBand":"MEDIUM"}""";
    private static final String FIXED_CORRELATION_ID = "api-key-test";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("missing, blank, wrong and wrong-length keys all get the same 401 Problem Details body")
    void rejectsBadKeysWithIdenticalBodies() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String presentedKey : Arrays.asList(null, "", "   ", "wrong-key", "c", "current-key-plus-extra")) {
            String body = mvc.perform(quoteRequest(presentedKey))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.correlationId").value(FIXED_CORRELATION_ID))
                    .andReturn().getResponse().getContentAsString();
            bodies.add(body);
        }

        assertThat(bodies).as("a caller must not be able to tell missing from wrong").containsOnly(bodies.getFirst());
    }

    @ParameterizedTest(name = "accepts \"{0}\"")
    @ValueSource(strings = {"current-key", "rotated-key"})
    @DisplayName("any configured key is accepted, so a key can be rotated with an overlap window")
    void acceptsEveryConfiguredKey(String key) throws Exception {
        mvc.perform(quoteRequest(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").isNotEmpty());
    }

    @Test
    @DisplayName("the health endpoint needs no key")
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("everything else is denied by default, even paths that don't exist")
    void unknownPathsNeedAKeyToo() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder quoteRequest(String apiKey) {
        MockHttpServletRequestBuilder request = post(QUOTES)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", FIXED_CORRELATION_ID)
                .content(VALID_BODY);
        return apiKey == null ? request : request.header(ApiKeyFilter.HEADER, apiKey);
    }
}
