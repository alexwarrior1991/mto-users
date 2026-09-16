package com.alejandro.mtousers.service;

import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.ExecuteActionsEmailRequest;
import com.alejandro.mtousers.dto.PageResponse;
import com.alejandro.mtousers.dto.ResetPasswordRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserSearchCriteria;

/** Administración de usuarios del realm. */
public interface UserService {

    PageResponse<UserResponse> search(UserSearchCriteria criteria);

    UserResponse get(String userId);

    UserResponse create(CreateUserRequest request);

    UserResponse update(String userId, UpdateUserRequest request);

    UserResponse setEnabled(String userId, boolean enabled);

    void delete(String userId);

    void resetPassword(String userId, ResetPasswordRequest request);

    void executeActionsEmail(String userId, ExecuteActionsEmailRequest request);
}
