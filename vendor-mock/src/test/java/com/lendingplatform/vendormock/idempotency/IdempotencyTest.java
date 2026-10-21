package com.lendingplatform.vendormock.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class IdempotencyTest {

    @Nested
    @SpringBootTest(properties = {"mock.api-keys=test-key", "mock.chaos.enabled=false"})
    @AutoConfigureMockMvc
    @DisplayName("over HTTP")
    class OverHttp {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("repeating a request with the same key replays the same quoteId, marked Idempotent-Replayed")
        void replaysStoredResponse() throws Exception {
            String key = UUID.randomUUID().toString();
            String body = """
                    {"loanAmount":500000,"loanTermInMonths":360,"riskBand":"MEDIUM"}""";

            String firstQuoteId = quoteIdOf(send(key, body)
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Idempotent-Replayed")));

            send(key, body)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.quoteId").value(firstQuoteId));
        }

        @Test
        @DisplayName("500000 and 500000.00 are the same payload")
        void normalisesAmountsWhenComparingPayloads() throws Exception {
            String key = UUID.randomUUID().toString();

            String firstQuoteId = quoteIdOf(send(key, """
                    {"loanAmount":500000,"loanTermInMonths":360,"riskBand":"MEDIUM"}"""));

            send(key, """
                    {"loanAmount":500000.00,"loanTermInMonths":360,"riskBand":"MEDIUM"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quoteId").value(firstQuoteId));
        }

        @Test
        @DisplayName("the same key with a different payload is a 422")
        void rejectsKeyReuseWithDifferentPayload() throws Exception {
            String key = UUID.randomUUID().toString();
            send(key, """
                    {"loanAmount":500000,"loanTermInMonths":360,"riskBand":"MEDIUM"}""");

            send(key, """
                    {"loanAmount":600000,"loanTermInMonths":360,"riskBand":"MEDIUM"}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }

        @Test
        @DisplayName("without a key, every request is a new quote")
        void noKeyMeansNewQuote() throws Exception {
            String body = """
                    {"loanAmount":500000,"loanTermInMonths":360,"riskBand":"MEDIUM"}""";

            String first = quoteIdOf(mvc.perform(request(body)));
            String second = quoteIdOf(mvc.perform(request(body)));

            assertThat(first).isNotEqualTo(second);
        }

        private ResultActions send(String key, String body) throws Exception {
            return mvc.perform(request(body).header("Idempotency-Key", key));
        }

        private static MockHttpServletRequestBuilder request(String body) {
            return post("/v1/commission-quotes")
                    .header("api-key", "test-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
        }

        private static String quoteIdOf(ResultActions result) throws Exception {
            return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.quoteId");
        }
    }

    @Nested
    @DisplayName("the store")
    class Store {

        private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T00:00:00Z"));
        private final IdempotencyStore<String> store =
                new IdempotencyStore<>(new IdempotencyProperties(Duration.ofMinutes(10), 3), clock);

        @Test
        @DisplayName("concurrent duplicates resolve to one stored response")
        void concurrentDuplicatesResolveToOneResponse() throws Exception {
            int callers = 16;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger created = new AtomicInteger();
            Callable<String> caller = () -> {
                start.await();
                return store.findOrCreate("same-key", "same-payload",
                        () -> "quote-" + created.incrementAndGet()).response();
            };

            Set<String> responses = ConcurrentHashMap.newKeySet();
            try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
                List<Future<String>> futures = IntStream.range(0, callers).mapToObj(i -> pool.submit(caller)).toList();
                start.countDown();
                for (Future<String> future : futures) {
                    responses.add(future.get());
                }
            }

            assertThat(responses).as("every caller sees the same stored response").hasSize(1);
        }

        @Test
        @DisplayName("an entry expires after the TTL and the key can then be used afresh")
        void expiresAfterTtl() {
            store.findOrCreate("key", "payload-a", () -> "first");

            clock.advance(Duration.ofMinutes(10));

            IdempotencyStore.Lookup<String> lookup = store.findOrCreate("key", "payload-b", () -> "second");
            assertThat(lookup.response()).isEqualTo("second");
            assertThat(lookup.replayed()).isFalse();
        }

        @Test
        @DisplayName("an entry within the TTL is replayed")
        void replaysWithinTtl() {
            store.findOrCreate("key", "payload", () -> "first");

            clock.advance(Duration.ofMinutes(9));

            assertThat(store.findOrCreate("key", "payload", () -> "second").response()).isEqualTo("first");
        }

        @Test
        @DisplayName("when full, the oldest entry is dropped so memory stays bounded")
        void dropsOldestWhenFull() {
            store.findOrCreate("k1", "p", () -> "r1");
            store.findOrCreate("k2", "p", () -> "r2");
            store.findOrCreate("k3", "p", () -> "r3");
            store.findOrCreate("k4", "p", () -> "r4");

            assertThat(store.findOrCreate("k1", "p", () -> "new").replayed()).isFalse();
            assertThat(store.findOrCreate("k4", "p", () -> "new").response()).isEqualTo("r4");
        }

        @Test
        @DisplayName("a different payload under a stored key is rejected")
        void rejectsDifferentPayload() {
            store.findOrCreate("key", "payload-a", () -> "first");

            assertThatThrownBy(() -> store.findOrCreate("key", "payload-b", () -> "second"))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
