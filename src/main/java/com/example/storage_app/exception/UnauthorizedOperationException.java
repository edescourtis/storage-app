package com.example.storage_app.exception;

public class UnauthorizedOperationException extends RuntimeException {
  public UnauthorizedOperationException(String message) {
    super(message);
  }
}
