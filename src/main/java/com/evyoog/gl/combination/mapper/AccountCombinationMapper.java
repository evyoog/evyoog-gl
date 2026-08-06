package com.evyoog.gl.combination.mapper;

import com.evyoog.gl.combination.domain.AccountCombination;
import com.evyoog.gl.combination.dto.AccountCombinationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AccountCombinationMapper {

    @Mapping(source = "entity.ledger.id", target = "ledgerId")
    @Mapping(source = "entity.ledger.name", target = "ledgerName")
    @Mapping(source = "entity.legalEntity.id", target = "legalEntityId")
    @Mapping(source = "entity.legalEntity.name", target = "legalEntityName")
    @Mapping(source = "entity.active", target = "isActive")
    @Mapping(source = "entity.dynamic", target = "isDynamic")
    AccountCombinationResponse toResponse(AccountCombination entity);
}
