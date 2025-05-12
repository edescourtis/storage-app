package com.example.storage_app.util;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = FilenameValidator.class)
@Target({FIELD})
@Retention(RUNTIME)
public @interface ValidFilename {
  String message() default "Unsafe filename";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
