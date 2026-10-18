package com.custom.url.shortner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateUrlRequest(
        @NotBlank(message = "URL is required")
        @Size(max = 2048, message = "URL is too long")
        @URL String longUrl
) {
}
