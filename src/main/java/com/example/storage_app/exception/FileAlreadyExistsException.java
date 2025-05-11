package com.example.storage_app.exception;

public class FileAlreadyExistsException extends RuntimeException {
  public FileAlreadyExistsException(String message) {
    super(message);
  }

  public FileAlreadyExistsException(String message, Throwable cause) {
    super(message, cause);
  }
}
