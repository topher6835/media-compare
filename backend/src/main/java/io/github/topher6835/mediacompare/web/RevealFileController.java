package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.matching.RevealFileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/media-library/items")
public class RevealFileController {
    private final RevealFileService service;

    public RevealFileController(RevealFileService service) {
        this.service = service;
    }

    @PostMapping("/{fileEntryId}/reveal")
    public ResponseEntity<ErrorResponse> reveal(@PathVariable long fileEntryId,
            @RequestHeader(value = "X-Media-Compare-Reveal", required = false) String actionHeader,
            @RequestBody(required = false) String body) {
        if (!"1".equals(actionHeader)) return failure(HttpStatus.BAD_REQUEST, "ACTION_HEADER_REQUIRED");
        if (body != null && !body.isBlank()) return failure(HttpStatus.BAD_REQUEST, "BODY_NOT_ALLOWED");
        return switch (service.reveal(fileEntryId)) {
            case SUCCESS -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            case NOT_FOUND -> failure(HttpStatus.NOT_FOUND, "FILE_ENTRY_NOT_FOUND");
            case STALE_AUTHORITY -> failure(HttpStatus.CONFLICT, "AUTHORITY_UNAVAILABLE");
            case FILE_CHANGED -> failure(HttpStatus.GONE, "FILE_CHANGED");
            case UNSUPPORTED_HOST -> failure(HttpStatus.NOT_IMPLEMENTED, "UNSUPPORTED_HOST");
            case FINDER_FAILED -> failure(HttpStatus.BAD_GATEWAY, "FINDER_FAILED");
        };
    }

    private static ResponseEntity<ErrorResponse> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code));
    }

    public record ErrorResponse(String code) { }
}
