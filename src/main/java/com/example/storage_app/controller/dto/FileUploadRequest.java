package com.example.storage_app.controller.dto;

import java.util.List;

import com.example.storage_app.model.Visibility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FileUploadRequest(
    @NotBlank(message = "Filename must not be blank")
    String filename,

    @NotNull(message = "Visibility must be provided")
    Visibility visibility,

    @Size(max = 5, message = "A maximum of 5 tags are allowed")
    List<String> tags
) {
} 