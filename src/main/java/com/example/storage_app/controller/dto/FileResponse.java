package com.example.storage_app.controller.dto;

import com.example.storage_app.model.Visibility;
import java.util.Date;
import java.util.List;

public record FileResponse(
    String id,
    String filename,
    Visibility visibility,
    List<String> tags,
    Date uploadDate,
    String contentType,
    long size,
    String downloadLink) {}
