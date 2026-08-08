package com.evyoog.gl.dimension.mapper;

import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.dto.CreateDimensionValueRequest;
import com.evyoog.gl.dimension.dto.DimensionValueResponse;
import com.evyoog.gl.dimension.dto.UpdateDimensionValueRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface DimensionValueMapper {

    @Mapping(target = "isSummary", ignore = true)
    @Mapping(target = "isPostable", ignore = true)
    @Mapping(target = "gstApplicable", ignore = true)
    @Mapping(target = "tdsApplicable", ignore = true)
    @Mapping(target = "displayOrder", ignore = true)
    @Mapping(target = "isDefault", ignore = true)
    @Mapping(target = "normalBalance", ignore = true)
    @Mapping(target = "counterpartyLegalEntity", ignore = true)
    @Mapping(target = "budgetControlled", ignore = true)
    DimensionValue toEntity(CreateDimensionValueRequest request);

    @Mapping(source = "financeDimension.id", target = "financeDimensionId")
    @Mapping(source = "financeDimension.code", target = "dimensionCode")
    @Mapping(source = "financeDimension.name", target = "dimensionName")
    @Mapping(source = "financeDimension.dimensionType", target = "dimensionType")
    @Mapping(source = "parentValue.id", target = "parentValueId")
    @Mapping(source = "parentValue.code", target = "parentValueCode")
    @Mapping(source = "parentValue.name", target = "parentValueName")
    @Mapping(source = "active", target = "isActive")
    @Mapping(source = "summary", target = "isSummary")
    @Mapping(source = "postable", target = "isPostable")
    @Mapping(source = "default", target = "isDefault")
    @Mapping(source = "counterpartyLegalEntity.id", target = "counterpartyLegalEntityId")
    @Mapping(source = "counterpartyLegalEntity.name", target = "counterpartyLegalEntityName")
    DimensionValueResponse toResponse(DimensionValue entity);

    @Mapping(target = "summary", ignore = true)
    @Mapping(target = "postable", ignore = true)
    @Mapping(target = "default", ignore = true)
    @Mapping(target = "counterpartyLegalEntity", ignore = true)
    @Mapping(target = "budgetControlled", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateFromRequest(UpdateDimensionValueRequest request, @MappingTarget DimensionValue entity);
}
