package dev.stealth.core;

import java.util.List;

/** Runs several analyzers in order and combines their findings. */
public class CompositeAnalyzer implements Analyzer {

    private final List<Analyzer> analyzers;

    public CompositeAnalyzer(List<Analyzer> analyzers) {
        this.analyzers = List.copyOf(analyzers);
    }

    @Override
    public List<Finding> analyze(RepoContext context) {
        return analyzers.stream().flatMap(analyzer -> analyzer.analyze(context).stream()).toList();
    }
}
