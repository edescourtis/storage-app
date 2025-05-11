package com.example.storage_app.util;

import org.bson.Document;
import org.bson.types.ObjectId;

public class FileStorageResult {
  public final ObjectId id;
  public final String sha256;
  public final String contentType;
  public final long size;
  public final Document metadata;

  public FileStorageResult(
      ObjectId id, String sha256, String contentType, long size, Document metadata) {
    this.id = id;
    this.sha256 = sha256;
    this.contentType = contentType;
    this.size = size;
    this.metadata = metadata;
  }
}
