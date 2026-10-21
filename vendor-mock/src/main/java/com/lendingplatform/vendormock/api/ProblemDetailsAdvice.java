package com.lendingplatform.vendormock.api;

import com.lendingplatform.vendormock.web.CorrelationIdFilter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Every error the API returns is RFC 9457 Problem Details with two extension members: a stable {@code code} and the
 * {@code correlationId} (ARCHITECTURE §7.1, ADR-007). Spring's base class already turns the standard MVC exceptions
 * (415, 405, 404…) into Problem Details; this class adds the extensions and the contract's specific codes.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private static final String CODE = "code";
    private static final String CORRELATION_ID = "correlationId";
    private static final String ERRORS = "errors";

    public record FieldProblem(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblem(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::message))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        problem.setProperty(CODE, "VALIDATION_FAILED");
        problem.setProperty(ERRORS, errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Unparseable JSON and wrong types (a string for a number, 12.5 for the term, "low" for the band, an unknown
     * property). When Jackson knows which field it was, say so: it saves the caller a guess.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request body is not valid JSON for this operation, or a value has the wrong type.");
        problem.setProperty(CODE, "MALFORMED_REQUEST");
        if (ex.getCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            String field = jackson.getPath().stream()
                    .map(reference -> reference.getPropertyName() != null
                            ? reference.getPropertyName()
                            : String.valueOf(reference.getIndex()))
                    .collect(Collectors.joining("."));
            problem.setProperty(ERRORS, List.of(new FieldProblem(field, "has the wrong type or is not allowed")));
        }
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error handling request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Quote the correlationId if you contact support.");
        problem.setProperty(CODE, "INTERNAL_ERROR");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** Single exit point for every handler above and every standard MVC exception: add the extension members. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            Map<String, Object> properties = problem.getProperties();
            if (properties == null || !properties.containsKey(CODE)) {
                problem.setProperty(CODE, defaultCode(statusCode));
            }
            problem.setProperty(CORRELATION_ID, CorrelationIdFilter.current());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static String defaultCode(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }
}
