package com.vaultora.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.vaultora.audit.AuditorInspectionService;
import com.vaultora.domain.AccountEntity;

public class CsvStatementGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    public static String generate(
            AccountEntity account,
            Instant startDate,
            Instant endDate,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            List<PdfStatementGenerator.StatementRow> rows) {

        StringBuilder csv = new StringBuilder();

        csv.append("VAULTORA BANKING ACCOUNT STATEMENT\r\n");
        csv.append("Account Number,").append(AuditorInspectionService.escapeCsvCell(account.getAccountNumber())).append("\r\n");
        csv.append("Account Holder,").append(AuditorInspectionService.escapeCsvCell(account.getOwner() != null ? account.getOwner().getEmail() : "N/A")).append("\r\n");
        csv.append("Currency,").append(AuditorInspectionService.escapeCsvCell(account.getCurrency())).append("\r\n");
        csv.append("Status,").append(AuditorInspectionService.escapeCsvCell(account.getStatus().name())).append("\r\n");

        String periodText = (startDate != null ? DAY_FMT.format(startDate) : "Genesis") + " to " + (endDate != null ? DAY_FMT.format(endDate) : "Present");
        csv.append("Statement Period,").append(AuditorInspectionService.escapeCsvCell(periodText)).append("\r\n");
        csv.append("Opening Balance,").append(AuditorInspectionService.escapeCsvCell(openingBalance.toPlainString())).append("\r\n");
        csv.append("Total Deposits,").append(AuditorInspectionService.escapeCsvCell(totalCredits.toPlainString())).append("\r\n");
        csv.append("Total Withdrawals,").append(AuditorInspectionService.escapeCsvCell(totalDebits.toPlainString())).append("\r\n");
        csv.append("Closing Balance,").append(AuditorInspectionService.escapeCsvCell(closingBalance.toPlainString())).append("\r\n");
        csv.append("\r\n");

        // Table Header
        csv.append("Date (UTC),Transaction ID,Type,Debit,Credit,Running Balance\r\n");

        for (PdfStatementGenerator.StatementRow row : rows) {
            String dateStr = DATE_FMT.format(row.date());
            String txIdStr = row.transactionId() != null ? row.transactionId() : "";
            String typeStr = row.type() != null ? row.type() : "";
            String debitStr = (row.debit() != null && row.debit().compareTo(BigDecimal.ZERO) > 0) ? row.debit().toPlainString() : "";
            String creditStr = (row.credit() != null && row.credit().compareTo(BigDecimal.ZERO) > 0) ? row.credit().toPlainString() : "";
            String balStr = row.runningBalance() != null ? row.runningBalance().toPlainString() : "0.00";

            csv.append(AuditorInspectionService.escapeCsvCell(dateStr)).append(",");
            csv.append(AuditorInspectionService.escapeCsvCell(txIdStr)).append(",");
            csv.append(AuditorInspectionService.escapeCsvCell(typeStr)).append(",");
            csv.append(AuditorInspectionService.escapeCsvCell(debitStr)).append(",");
            csv.append(AuditorInspectionService.escapeCsvCell(creditStr)).append(",");
            csv.append(AuditorInspectionService.escapeCsvCell(balStr)).append("\r\n");
        }

        return csv.toString();
    }
}
