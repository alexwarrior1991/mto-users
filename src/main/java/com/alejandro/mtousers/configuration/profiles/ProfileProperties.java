package com.alejandro.mtousers.configuration.profiles;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Qué roles de realm son perfiles ({@code app.profiles.*}).
 *
 * <p>Un perfil es un rol <em>compuesto</em> de realm de la plataforma —{@code mto-viewer},
 * {@code mto-warehouse-admin}, {@code mto-maintenance-manager}, {@code mto-users-admin},
 * {@code mto-ops}...— que agrupa roles de cliente de uno o varios servicios. Los define cada
 * servicio en su partial import y Keycloak los expande en {@code resource_access} al emitir el
 * token, así que asignar un perfil es asignar un único rol de realm. Este servicio no persiste
 * nada: los perfiles son los roles de realm cuyo nombre empieza por {@code prefix}, menos los que
 * figuren en {@code excluded}.</p>
 *
 * @param prefix   prefijo de los roles de realm que se consideran perfiles
 * @param excluded roles de realm con ese prefijo que NO se administran desde aquí
 */
@Validated
@ConfigurationProperties(prefix = "app.profiles")
public record ProfileProperties(
        @DefaultValue("mto-") @NotBlank String prefix,
        @DefaultValue List<String> excluded
) {

    public boolean isProfile(String realmRoleName) {
        return realmRoleName != null
                && realmRoleName.startsWith(prefix)
                && (excluded == null || !excluded.contains(realmRoleName));
    }
}
