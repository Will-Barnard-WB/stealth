package dev.stealth.core.impact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What changed in a library's API between two versions, as far as code compiled against the old one
 * is concerned: classes and members that are gone (a changed signature shows up as the old one
 * gone), and ones newly deprecated.
 *
 * @param removedMembers by class, member keys as in {@link ApiSurface.ApiClass#members()}
 * @param deprecatedMembers by class, members deprecated in the new version but not the old
 */
record ApiChanges(
        Set<String> removedClasses,
        Map<String, Set<String>> removedMembers,
        Set<String> deprecatedClasses,
        Map<String, Set<String>> deprecatedMembers) {

    static ApiChanges between(ApiSurface before, ApiSurface after) {
        Set<String> removedClasses = new HashSet<>();
        Map<String, Set<String>> removedMembers = new HashMap<>();
        Set<String> deprecatedClasses = new HashSet<>();
        Map<String, Set<String>> deprecatedMembers = new HashMap<>();
        for (ApiSurface.ApiClass was : before.classes().values()) {
            ApiSurface.ApiClass now = after.classes().get(was.name());
            if (now == null) {
                removedClasses.add(was.name());
                continue;
            }
            if (now.deprecated() && !was.deprecated()) {
                deprecatedClasses.add(was.name());
            }
            for (Map.Entry<String, Boolean> member : was.members().entrySet()) {
                Boolean deprecatedNow = now.members().get(member.getKey());
                if (deprecatedNow == null) {
                    removedMembers
                            .computeIfAbsent(was.name(), c -> new HashSet<>())
                            .add(member.getKey());
                } else if (deprecatedNow && !member.getValue()) {
                    deprecatedMembers
                            .computeIfAbsent(was.name(), c -> new HashSet<>())
                            .add(member.getKey());
                }
            }
        }
        return new ApiChanges(removedClasses, removedMembers, deprecatedClasses, deprecatedMembers);
    }

    boolean isEmpty() {
        return removedClasses.isEmpty()
                && removedMembers.isEmpty()
                && deprecatedClasses.isEmpty()
                && deprecatedMembers.isEmpty();
    }
}
