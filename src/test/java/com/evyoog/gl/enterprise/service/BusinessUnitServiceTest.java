package com.evyoog.gl.enterprise.service;

import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.common.exception.ValidationException;
import com.evyoog.gl.enterprise.domain.BusinessUnit;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.dto.BusinessUnitResponse;
import com.evyoog.gl.enterprise.dto.CreateBusinessUnitRequest;
import com.evyoog.gl.enterprise.mapper.BusinessUnitMapper;
import com.evyoog.gl.enterprise.repository.BusinessUnitRepository;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessUnitServiceTest {

    @Mock
    private BusinessUnitRepository repository;
    @Mock
    private LegalEntityRepository legalEntityRepository;
    @Mock
    private BusinessUnitMapper mapper;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private BusinessUnitService service;

    @Test
    void create_whenGstinFormatInvalid_shouldThrow400() {
        UUID legalEntityId = UUID.randomUUID();
        LegalEntity legalEntity = new LegalEntity();
        legalEntity.setId(legalEntityId);
        CreateBusinessUnitRequest request = new CreateBusinessUnitRequest(legalEntityId, "BU-001", "Coimbatore Plant", "NOT-A-GSTIN", null);

        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_GSTIN");
    }

    @Test
    void create_whenGstinAlreadyUsed_shouldThrowDuplicateResourceException() {
        UUID legalEntityId = UUID.randomUUID();
        LegalEntity legalEntity = new LegalEntity();
        legalEntity.setId(legalEntityId);
        String gstin = "33AABCE1234F1Z5";
        CreateBusinessUnitRequest request = new CreateBusinessUnitRequest(legalEntityId, "BU-001", "Coimbatore Plant", gstin, null);

        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
        when(repository.existsByGstinAndLegalEntityIdNot(gstin, legalEntityId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_GSTIN");
    }

    @Test
    void create_whenSameGstinSameLegalEntity_shouldSucceed() {
        UUID legalEntityId = UUID.randomUUID();
        LegalEntity legalEntity = new LegalEntity();
        legalEntity.setId(legalEntityId);
        String gstin = "33AABCE1234F1Z5";
        CreateBusinessUnitRequest request = new CreateBusinessUnitRequest(legalEntityId, "BU-002", "Coimbatore Plant 2", gstin, null);
        BusinessUnit entity = new BusinessUnit();
        BusinessUnit saved = BusinessUnit.builder().code("BU-002").name("Coimbatore Plant 2").gstin(gstin).stateCode("33").build();
        saved.setId(UUID.randomUUID());
        BusinessUnitResponse response = new BusinessUnitResponse(
                saved.getId(), legalEntityId, "BU-002", "Coimbatore Plant 2", gstin, "33", true, Instant.now(), Instant.now());

        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
        // Another Business Unit under the SAME Legal Entity already holds this GSTIN — existsByGstinAndLegalEntityIdNot
        // excludes that LE, so this must return false (no cross-LE conflict).
        when(repository.existsByGstinAndLegalEntityIdNot(gstin, legalEntityId)).thenReturn(false);
        when(repository.existsByLegalEntityIdAndCode(legalEntityId, "BU-002")).thenReturn(false);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(response);

        BusinessUnitResponse result = service.create(request, "prashanth");

        assertThat(result.gstin()).isEqualTo(gstin);
        assertThat(result.legalEntityId()).isEqualTo(legalEntityId);
    }

    @Test
    void create_whenValid_shouldDeriveStateCodeFromGstinAndPersist() {
        UUID legalEntityId = UUID.randomUUID();
        LegalEntity legalEntity = new LegalEntity();
        legalEntity.setId(legalEntityId);
        String gstin = "33AABCE1234F1Z5";
        CreateBusinessUnitRequest request = new CreateBusinessUnitRequest(legalEntityId, "BU-001", "Coimbatore Plant", gstin, null);
        BusinessUnit entity = new BusinessUnit();
        BusinessUnit saved = BusinessUnit.builder().code("BU-001").name("Coimbatore Plant").gstin(gstin).stateCode("33").build();
        saved.setId(UUID.randomUUID());
        BusinessUnitResponse response = new BusinessUnitResponse(
                saved.getId(), legalEntityId, "BU-001", "Coimbatore Plant", gstin, "33", true, Instant.now(), Instant.now());

        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
        when(repository.existsByGstinAndLegalEntityIdNot(gstin, legalEntityId)).thenReturn(false);
        when(repository.existsByLegalEntityIdAndCode(legalEntityId, "BU-001")).thenReturn(false);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(response);

        BusinessUnitResponse result = service.create(request, "prashanth");

        assertThat(result.stateCode()).isEqualTo("33");
        assertThat(entity.getStateCode()).isEqualTo("33");
    }

    @Test
    void create_whenLegalEntityMissing_shouldThrowResourceNotFoundException() {
        UUID legalEntityId = UUID.randomUUID();
        CreateBusinessUnitRequest request = new CreateBusinessUnitRequest(legalEntityId, "BU-001", "Orphan Plant", null, null);
        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
