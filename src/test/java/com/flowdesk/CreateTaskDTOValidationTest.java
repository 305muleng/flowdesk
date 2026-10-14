
package com.flowdesk;

import com.flowdesk.dto.CreateTaskDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateTaskDTOValidationTest {

    @Test
    void missingDescriptionAndGoalShouldFailValidation() {
        CreateTaskDTO dto = new CreateTaskDTO();

        dto.setTitle("测试任务");
        dto.setDeadline(LocalDateTime.now().plusDays(1));

        // description 和 goal 故意不赋值

        try (ValidatorFactory factory =
                     Validation.buildDefaultValidatorFactory()) {

            Validator validator = factory.getValidator();

            Set<String> invalidFields =
                    validator.validate(dto).stream()
                            .map(v -> v.getPropertyPath().toString())
                            .collect(Collectors.toSet());

            assertEquals(
                    Set.of("description", "goal"),
                    invalidFields
            );
        }
    }
}
