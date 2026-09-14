package io.github.topher6835.mediacompare.web;

import java.net.URI;
import java.util.List;

import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @PostMapping
    public ResponseEntity<SourceResponse> register(@RequestBody RegisterSourceRequest request) {
        Source source = sourceService.register(request.name(), request.rootPath());
        URI location = URI.create("/api/sources/" + source.id());
        return ResponseEntity.created(location).body(SourceResponse.from(source));
    }

    @GetMapping
    public List<SourceResponse> findAll() {
        return sourceService.findAll().stream()
                .map(SourceResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SourceResponse> findById(@PathVariable long id) {
        return sourceService.findById(id)
                .map(SourceResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> invalidRegistration() {
        return ResponseEntity.badRequest().build();
    }
}
