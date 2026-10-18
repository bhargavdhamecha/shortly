package com.custom.url.shortner.controller;

import com.custom.url.shortner.log.LogEntryExit;
import com.custom.url.shortner.service.UrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final UrlService urlService;

    @GetMapping("/{shortCode}")
    @Operation(
            summary = "Redirect to the original URL",
            description = "Open the short URL directly in a browser to follow the redirect. "
                    + "Swagger UI's Try it out follows redirects using a browser fetch request, "
                    + "which can fail with a CORS error when the destination is an external website. "
                    + "Use curl -i without -L to inspect the 302 response and Location header."
    )
    @ApiResponse(responseCode = "302", description = "Redirect to the original URL",
            headers = @Header(name = "Location", description = "Original URL",
                    schema = @Schema(type = "string", format = "uri")))
    @ApiResponse(responseCode = "404", description = "Short code is invalid or was not found")
    @LogEntryExit
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = urlService.findOriginalUrl(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND) // 302 redirect
                .location(URI.create(originalUrl))
                .build();
    }

}
