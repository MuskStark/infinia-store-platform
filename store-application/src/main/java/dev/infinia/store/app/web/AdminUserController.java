package dev.infinia.store.app.web;

import dev.infinia.store.app.service.AdminUserService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.contract.api.AccountDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform-admin user management (design §12.4 管理 · 用户管理): browse every
 * account with its Infinia Level (Infinia Level), status and roles; promote/demote levels,
 * disable/enable accounts. Guarded by the /api/v1/admin/** PLATFORM_ADMIN rule.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    private final AdminUserService users;
    private final CurrentPrincipal principal;

    AdminUserController(AdminUserService users, CurrentPrincipal principal) {
        this.users = users;
        this.principal = principal;
    }

    @GetMapping
    public List<AccountDtos.AdminUserDto> listUsers() {
        principal.require();
        return users.listUsers();
    }

    /** Partial update: bee level, status (ACTIVE/DISABLED), roles, display name. */
    @PutMapping("/{userId}")
    public AccountDtos.AdminUserDto updateUser(@PathVariable UUID userId,
            @RequestBody AccountDtos.UpdateAdminUserRequest request) {
        UUID admin = principal.requireUserId();
        return users.updateUser(admin, userId, request);
    }
}
