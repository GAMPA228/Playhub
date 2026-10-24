package com.tvbox.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tvbox.web.service.SourceSettingsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/source-settings")
public class SourceSettingsController {

    private final SourceSettingsService settings;

    public SourceSettingsController(SourceSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ResponseEntity<SourceSettingsService.Settings> getSettings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.getSettings());
    }

    @PostMapping
    public SourceSettingsService.Settings update(@RequestBody JsonNode patch) {
        return settings.update(patch);
    }
}
