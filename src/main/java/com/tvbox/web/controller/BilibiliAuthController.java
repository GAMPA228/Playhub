package com.tvbox.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tvbox.web.service.BilibiliAuthService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/bilibili/auth")
public class BilibiliAuthController {

    private final BilibiliAuthService auth;

    public BilibiliAuthController(BilibiliAuthService auth) {
        this.auth = auth;
    }

    @GetMapping
    public ResponseEntity<BilibiliAuthService.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.status());
    }

    @PostMapping
    public ResponseEntity<BilibiliAuthService.Status> update(@RequestBody JsonNode body,
            @RequestHeader(value = "X-Mujian-Settings", required = false) String settingsHeader) {
        if (!"1".equals(settingsHeader)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请通过本站设置页面修改 B站登录态");
        }
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("cookie").isTextual()) {
            throw new IllegalArgumentException("请提交 Cookie 字符串，清除登录态请使用空字符串");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.update(body.path("cookie").asText()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("ok", false, "error", "请求体格式无效，请使用 JSON 格式提交 Cookie"));
    }
}
