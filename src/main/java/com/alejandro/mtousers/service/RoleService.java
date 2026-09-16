package com.alejandro.mtousers.service;

import com.alejandro.mtousers.dto.ClientResponse;
import com.alejandro.mtousers.dto.ClientRoleResponse;
import com.alejandro.mtousers.dto.RoleNamesRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserRolesResponse;

import java.util.List;

/** Catálogo de roles de cliente y su asignación a usuarios. */
public interface RoleService {

    List<ClientResponse> listClients();

    List<ClientRoleResponse> listClientRoles(String clientId);

    UserRolesResponse getUserRoles(String userId);

    UserRolesResponse addClientRoles(String userId, String clientId, RoleNamesRequest request);

    UserRolesResponse removeClientRoles(String userId, String clientId, RoleNamesRequest request);

    /** Quién tiene ese rol de cliente asignado directamente. */
    List<UserResponse> listClientRoleMembers(String clientId, String roleName, int first, int max);
}
