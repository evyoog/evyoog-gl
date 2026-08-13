package com.evyoog.gl.coa.mapper;

import com.evyoog.gl.coa.dto.CoaSegmentSummary;
import com.evyoog.gl.coa.dto.CreateCoaSegmentRequest;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CoaStructureMapper {

    @Mapping(source = "entity.displayOrder", target = "segmentNumber")
    @Mapping(source = "entity.required", target = "isRequired")
    @Mapping(source = "valueCount", target = "valueCount")
    CoaSegmentSummary toSegmentSummary(FinanceDimension entity, long valueCount);

    @Mapping(target = "displayOrder", ignore = true)
    @Mapping(target = "isRequired", ignore = true)
    FinanceDimension toSegmentEntity(CreateCoaSegmentRequest request);
}
