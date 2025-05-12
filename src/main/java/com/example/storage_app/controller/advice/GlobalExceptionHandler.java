package com.example.storage_app.controller.advice;

import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

@ControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, WebRequest request) {
    Map<String, Object> body = new HashMap<>();
    body.put("timestamp", System.currentTimeMillis());
    body.put("status", HttpStatus.BAD_REQUEST.value());

    List<String> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
            .collect(Collectors.toList());
    body.put("errors", errors);
    body.put("message", "Validation failed");

    return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<Object> handleResourceNotFoundException(
      ResourceNotFoundException ex, WebRequest request) {
    return buildErrorResponse(ex, HttpStatus.NOT_FOUND, request);
  }

  @ExceptionHandler(FileAlreadyExistsException.class)
  public ResponseEntity<Object> handleFileAlreadyExistsException(
      FileAlreadyExistsException ex, WebRequest request) {
    return buildErrorResponse(ex, HttpStatus.CONFLICT, request);
  }

  @ExceptionHandler(UnauthorizedOperationException.class)
  public ResponseEntity<Object> handleUnauthorizedOperationException(
      UnauthorizedOperationException ex, WebRequest request) {
    return buildErrorResponse(ex, HttpStatus.FORBIDDEN, request);
  }

  @ExceptionHandler(InvalidRequestArgumentException.class)
  public ResponseEntity<Object> handleInvalidRequestArgumentException(
      InvalidRequestArgumentException ex, WebRequest request) {
    return buildErrorResponse(ex, HttpStatus.BAD_REQUEST, request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Object> handleIllegalArgumentException(
      IllegalArgumentException ex, WebRequest request) {
    return buildErrorResponse(ex, HttpStatus.BAD_REQUEST, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleAllOtherExceptions(Exception ex, WebRequest request) {
    return buildErrorResponse(ex, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<Object> handleDuplicateKeyException(
      DuplicateKeyException ex, WebRequest request) {
    String message =
        "A file with the same filename or content already exists for this user (DB conflict).";
    return buildErrorResponse(ex, message, HttpStatus.CONFLICT, request);
  }

  private ResponseEntity<Object> buildErrorResponse(
      Exception ex, HttpStatus status, WebRequest request) {
    return buildErrorResponse(ex, ex.getMessage(), status, request);
  }

  private ResponseEntity<Object> buildErrorResponse(
      Exception ex, String message, HttpStatus status, WebRequest request) {
    Map<String, Object> body = new HashMap<>();
    body.put("timestamp", System.currentTimeMillis());
    body.put("status", status.value());
    body.put("error", status.getReasonPhrase());
    body.put("message", message);
    return new ResponseEntity<>(body, status);
  }
}
