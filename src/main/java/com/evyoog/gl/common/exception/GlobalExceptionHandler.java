package com.evyoog.gl.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EvyoogException.class)
    public ResponseEntity<ErrorResponse> handleEvyoogException(EvyoogException ex) {
        ErrorResponse body = ErrorResponse.of(ex.getStatus().value(), ex.getCode(), ex.getMessage(), ex.getField());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    // @PreAuthorize denials are thrown by the method-security AOP interceptor from inside
    // DispatcherServlet's dispatch, so they never reach SecurityConfig's accessDeniedHandler
    // (that only sees exceptions from the filter chain itself, e.g. authentication failures
    // before dispatch). Handle both here so every 401/403 gets the same structured body.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse body = ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                "You do not have permission to perform this action.", null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        ErrorResponse body = ErrorResponse.of(HttpStatus.UNAUTHORIZED.value(), "AUTH_REQUIRED",
                "Authentication is required to access this resource.", null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        org.springframework.validation.FieldError fieldError = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .orElse(null);
        String field = fieldError != null ? fieldError.getField() : null;
        String message = fieldError != null ? fieldError.getDefaultMessage() : "Validation failed";
        ErrorResponse body = ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message, field);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception reached GlobalExceptionHandler", ex);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR", ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
