package com.shiguang.photograph.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class ApiErrors {
    private final ObjectMapper json;

    public ApiErrors(ObjectMapper json) { this.json = json; }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Body> business(ApiException error) {
        Body body = body(error.code(), error.getMessage());
        return ResponseEntity.status(error.status()).header("X-Request-ID", body.requestId()).body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<Body> malformed() {
        return business(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求格式无效。"));
    }

    public void security(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        Body body = body(code, message);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Request-ID", body.requestId());
        json.writeValue(response.getOutputStream(), body);
    }

    private static Body body(String code, String message) {
        return new Body(code, message, UUID.randomUUID().toString());
    }

    record Body(String code, String message, String requestId) {}
}
