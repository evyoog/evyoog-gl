package com.evyoog.gl.reporting.ledger.service;

import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.posting.domain.AccountBalance;
import com.evyoog.gl.posting.domain.JournalHeader;
import com.evyoog.gl.posting.domain.JournalLine;
import com.evyoog.gl.posting.domain.JournalStatus;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.posting.repository.JournalHeaderRepository;
import com.evyoog.gl.posting.repository.JournalLineRepository;
import com.evyoog.gl.reporting.ledger.dto.AccountLedgerEntry;
import com.evyoog.gl.reporting.ledger.dto.AccountLedgerResponse;
import com.evyoog.gl.reporting.ledger.dto.JournalListingEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountLedgerService {

    private final AccountBalanceRepository accountBalanceRepository;
    private final JournalLineRepository journalLineRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final JournalHeaderRepository journalHeaderRepository;

    public AccountLedgerResponse getAccountLedger(UUID legalEntityId, UUID accountingPeriodId,
                                                   UUID naturalAccountValueId) {

        BigDecimal openingBalance = accountBalanceRepository
                .findByLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(
                        legalEntityId, accountingPeriodId, naturalAccountValueId)
                .stream()
                .map(AccountBalance::getBeginningBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<JournalLine> lines = journalLineRepository
                .findByNaturalAccountIdAndJournalHeader_LegalEntityIdAndJournalHeader_AccountingPeriodIdAndJournalHeader_Status(
                        naturalAccountValueId, legalEntityId, accountingPeriodId, JournalStatus.POSTED);

        lines.sort(Comparator
                .comparing((JournalLine l) -> l.getJournalHeader().getGlDate())
                .thenComparing(l -> l.getJournalHeader().getJournalNumber()));

        BigDecimal running = openingBalance;
        List<AccountLedgerEntry> entries = new ArrayList<>();

        for (JournalLine line : lines) {
            BigDecimal dr = line.getDebitAmount() != null ? line.getDebitAmount() : BigDecimal.ZERO;
            BigDecimal cr = line.getCreditAmount() != null ? line.getCreditAmount() : BigDecimal.ZERO;
            running = running.add(dr).subtract(cr);

            JournalHeader header = line.getJournalHeader();
            entries.add(AccountLedgerEntry.builder()
                    .lineId(line.getId())
                    .journalHeaderId(header.getId())
                    .journalNumber(header.getJournalNumber())
                    .glDate(header.getGlDate())
                    .accountingDate(header.getAccountingDate())
                    .journalDescription(header.getDescription())
                    .lineDescription(line.getDescription())
                    .journalSourceCode(header.getJournalSource().getCode())
                    .journalCategoryCode(header.getJournalCategory().getCode())
                    .debitAmount(line.getDebitAmount())
                    .creditAmount(line.getCreditAmount())
                    .runningBalance(running)
                    .gstApplicable(line.getGstApplicable())
                    .gstType(line.getGstType())
                    .tdsApplicable(line.getTdsApplicable())
                    .tdsSection(line.getTdsSection())
                    .accountCombination(line.getAccountCombination())
                    .createdAt(line.getCreatedAt())
                    .build());
        }

        BigDecimal totalDr = entries.stream()
                .map(AccountLedgerEntry::debitAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCr = entries.stream()
                .map(AccountLedgerEntry::creditAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DimensionValue account = dimensionValueRepository.findById(naturalAccountValueId)
                .orElseThrow(() -> new EvyoogException("ACCOUNT_NOT_FOUND",
                        "Account not found.", HttpStatus.NOT_FOUND));
        AccountingPeriod period = accountingPeriodRepository.findById(accountingPeriodId)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND",
                        "Accounting period not found.", HttpStatus.NOT_FOUND));
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new EvyoogException("LEGAL_ENTITY_NOT_FOUND",
                        "Legal Entity not found.", HttpStatus.NOT_FOUND));

        return AccountLedgerResponse.builder()
                .naturalAccountValueId(naturalAccountValueId)
                .accountCode(account.getCode())
                .accountName(account.getName())
                .accountQualifier(account.getAccountQualifier() != null
                        ? account.getAccountQualifier().name() : null)
                .normalBalance(account.getNormalBalance() != null
                        ? account.getNormalBalance().name() : null)
                .legalEntityId(legalEntityId)
                .legalEntityName(legalEntity.getName())
                .accountingPeriodId(accountingPeriodId)
                .periodName(period.getName())
                .fiscalYear(period.getFiscalYear())
                .openingBalance(openingBalance)
                .entries(entries)
                .totalDebits(totalDr)
                .totalCredits(totalCr)
                .closingBalance(openingBalance.add(totalDr).subtract(totalCr))
                .entryCount(entries.size())
                .build();
    }

    public Page<JournalListingEntry> getJournalListing(UUID legalEntityId, JournalStatus status, UUID periodId,
                                                        String sourceCode, LocalDate fromDate, LocalDate toDate,
                                                        Pageable pageable) {
        return journalHeaderRepository
                .search(legalEntityId, status, periodId, sourceCode, fromDate, toDate, pageable)
                .map(this::toListingEntry);
    }

    private JournalListingEntry toListingEntry(JournalHeader jh) {
        return JournalListingEntry.builder()
                .id(jh.getId())
                .journalNumber(jh.getJournalNumber())
                .legalEntityName(jh.getLegalEntity().getName())
                .ledgerName(jh.getLedger().getName())
                .periodName(jh.getAccountingPeriod().getName())
                .journalSourceCode(jh.getJournalSource().getCode())
                .journalCategoryCode(jh.getJournalCategory().getCode())
                .description(jh.getDescription())
                .glDate(jh.getGlDate())
                .totalDebit(jh.getTotalDebit())
                .totalCredit(jh.getTotalCredit())
                .status(jh.getStatus().name())
                .financeModeSnapshot(jh.getFinanceModeSnapshot().name())
                .postedAt(jh.getPostedAt())
                .createdAt(jh.getCreatedAt())
                .build();
    }
}
