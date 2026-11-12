package com.tvbox.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(Map.of(
                "ok", false, "error", ex.getReason() == null ? "请求失败" : ex.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
        return Map.of("ok", false, "error", "请求参数无效，请检查输入内容及参数范围");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", false);
        data.put("error", ex.getMessage());
        return data;
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalState(IllegalStateException ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", false);
        data.put("error", ex.getMessage());
        return data;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", false);
        data.put("error", ex.getMessage());
        return data;
    }
}
