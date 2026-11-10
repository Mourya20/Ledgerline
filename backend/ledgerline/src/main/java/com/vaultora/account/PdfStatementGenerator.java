package com.vaultora.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.vaultora.domain.AccountEntity;

public class PdfStatementGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    public record StatementRow(
            Instant date,
            String transactionId,
            String type,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal runningBalance
    ) {}

    public static byte[] generate(
            AccountEntity account,
            Instant startDate,
            Instant endDate,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            List<StatementRow> rows) {

        StringBuilder stream = new StringBuilder();

        // Canvas dimensions: A4 = 595 x 842
        // Header background banner
        stream.append("0.05 0.43 0.39 rg\n"); // Teal #0D6E64
        stream.append("0 762 595 80 re f\n");

        // Bank Title
        stream.append("BT\n");
        stream.append("/F2 22 Tf\n");
        stream.append("1 1 1 rg\n"); // White
        stream.append("36 805 Td\n");
        stream.append("(VAULTORA BANKING) Tj\n");
        stream.append("ET\n");

        stream.append("BT\n");
        stream.append("/F1 10 Tf\n");
        stream.append("0.85 0.95 0.94 rg\n");
        stream.append("36 788 Td\n");
        stream.append("(Official Account Statement - Generated via Vaultora Core Ledger) Tj\n");
        stream.append("ET\n");

        // Account Information section
        stream.append("0.96 0.97 0.98 rg\n"); // #F3F4F6
        stream.append("36 675 523 70 re f\n");
        stream.append("0.85 0.87 0.90 RG\n");
        stream.append("36 675 523 70 re S\n");

        stream.append("BT\n");
        stream.append("/F2 10 Tf\n");
        stream.append("0.1 0.1 0.1 rg\n");
        stream.append("50 725 Td\n");
        stream.append("(Account Number: ").append(escapePdf(account.getAccountNumber())).append(") Tj\n");
        stream.append("250 0 Td\n");
        stream.append("(Currency: ").append(escapePdf(account.getCurrency())).append(") Tj\n");
        stream.append("ET\n");

        String ownerEmail = account.getOwner() != null ? account.getOwner().getEmail() : "N/A";
        stream.append("BT\n");
        stream.append("/F1 10 Tf\n");
        stream.append("0.2 0.2 0.2 rg\n");
        stream.append("50 705 Td\n");
        stream.append("(Account Holder: ").append(escapePdf(ownerEmail)).append(") Tj\n");
        stream.append("250 0 Td\n");
        stream.append("(Account Status: ").append(escapePdf(account.getStatus().name())).append(") Tj\n");
        stream.append("ET\n");

        String periodText = (startDate != null ? DAY_FMT.format(startDate) : "Genesis")
                + " to "
                + (endDate != null ? DAY_FMT.format(endDate) : "Present");
        stream.append("BT\n");
        stream.append("/F1 9 Tf\n");
        stream.append("0.35 0.35 0.35 rg\n");
        stream.append("50 687 Td\n");
        stream.append("(Statement Period: ").append(escapePdf(periodText)).append(") Tj\n");
        stream.append("ET\n");

        // Summary Statistics Box
        stream.append("BT\n");
        stream.append("/F2 10 Tf\n");
        stream.append("0.1 0.1 0.1 rg\n");
        stream.append("36 650 Td\n");
        stream.append("(ACCOUNT SUMMARY) Tj\n");
        stream.append("ET\n");

        // 4 mini summary cards
        int boxW = 125;
        int boxH = 40;
        int boxY = 600;

        // Opening Balance
        stream.append("0.97 0.98 0.99 rg 36 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re f\n");
        stream.append("0.85 0.87 0.90 RG 36 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re S\n");
        stream.append("BT /F1 8 Tf 0.4 0.4 0.4 rg 44 ").append(boxY + 26).append(" Td (Opening Balance) Tj ET\n");
        stream.append("BT /F2 10 Tf 0.1 0.1 0.1 rg 44 ").append(boxY + 10).append(" Td (").append(escapePdf(formatCurrency(openingBalance))).append(") Tj ET\n");

        // Total Deposits
        stream.append("0.97 0.98 0.99 rg 168 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re f\n");
        stream.append("0.85 0.87 0.90 RG 168 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re S\n");
        stream.append("BT /F1 8 Tf 0.4 0.4 0.4 rg 176 ").append(boxY + 26).append(" Td (Total Deposits) Tj ET\n");
        stream.append("BT /F2 10 Tf 0.05 0.5 0.25 rg 176 ").append(boxY + 10).append(" Td (+").append(escapePdf(formatCurrency(totalCredits))).append(") Tj ET\n");

        // Total Withdrawals
        stream.append("0.97 0.98 0.99 rg 300 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re f\n");
        stream.append("0.85 0.87 0.90 RG 300 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re S\n");
        stream.append("BT /F1 8 Tf 0.4 0.4 0.4 rg 308 ").append(boxY + 26).append(" Td (Total Withdrawals) Tj ET\n");
        stream.append("BT /F2 10 Tf 0.8 0.15 0.15 rg 308 ").append(boxY + 10).append(" Td (-").append(escapePdf(formatCurrency(totalDebits))).append(") Tj ET\n");

        // Closing Balance
        stream.append("0.95 0.98 0.97 rg 432 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re f\n");
        stream.append("0.05 0.43 0.39 RG 432 ").append(boxY).append(" ").append(boxW).append(" ").append(boxH).append(" re S\n");
        stream.append("BT /F1 8 Tf 0.05 0.43 0.39 rg 440 ").append(boxY + 26).append(" Td (Closing Balance) Tj ET\n");
        stream.append("BT /F2 11 Tf 0.05 0.43 0.39 rg 440 ").append(boxY + 10).append(" Td (").append(escapePdf(formatCurrency(closingBalance))).append(") Tj ET\n");

        // Table Header
        int tableTop = 575;
        stream.append("0.05 0.43 0.39 rg 36 ").append(tableTop).append(" 523 20 re f\n");
        stream.append("BT /F2 9 Tf 1 1 1 rg\n");
        stream.append("42 ").append(tableTop + 6).append(" Td (Date (UTC)) Tj\n");
        stream.append("100 0 Td (Type) Tj\n");
        stream.append("80 0 Td (Debit) Tj\n");
        stream.append("80 0 Td (Credit) Tj\n");
        stream.append("90 0 Td (Balance) Tj\n");
        stream.append("ET\n");

        int currentY = tableTop - 18;
        int rowLimit = Math.min(rows.size(), 30); // Single page fit up to 30 rows
        for (int i = 0; i < rowLimit; i++) {
            StatementRow row = rows.get(i);
            if (i % 2 == 1) {
                stream.append("0.98 0.98 0.98 rg 36 ").append(currentY - 4).append(" 523 16 re f\n");
            }
            stream.append("0.92 0.92 0.92 RG 36 ").append(currentY - 4).append(" 523 16 re S\n");

            stream.append("BT /F1 8 Tf 0.15 0.15 0.15 rg\n");
            stream.append("42 ").append(currentY).append(" Td (").append(escapePdf(DATE_FMT.format(row.date()))).append(") Tj\n");
            stream.append("100 0 Td (").append(escapePdf(row.type())).append(") Tj\n");

            String debitStr = (row.debit() != null && row.debit().compareTo(BigDecimal.ZERO) > 0)
                    ? formatCurrency(row.debit()) : "-";
            String creditStr = (row.credit() != null && row.credit().compareTo(BigDecimal.ZERO) > 0)
                    ? formatCurrency(row.credit()) : "-";
            String balStr = formatCurrency(row.runningBalance());

            stream.append("80 0 Td (").append(escapePdf(debitStr)).append(") Tj\n");
            stream.append("80 0 Td (").append(escapePdf(creditStr)).append(") Tj\n");
            stream.append("90 0 Td (").append(escapePdf(balStr)).append(") Tj\n");
            stream.append("ET\n");

            currentY -= 17;
        }

        if (rows.isEmpty()) {
            stream.append("BT /F1 9 Tf 0.5 0.5 0.5 rg 200 ").append(tableTop - 30).append(" Td (No transactions in the selected period.) Tj ET\n");
        }

        // Footer
        stream.append("0.85 0.87 0.90 RG 36 40 523 0.5 re S\n");
        stream.append("BT /F1 8 Tf 0.5 0.5 0.5 rg 36 28 Td (Vaultora Banking System - Confidential Financial Document - Page 1 of 1) Tj ET\n");

        return buildPdf(stream.toString());
    }

    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String escapePdf(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("(", "\\(")
                   .replace(")", "\\)");
    }

    private static byte[] buildPdf(String contentStream) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Long> offsets = new ArrayList<>();

            out.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(StandardCharsets.ISO_8859_1));

            // Object 1: Catalog
            offsets.add((long) out.size());
            out.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

            // Object 2: Pages
            offsets.add((long) out.size());
            out.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

            // Object 3: Page
            offsets.add((long) out.size());
            out.write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 6 0 R /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> >>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

            // Object 4: Font F1 (Helvetica)
            offsets.add((long) out.size());
            out.write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

            // Object 5: Font F2 (Helvetica-Bold)
            offsets.add((long) out.size());
            out.write("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

            // Object 6: Content stream
            byte[] streamBytes = contentStream.getBytes(StandardCharsets.ISO_8859_1);
            offsets.add((long) out.size());
            out.write(("6 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(streamBytes);
            out.write("\nendstream\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

            // Cross-reference table
            long xrefOffset = (long) out.size();
            out.write(("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n").getBytes(StandardCharsets.ISO_8859_1));
            for (Long offset : offsets) {
                out.write(String.format("%010d 00000 n \n", offset).getBytes(StandardCharsets.ISO_8859_1));
            }

            // Trailer
            out.write(("trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate PDF statement", e);
        }
    }
}
