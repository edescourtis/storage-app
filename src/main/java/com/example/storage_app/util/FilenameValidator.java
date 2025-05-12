package com.example.storage_app.util;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.io.FilenameUtils;

public class FilenameValidator implements ConstraintValidator<ValidFilename, String> {
  private static final Pattern WINDOWS_FORBIDDEN_CHARS = Pattern.compile("[<>:\"|?*]");
  private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
  private static final Set<String> WINDOWS_RESERVED_NAMES =
      new HashSet<>(
          Arrays.asList(
              "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
              "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8",
              "LPT9"));
  private static final int MAX_FILENAME_CODE_POINTS = 255;

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return false;
    }
    if (CONTROL_CHARS.matcher(value).find()) {
      return false;
    }
    String baseName = FilenameUtils.getName(value);
    if (!baseName.equals(value)) {
      return false;
    }
    if (WINDOWS_FORBIDDEN_CHARS.matcher(value).find()) {
      return false;
    }
    String nameWithoutExt = FilenameUtils.getBaseName(value).toUpperCase();
    if (WINDOWS_RESERVED_NAMES.contains(nameWithoutExt)) {
      return false;
    }
    if (!value.equals(value.trim())) {
      return false;
    }
    if (value.codePointCount(0, value.length()) > MAX_FILENAME_CODE_POINTS) {
      return false;
    }
    return true;
  }
}
