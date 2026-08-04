package sullivan.serverjava.security;

import java.util.Set;

public record AuthPrincipal(String id, String principalType, Set<String> roles, Set<String> permissions) {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
