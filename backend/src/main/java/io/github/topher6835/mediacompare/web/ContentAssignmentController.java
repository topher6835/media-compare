package io.github.topher6835.mediacompare.web;

import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.scan.ContentAssignmentConflictException;
import io.github.topher6835.mediacompare.scan.ContentAssignmentService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scan-runs/{scanRunId}/content-assignment")
public class ContentAssignmentController {

    private final ContentAssignmentService contentAssignmentService;

    public ContentAssignmentController(ContentAssignmentService contentAssignmentService) {
        this.contentAssignmentService = contentAssignmentService;
    }

    @PostMapping
    public ResponseEntity<ContentAssignmentResponse> assign(@PathVariable long scanRunId) {
        return ResponseEntity.ok(ContentAssignmentResponse.from(contentAssignmentService.assign(scanRunId)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> missingScanRunOrExecution() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ContentAssignmentConflictException.class)
    public ResponseEntity<Void> assignmentConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
