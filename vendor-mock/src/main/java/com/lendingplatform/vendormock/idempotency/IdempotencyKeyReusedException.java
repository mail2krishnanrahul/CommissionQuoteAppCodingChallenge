package com.lendingplatform.vendormock.idempotency;

/** The same Idempotency-Key arrived with a different request body: a client bug, answered with 422. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used with a different request body.");
    }
}
