package com.metricplatform.api;

import com.metricplatform.identity.LoginRateLimitException;
import com.metricplatform.query.QueryValidationException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
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
    private static final String GIT_WRITE_CONFLICT = "GIT01";

    /** Git 数据库守卫覆盖所有管理写路径；只把专用 SQLSTATE 转换为可处理的资源冲突。 */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> persistence(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (GIT_WRITE_CONFLICT.equals(sql.getSQLState())) {
                    return conflict(
                            new ConflictException(
                                    "Git-managed definitions must be changed in their repository"));
                }
                return upstream(sql);
            }
        }
        throw exception;
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
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Semantic validation failed",
                exception.getMessage());
    }

    @ExceptionHandler(QueryValidationException.class)
    ResponseEntity<ProblemDetail> queryInvalid(QueryValidationException exception) {
        ProblemDetail detail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        detail.setTitle("Semantic validation failed");
        detail.setProperty("code", exception.code());
        detail.setProperty("objectType", exception.objectType());
        detail.setProperty("objectId", exception.objectId());
        detail.setProperty("candidatePaths", exception.candidatePaths());
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ProblemDetail> forbidden(SecurityException exception) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", exception.getMessage());
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
