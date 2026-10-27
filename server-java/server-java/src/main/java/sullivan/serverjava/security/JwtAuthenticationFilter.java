package sullivan.serverjava.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sullivan.serverjava.service.AdminAccessService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final AdminAccessService adminAccessService;

    public JwtAuthenticationFilter(JwtService jwtService, AdminAccessService adminAccessService) {
        this.jwtService = jwtService;
        this.adminAccessService = adminAccessService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                AuthPrincipal principal = jwtService.parse(authorization.substring(7));
                // 管理员的角色/权限不信任 JWT 声明（登录后授予的角色要立刻生效、撤销要立刻失效），
                // 逐请求从库中现算；账号被禁用或已删除时 resolveAdminAccess 返回 null，按未认证处理。
                // 自然人/机构的角色来自 user_role 关系且不走 RBAC 权限，维持 JWT 声明即可。
                if ("ADMIN".equals(principal.principalType())) {
                    principal = resolveAdmin(principal);
                }
                if (principal != null) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, authorities(principal)));
                } else {
                    SecurityContextHolder.clearContext();
                }
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 用库里的实时角色/权限替换 JWT 声明；账号不存在或已禁用返回 null。 */
    private AuthPrincipal resolveAdmin(AuthPrincipal fromJwt) {
        AdminAccessService.AdminAccessSnapshot snapshot =
                adminAccessService.resolveAdminAccess(fromJwt.id());
        if (snapshot == null) {
            return null;
        }
        return new AuthPrincipal(fromJwt.id(), "ADMIN", snapshot.roles(), snapshot.permissions());
    }

    private List<SimpleGrantedAuthority> authorities(AuthPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        Set<String> roles = principal.roles();
        Set<String> permissions = principal.permissions();
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(permission ->
                authorities.add(new SimpleGrantedAuthority("PERM_" + permission)));
        return authorities;
    }
}
