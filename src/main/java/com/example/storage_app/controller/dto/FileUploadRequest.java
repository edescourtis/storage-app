package com.example.storage_app.controller.dto;

import com.example.storage_app.model.Visibility;
import com.example.storage_app.util.ValidFilename;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FileUploadRequest(
    @ValidFilename @NotBlank(message = "Filename must not be blank") String filename,
    @NotNull(message = "Visibility must be provided") Visibility visibility,
    @Size(max = 5, message = "A maximum of 5 tags are allowed") List<String> tags) {}
