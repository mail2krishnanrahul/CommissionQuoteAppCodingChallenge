package com.lendingplatform.vendormock.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Strict on what we receive (ADR-011): every bad payload is a 400 or 415 Problem Details, never a 500. */
@SpringBootTest(properties = {"mock.api-keys=test-key", "mock.chaos.enabled=false"})
@AutoConfigureMockMvc
class QuoteRequestValidationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("a valid request returns the quote, with the rate and total at their contract scales")
    void validRequestReturnsQuote() throws Exception {
        mvc.perform(quote("""
                        {"loanAmount":500000.00,"loanTermInMonths":360,"riskBand":"MEDIUM"}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.quoteId").isNotEmpty())
                .andExpect(content().string(containsString("\"commissionRate\":0.0060")))
                .andExpect(content().string(containsString("\"totalCommission\":3000.00")));
    }

    @ParameterizedTest(name = "{0} â†’ {2} on {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            term with a fraction is not silently truncated | {"loanAmount":500000,"loanTermInMonths":12.5,"riskBand":"LOW"}     | MALFORMED_REQUEST | loanTermInMonths
            term sent as a string                          | {"loanAmount":500000,"loanTermInMonths":"12","riskBand":"LOW"}     | MALFORMED_REQUEST | loanTermInMonths
            amount sent as a string                        | {"loanAmount":"500000","loanTermInMonths":12,"riskBand":"LOW"}     | MALFORMED_REQUEST | loanAmount
            lowercase risk band                            | {"loanAmount":500000,"loanTermInMonths":12,"riskBand":"low"}       | MALFORMED_REQUEST | riskBand
            unknown risk band                              | {"loanAmount":500000,"loanTermInMonths":12,"riskBand":"EXTREME"}   | MALFORMED_REQUEST | riskBand
            risk band as an index                          | {"loanAmount":500000,"loanTermInMonths":12,"riskBand":0}           | MALFORMED_REQUEST | riskBand
            unknown property                               | {"loanAmount":500000,"loanTermInMonths":12,"riskBand":"LOW","x":1} | MALFORMED_REQUEST | x
            missing loanAmount                             | {"loanTermInMonths":12,"riskBand":"LOW"}                           | VALIDATION_FAILED | loanAmount
            missing loanTermInMonths                       | {"loanAmount":500000,"riskBand":"LOW"}                             | VALIDATION_FAILED | loanTermInMonths
            missing riskBand                               | {"loanAmount":500000,"loanTermInMonths":12}                        | VALIDATION_FAILED | riskBand
            null riskBand                                  | {"loanAmount":500000,"loanTermInMonths":12,"riskBand":null}        | VALIDATION_FAILED | riskBand
            amount below the minimum                       | {"loanAmount":999.99,"loanTermInMonths":12,"riskBand":"LOW"}       | VALIDATION_FAILED | loanAmount
            amount above the maximum                       | {"loanAmount":5000000.01,"loanTermInMonths":12,"riskBand":"LOW"}   | VALIDATION_FAILED | loanAmount
            amount with 3 decimal places                   | {"loanAmount":1000.001,"loanTermInMonths":12,"riskBand":"LOW"}     | VALIDATION_FAILED | loanAmount
            negative amount                                | {"loanAmount":-1000,"loanTermInMonths":12,"riskBand":"LOW"}        | VALIDATION_FAILED | loanAmount
            term below the minimum                         | {"loanAmount":500000,"loanTermInMonths":11,"riskBand":"LOW"}       | VALIDATION_FAILED | loanTermInMonths
            term above the maximum                         | {"loanAmount":500000,"loanTermInMonths":361,"riskBand":"LOW"}      | VALIDATION_FAILED | loanTermInMonths
            """)
    @DisplayName("bad payloads are rejected with a code and the offending field")
    void rejectsBadPayloads(String description, String body, String expectedCode, String expectedField) throws Exception {
        mvc.perform(quote(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.errors[*].field", hasItem(expectedField)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            truncated JSON   | {"loanAmount":
            an array         | []
            not JSON at all  | loanAmount=500000
            empty body       | ``
            """)
    @DisplayName("bodies that aren't a JSON object are MALFORMED_REQUEST")
    void rejectsMalformedJson(String description, String body) throws Exception {
        mvc.perform(quote(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("a non-JSON content type is 415")
    void rejectsWrongContentType() throws Exception {
        mvc.perform(post("/v1/commission-quotes")
                        .header("api-key", "test-key")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("loanAmount=500000"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private static MockHttpServletRequestBuilder quote(String body) {
        return post("/v1/commission-quotes")
                .header("api-key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
