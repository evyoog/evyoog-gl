package com.evyoog.gl.dimension.mapper;

import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.dto.CreateFinanceDimensionRequest;
import com.evyoog.gl.dimension.dto.FinanceDimensionResponse;
import com.evyoog.gl.dimension.dto.UpdateFinanceDimensionRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface FinanceDimensionMapper {

    @Mapping(target = "isRequired", ignore = true)
    @Mapping(target = "displayOrder", ignore = true)
    @Mapping(target = "isBalancing", ignore = true)
    FinanceDimension toEntity(CreateFinanceDimensionRequest request);

    @Mapping(source = "entity.ledger.id", target = "ledgerId")
    @Mapping(source = "entity.ledger.name", target = "ledgerName")
    @Mapping(source = "entity.coaStructure.id", target = "coaStructureId")
    @Mapping(source = "entity.active", target = "isActive")
    @Mapping(source = "entity.required", target = "isRequired")
    @Mapping(source = "entity.balancing", target = "isBalancing")
    @Mapping(source = "valueCount", target = "valueCount")
    FinanceDimensionResponse toResponse(FinanceDimension entity, long valueCount);

    @Mapping(target = "required", ignore = true)
    @Mapping(target = "balancing", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateFromRequest(UpdateFinanceDimensionRequest request, @MappingTarget FinanceDimension entity);
}
