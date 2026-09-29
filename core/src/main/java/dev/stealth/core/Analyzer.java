package dev.stealth.core;

import java.util.List;

/** Checks a repository for one kind of tech or security debt. */
public interface Analyzer {

    List<Finding> analyze(RepoContext context);
}
