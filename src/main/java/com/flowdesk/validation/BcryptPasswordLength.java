package com.flowdesk.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BcryptPasswordLength.Validator.class)
public @interface BcryptPasswordLength {
    String message() default PasswordRules.BCRYPT_LENGTH_MESSAGE;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BcryptPasswordLength, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return PasswordRules.isWithinBcryptLimit(value);
        }
    }
}
