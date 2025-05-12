package com.example.storage_app.controller.dto;

import com.example.storage_app.util.ValidFilename;
import jakarta.validation.constraints.NotBlank;

public record FileUpdateRequest(
    @ValidFilename @NotBlank(message = "Filename must not be blank") String newFilename) {}
