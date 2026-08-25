package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.Role;
import com.evyoog.gl.auth.dto.RoleResponse;
import com.evyoog.gl.auth.dto.UpdateRoleRequest;
import com.evyoog.gl.auth.repository.PermissionRepository;
import com.evyoog.gl.auth.repository.RoleRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private AuditService auditService;

    @InjectMocks
    private RoleService service;

    @Test
    void testUpdateRole_populatesUpdatedBy() {
        UUID roleId = UUID.randomUUID();
        Role role = Role.builder()
                .id(roleId)
                .code("CUSTOM_ROLE")
                .name("Old Name")
                .isSystemRole(false)
                .build();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRoleRequest request = new UpdateRoleRequest("New Name", null, null, null);

        RoleResponse response = service.update(roleId, request, "admin@evyoog.com");

        assertThat(response.updatedBy()).isEqualTo("admin@evyoog.com");
        assertThat(role.getUpdatedBy()).isEqualTo("admin@evyoog.com");
    }

    @Test
    void testUpdateRole_systemRole_throwsSystemRoleImmutable() {
        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).code("GL_ADMIN").isSystemRole(true).build();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        UpdateRoleRequest request = new UpdateRoleRequest("New Name", null, null, null);

        assertThatThrownBy(() -> service.update(roleId, request, "someone"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "SYSTEM_ROLE_IMMUTABLE");
    }
}
