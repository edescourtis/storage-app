package com.example.storage_app.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FilenameValidatorTest {
  private final FilenameValidator validator = new FilenameValidator();

  @Test
  void valid() {
    assertTrue(validator.isValid("file.txt", null));
  }

  @Test
  void nullOrBlank() {
    assertFalse(validator.isValid(null, null));
    assertFalse(validator.isValid("   ", null));
  }

  @Test
  void path() {
    assertFalse(validator.isValid("/etc/passwd", null));
  }

  @Test
  void controlChar() {
    assertFalse(validator.isValid("file\u0000.txt", null));
  }

  @Test
  void forbiddenChar() {
    assertFalse(validator.isValid("file<.txt", null));
  }

  @Test
  void reservedName() {
    assertFalse(validator.isValid("CON.txt", null));
  }

  @Test
  void whitespace() {
    assertFalse(validator.isValid(" file.txt", null));
    assertFalse(validator.isValid("file.txt ", null));
  }

  @Test
  void tooLong() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 256; i++) sb.append('a');
    assertFalse(validator.isValid(sb.toString(), null));
  }
}
