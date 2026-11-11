package com.alejandro.mtousers.exception;

/**
 * Alguien intenta cambiar sus propios permisos o sacarse a sí mismo (403): asignarse o quitarse un
 * rol de cliente o un perfil, deshabilitarse o borrarse. Con {@code users-roles-write} o
 * {@code users-profiles-write} una persona podría darse cualquier permiso del dominio; lo que
 * cambia los permisos de alguien lo hace siempre otra persona.
 *
 * <p>Es un 403 con código propio y no {@code AUTH-403}: el permiso está, lo que no vale es el
 * objetivo.</p>
 */
public class SelfAdministrationException extends UsersException {

    public static final String CODE = "USR-SELF-403";

    public SelfAdministrationException(String operation) {
        super(CODE, "You cannot " + operation + ": it has to be done by another administrator");
    }
}
