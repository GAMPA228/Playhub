package com.tvbox.web.controller;

import com.tvbox.web.service.BilibiliMediaService;
import com.tvbox.web.service.BilibiliService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/bilibili")
public class BilibiliController {

    private final BilibiliService service;
    private final BilibiliMediaService media;

    public BilibiliController(BilibiliService service, BilibiliMediaService media) {
        this.service = service;
        this.media = media;
    }

    public record ResolveRequest(@NotBlank @Size(max = 4096) String input,
                                 @Min(1) @Max(1000) Integer page,
                                 @Min(1) @Max(30000) Integer quality) {
    }

    @PostMapping("/resolve")
    public ResponseEntity<BilibiliService.Result> resolve(HttpSession session, @Valid @RequestBody ResolveRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.resolve(session.getId(), request.input(), request.page(), request.quality()));
    }

    @GetMapping(value = "/play/{id}/manifest.mpd", produces = "application/dash+xml")
    public ResponseEntity<String> manifest(HttpSession session, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/dash+xml"))
                .body(media.manifest(session.getId(), id));
    }

    @RequestMapping(value = "/media/{id}/{filename}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void stream(HttpSession session, @PathVariable String id, @PathVariable String filename,
                       @RequestParam("r") String resource, @RequestParam("sig") String signature,
                       @RequestHeader(value = "Range", required = false) String range,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        media.stream(session.getId(), id, resource, signature, range, "HEAD".equals(request.getMethod()), response);
    }
}
