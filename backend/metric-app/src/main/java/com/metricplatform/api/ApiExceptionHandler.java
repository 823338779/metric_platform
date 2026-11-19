package com.metricplatform.api;

import com.metricplatform.identity.auth.LoginRateLimitException;
import com.metricplatform.query.ReleaseGoneException;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.metricplatform.environment.RepositorySource.UnavailableException.class)
    ResponseEntity<ProblemDetail> repositoryUnavailable(
            com.metricplatform.environment.RepositorySource.UnavailableException exception) {
        return problem(
                HttpStatus.BAD_GATEWAY, "Git repository unavailable", exception.getMessage());
    }

    private static final String CODE = "code",
            PHASE = "phase",
            BUILD_ID = "buildId",
            RETRYABLE = "retryable";

    @ExceptionHandler(com.metricplatform.catalog.BuildGateway.Failure.class)
    ResponseEntity<ProblemDetail> buildFailure(
            com.metricplatform.catalog.BuildGateway.Failure failure) {
        var error = failure.error();
        ProblemDetail body =
                ProblemDetail.forStatusAndDetail(
                        HttpStatusCode.valueOf(failure.status()), error.message());
        body.setProperty(CODE, error.code());
        body.setProperty(PHASE, error.phase());
        body.setProperty(BUILD_ID, error.buildId());
        body.setProperty(RETRYABLE, error.retryable());
        return ResponseEntity.status(failure.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(ReleaseGoneException.class)
    ResponseEntity<ProblemDetail> releaseGone(ReleaseGoneException exception) {
        return problem(HttpStatus.GONE, "Release cleaned", exception.getMessage());
    }

    /** 数据库错误只返回可处理的上游故障，不泄露 SQL 和连接信息。 */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> persistence(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return upstream(sql);
            }
        }
        throw exception;
    }

    /** 将租户开通冲突的可操作原因返回给调用方。 */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ProblemDetail> operationConflict(
            org.springframework.web.server.ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.getBody());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> unauthorized(BadCredentialsException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid credentials");
    }

    @ExceptionHandler(LoginRateLimitException.class)
    ResponseEntity<ProblemDetail> rateLimited(LoginRateLimitException exception) {
        return problem(
                HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts", "Too many login attempts");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", "Access denied");
    }

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
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid request", exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ProblemDetail> forbidden(SecurityException exception) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missing(NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> unavailable(IllegalStateException exception) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Platform operation unavailable",
                "The requested operation is temporarily unavailable");
    }

    @ExceptionHandler(SQLException.class)
    ResponseEntity<ProblemDetail> upstream(SQLException exception) {
        return problem(
                HttpStatus.BAD_GATEWAY,
                "Database operation failed",
                "The database operation could not be completed");
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
