package com.metricplatform.api;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.NoSuchElementException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> malformed(MethodArgumentTypeMismatchException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Malformed parameter",
                "A request parameter has an invalid type");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> malformedBody(MethodArgumentNotValidException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Request validation failed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(IllegalArgumentException exception) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Semantic validation failed",
                exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missing(NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Resource conflict", exception.getMessage());
    }

    @ExceptionHandler(SQLTimeoutException.class)
    ResponseEntity<ProblemDetail> timeout(SQLTimeoutException exception) {
        return problem(
                HttpStatus.GATEWAY_TIMEOUT,
                "Source preview timed out",
                "The upstream source did not respond before the timeout");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> unavailable(IllegalStateException exception) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Data source unavailable",
                "The data source is inactive or unavailable");
    }

    @ExceptionHandler(SQLException.class)
    ResponseEntity<ProblemDetail> upstream(SQLException exception) {
        return problem(
                HttpStatus.BAD_GATEWAY,
                "Query execution failed",
                "The upstream query could not be completed");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body =
                ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        body.setTitle(title);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
