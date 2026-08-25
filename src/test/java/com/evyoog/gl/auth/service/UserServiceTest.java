package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.User;
import com.evyoog.gl.auth.dto.UpdateUserRequest;
import com.evyoog.gl.auth.dto.UserResponse;
import com.evyoog.gl.auth.repository.RoleRepository;
import com.evyoog.gl.auth.repository.UserRepository;
import com.evyoog.gl.auth.repository.UserRoleRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    @InjectMocks
    private UserService service;

    @Test
    void testUpdateUser_populatesUpdatedBy() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("someone@evyoog.com")
                .fullName("Old Name")
                .isActive(true)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateUserRequest request = new UpdateUserRequest("New Name", false);

        UserResponse response = service.updateUser(userId, request, "admin@evyoog.com");

        assertThat(response.fullName()).isEqualTo("New Name");
        assertThat(response.isActive()).isFalse();
        assertThat(response.updatedBy()).isEqualTo("admin@evyoog.com");
        assertThat(user.getUpdatedBy()).isEqualTo("admin@evyoog.com");
    }

    @Test
    void testUpdateUser_partialRequest_leavesUnsetFieldsUnchanged() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("someone@evyoog.com")
                .fullName("Existing Name")
                .isActive(true)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateUserRequest request = new UpdateUserRequest(null, null);

        UserResponse response = service.updateUser(userId, request, "admin@evyoog.com");

        assertThat(response.fullName()).isEqualTo("Existing Name");
        assertThat(response.isActive()).isTrue();
        assertThat(response.updatedBy()).isEqualTo("admin@evyoog.com");
    }

    @Test
    void testUpdateUser_notFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        UpdateUserRequest request = new UpdateUserRequest("New Name", null);

        assertThatThrownBy(() -> service.updateUser(userId, request, "admin@evyoog.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
