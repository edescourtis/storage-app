package com.example.storage_app.exception;

public class InvalidRequestArgumentException extends RuntimeException {
  public InvalidRequestArgumentException(String message) {
    super(message);
  }
}
