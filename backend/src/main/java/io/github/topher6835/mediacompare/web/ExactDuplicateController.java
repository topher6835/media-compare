package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.matching.ExactDuplicateService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exact-duplicate-groups")
public class ExactDuplicateController {

    private final ExactDuplicateService service;

    public ExactDuplicateController(ExactDuplicateService service) {
        this.service = service;
    }

    @GetMapping
    public ExactDuplicateGroupPageResponse findGroups(
            @RequestParam(required = false) String afterDigestHex,
            @RequestParam(required = false) Integer limit) {
        return ExactDuplicateGroupPageResponse.from(service.findGroups(afterDigestHex, limit));
    }

    @GetMapping("/{digestHex}")
    public ResponseEntity<ExactDuplicateGroupDetailResponse> findGroup(
            @PathVariable String digestHex) {
        return service.findGroup(digestHex)
                .map(ExactDuplicateGroupDetailResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> invalidRequest() {
        return ResponseEntity.badRequest().build();
    }
}
