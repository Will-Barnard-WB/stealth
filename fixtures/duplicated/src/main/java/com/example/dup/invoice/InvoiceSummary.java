package com.example.dup.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/** Copy-pasted summary logic, identical to ReportSummary: above the duplication threshold. */
public class InvoiceSummary {

    public String summarise(List<BigDecimal> amounts, Locale locale) {
        if (amounts == null || amounts.isEmpty()) {
            return "No amounts";
        }
        NumberFormat currency = NumberFormat.getCurrencyInstance(locale);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal largest = amounts.get(0);
        BigDecimal smallest = amounts.get(0);
        for (BigDecimal amount : amounts) {
            total = total.add(amount);
            if (amount.compareTo(largest) > 0) {
                largest = amount;
            }
            if (amount.compareTo(smallest) < 0) {
                smallest = amount;
            }
        }
        BigDecimal average = total.divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
        StringBuilder summary = new StringBuilder();
        summary.append("Total: ").append(currency.format(total)).append('\n');
        summary.append("Average: ").append(currency.format(average)).append('\n');
        summary.append("Largest: ").append(currency.format(largest)).append('\n');
        summary.append("Smallest: ").append(currency.format(smallest)).append('\n');
        return summary.toString();
    }
}
