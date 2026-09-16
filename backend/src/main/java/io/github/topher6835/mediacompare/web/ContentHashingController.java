package io.github.topher6835.mediacompare.web;

import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.analysis.ContentHashingConflictException;
import io.github.topher6835.mediacompare.analysis.ContentHashingService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scan-runs/{scanRunId}/content-hashing")
public class ContentHashingController {

    private final ContentHashingService contentHashingService;

    public ContentHashingController(ContentHashingService contentHashingService) {
        this.contentHashingService = contentHashingService;
    }

    @PostMapping
    public ResponseEntity<ContentHashingResponse> hash(@PathVariable long scanRunId) {
        return ResponseEntity.ok(ContentHashingResponse.from(contentHashingService.hash(scanRunId)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> missingScanRunOrExecution() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ContentHashingConflictException.class)
    public ResponseEntity<Void> hashingConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
