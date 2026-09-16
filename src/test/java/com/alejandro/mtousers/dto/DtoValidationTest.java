package com.alejandro.mtousers.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void createUserRequestValidatesUsernameEmailAndPassword() {
        assertTrue(validator.validate(new CreateUserRequest("ana.nueva", null, null, "ana@mto.local", null, null, null, null, "Secreta.123")).isEmpty());

        assertEquals(Set.of("username"), fields(new CreateUserRequest(" ", null, null, null, null, null, null, null, null)));
        assertEquals(Set.of("username"), fields(new CreateUserRequest("ana nueva", null, null, null, null, null, null, null, null)));
        assertEquals(Set.of("email"), fields(new CreateUserRequest("ana", null, null, "no-es-email", null, null, null, null, null)));
        assertEquals(Set.of("temporaryPassword"), fields(new CreateUserRequest("ana", null, null, null, null, null, null, null, "corta")));
        assertEquals(Set.of("requiredActions[0].<list element>"), fields(new CreateUserRequest("ana", null, null, null, null, null, null,
                java.util.Arrays.asList((RequiredAction) null), null)));
    }

    @Test
    void passwordsNeverShowUpInToString() {
        assertFalse(new CreateUserRequest("ana", null, null, null, null, null, null, null, "Secreta.123").toString().contains("Secreta.123"));
        assertFalse(new ResetPasswordRequest("Secreta.123", true).toString().contains("Secreta.123"));
        assertTrue(new CreateUserRequest("ana", null, null, null, null, null, null, null, "Secreta.123").toString().contains("temporaryPassword=******"));
    }

    @Test
    void resetPasswordRequestNeedsAMinimumLengthAndIsTemporaryByDefault() {
        assertEquals(Set.of("password"), fields(new ResetPasswordRequest("corta", null)));
        assertEquals(Set.of("password"), fields(new ResetPasswordRequest(" ", false)));
        assertTrue(new ResetPasswordRequest("Secreta.123", null).isTemporary());
        assertFalse(new ResetPasswordRequest("Secreta.123", false).isTemporary());
    }

    @Test
    void executeActionsEmailRequestNeedsActionsAndAPositiveLifespan() {
        assertTrue(validator.validate(new ExecuteActionsEmailRequest(List.of(RequiredAction.UPDATE_PASSWORD), 600, null, null)).isEmpty());
        assertEquals(Set.of("actions"), fields(new ExecuteActionsEmailRequest(List.of(), null, null, null)));
        assertEquals(Set.of("lifespanSeconds"), fields(new ExecuteActionsEmailRequest(List.of(RequiredAction.VERIFY_EMAIL), 0, null, null)));
    }

    @Test
    void roleNamesRequestRejectsEmptyListsAndBlankNames() {
        assertEquals(Set.of("roles"), fields(new RoleNamesRequest(List.of())));
        assertEquals(Set.of("roles[1].<list element>"), fields(new RoleNamesRequest(List.of("stock-read", " "))));
    }

    @Test
    void updateUserRequestValidatesEmailAndAttributeKeys() {
        assertEquals(Set.of("email"), fields(new UpdateUserRequest(null, null, "nope", null, null)));
        assertEquals(Set.of("attributes<K>[ ].<map key>"), fields(new UpdateUserRequest(null, null, null, null, Map.of(" ", List.of("x")))));
        assertEquals(Set.of("enabled"), fields(new UserEnabledRequest(null)));
    }

    private Set<String> fields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
