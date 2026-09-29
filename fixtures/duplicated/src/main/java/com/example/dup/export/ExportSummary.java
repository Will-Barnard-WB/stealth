package com.example.dup.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/** Same logic as InvoiceSummary with every variable renamed: not flagged while identifiers are compared. */
public class ExportSummary {

    public String describe(List<BigDecimal> values, Locale locale) {
        if (values == null || values.isEmpty()) {
            return "No values";
        }
        NumberFormat money = NumberFormat.getCurrencyInstance(locale);
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal max = values.get(0);
        BigDecimal min = values.get(0);
        for (BigDecimal value : values) {
            sum = sum.add(value);
            if (value.compareTo(max) > 0) {
                max = value;
            }
            if (value.compareTo(min) < 0) {
                min = value;
            }
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
        StringBuilder out = new StringBuilder();
        out.append("Total: ").append(money.format(sum)).append('\n');
        out.append("Average: ").append(money.format(mean)).append('\n');
        out.append("Largest: ").append(money.format(max)).append('\n');
        out.append("Smallest: ").append(money.format(min)).append('\n');
        return out.toString();
    }
}
