package com.alejandro.mtousers.exception;

/** No hay ningún perfil con ese nombre: o el rol de realm no existe o no es un perfil (404). */
public class ProfileNotFoundException extends UsersException {

    public static final String CODE = "PRF-404";

    public ProfileNotFoundException(String profileName) {
        super(CODE, "Profile " + profileName + " was not found");
    }
}
