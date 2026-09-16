package com.alejandro.mtousers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Nombres de roles de un cliente, para asignarlos o quitarlos. */
public record RoleNamesRequest(@NotEmpty List<@NotBlank @Size(max = 255) String> roles) {
}
