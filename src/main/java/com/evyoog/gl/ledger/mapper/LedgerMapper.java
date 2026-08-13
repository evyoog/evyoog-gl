package com.evyoog.gl.ledger.mapper;

import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.dto.CreateLedgerRequest;
import com.evyoog.gl.ledger.dto.LedgerResponse;
import com.evyoog.gl.ledger.dto.UpdateLedgerRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface LedgerMapper {

    Ledger toEntity(CreateLedgerRequest request);

    @Mapping(source = "active", target = "isActive")
    @Mapping(source = "coaStructure.id", target = "coaStructureId")
    LedgerResponse toResponse(Ledger entity);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateFromRequest(UpdateLedgerRequest request, @MappingTarget Ledger entity);
}
