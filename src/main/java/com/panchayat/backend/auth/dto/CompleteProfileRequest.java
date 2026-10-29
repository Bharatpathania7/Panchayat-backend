package com.panchayat.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CompleteProfileRequest(

        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name cannot exceed 100 characters")
        String fullName,

        @NotBlank(message = "Gender is required")
        @Size(max = 20, message = "Gender cannot exceed 20 characters")
        String gender,

        @NotNull(message = "Date of birth is required")
        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth

) {}