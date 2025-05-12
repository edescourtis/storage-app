package com.example.storage_app.controller.dto;

import java.util.List;

public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    int totalPages,
    long totalElements,
    boolean last,
    boolean first,
    int numberOfElements) {}
