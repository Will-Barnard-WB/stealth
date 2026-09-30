package com.example.eol;

import java.time.LocalDate;
import java.util.List;

public class ReportJob {

    public List<String> run(LocalDate on) {
        return List.of("report for " + on);
    }
}
