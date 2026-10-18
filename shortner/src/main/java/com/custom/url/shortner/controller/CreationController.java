package com.custom.url.shortner.controller;

import com.custom.url.shortner.dto.CreateUrlRequest;
import com.custom.url.shortner.service.UrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("generate")
@RequiredArgsConstructor
public class CreationController {

    private final UrlService urlService;

    @PostMapping
    public ResponseEntity<String> createShortUrl(@Valid @RequestBody CreateUrlRequest request) {
        return urlService.shortenUrl(request);
    }
}
