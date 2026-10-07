package dev.stealth.core.impact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Every reference the repository's compiled code makes to another class or member, with the source
 * line it's on: method calls, field accesses, {@code new}, casts, class literals, supertypes and
 * the types in signatures. Read with ASM from {@code target/classes} and {@code
 * target/test-classes}.
 */
final class UsageScanner {

    /**
     * @param owner the referenced class (internal name)
     * @param member the referenced member key, or null for a reference to the class itself
     * @param source the source file, repo-relative, as best it can be found
     * @param method the enclosing method ({@code Class#method}), or null outside a method body
     */
    record Reference(String owner, String member, String source, int line, String method) {}

    private UsageScanner() {}

    /** Compiled class directories under {@code root}: each module's main and test output. */
    static List<Path> classDirectories(Path root) throws IOException {
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isDirectory)
                    .filter(
                            p ->
                                    p.endsWith(Path.of("target", "classes"))
                                            || p.endsWith(Path.of("target", "test-classes")))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .forEach(directories::add);
        }
        return directories;
    }

    static List<Reference> scan(Path root, List<Path> classDirectories) throws IOException {
        List<Reference> references = new ArrayList<>();
        for (Path directory : classDirectories) {
            Path module = directory.getParent().getParent();
            boolean test = directory.endsWith(Path.of("target", "test-classes"));
            Path sources = module.resolve(test ? "src/test/java" : "src/main/java");
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    try (InputStream in = Files.newInputStream(file)) {
                        scanClass(new ClassReader(in), root, sources, references);
                    }
                }
            }
        }
        return references;
    }

    private static void scanClass(
            ClassReader reader, Path root, Path sources, List<Reference> references) {
        String[] source = {null};
        String[] className = {null};
        int[] firstLine = {1};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        className[0] = name;
                        supertypes.clear();
                        if (superName != null) {
                            supertypes.add(superName);
                        }
                        if (interfaces != null) {
                            supertypes.addAll(List.of(interfaces));
                        }
                        // Supertypes are recorded once the source file is known
                        pendingSupers.clear();
                        if (superName != null) {
                            pendingSupers.add(superName);
                        }
                        if (interfaces != null) {
                            pendingSupers.addAll(List.of(interfaces));
                        }
                    }

                    private final List<String> pendingSupers = new ArrayList<>();
                    private final List<String> supertypes = new ArrayList<>();
                    private final String[] current = {null};

                    @Override
                    public void visitSource(String file, String debug) {
                        String packagePath =
                                className[0].contains("/")
                                        ? className[0].substring(
                                                0, className[0].lastIndexOf('/') + 1)
                                        : "";
                        Path candidate = sources.resolve(packagePath + (file == null ? "" : file));
                        source[0] =
                                root.relativize(
                                                Files.exists(candidate)
                                                        ? candidate
                                                        : sources.resolve(packagePath))
                                        .toString()
                                        .replace('\\', '/');
                    }

                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            Object value) {
                        // Fields come before any line number: recorded at the class's line, below
                        pendingSupers.addAll(typesIn(descriptor));
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        int[] line = {0};
                        String simpleClass =
                                className[0].substring(className[0].lastIndexOf('/') + 1);
                        current[0] =
                                simpleClass.replace('$', '.')
                                        + "#"
                                        + (name.equals("<init>") ? "new" : name);
                        boolean overridable =
                                (access
                                                        & (Opcodes.ACC_PRIVATE
                                                                | Opcodes.ACC_STATIC
                                                                | Opcodes.ACC_SYNTHETIC
                                                                | Opcodes.ACC_BRIDGE))
                                                == 0
                                        && !name.startsWith("<");
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitEnd() {
                                int at = line[0] == 0 ? firstLine[0] : methodStart[0];
                                // The signature's types, at the method's first line
                                for (String type : typesIn(descriptor)) {
                                    add(type, null, at);
                                }
                                // An override of a supertype's method: if that method is gone (or
                                // its
                                // signature changed), this no longer overrides it and won't compile
                                if (overridable) {
                                    for (String type : supertypes) {
                                        add(type, name + descriptor, at);
                                    }
                                }
                                current[0] = null;
                            }

                            private final int[] methodStart = {0};

                            @Override
                            public void visitLineNumber(int number, Label start) {
                                line[0] = number;
                                if (methodStart[0] == 0) {
                                    methodStart[0] = number;
                                }
                                if (firstLine[0] == 1 || number < firstLine[0]) {
                                    firstLine[0] = number;
                                }
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode,
                                    String owner,
                                    String name,
                                    String descriptor,
                                    boolean isInterface) {
                                add(owner, name + descriptor, line[0]);
                                add(owner, null, line[0]);
                            }

                            @Override
                            public void visitFieldInsn(
                                    int opcode, String owner, String name, String descriptor) {
                                add(owner, name + ":" + descriptor, line[0]);
                                add(owner, null, line[0]);
                            }

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                add(
                                        type.startsWith("[")
                                                ? Type.getType(type)
                                                        .getElementType()
                                                        .getInternalName()
                                                : type,
                                        null,
                                        line[0]);
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                                    add(type.getInternalName(), null, line[0]);
                                }
                            }

                            @Override
                            public void visitInvokeDynamicInsn(
                                    String name,
                                    String descriptor,
                                    Handle bootstrap,
                                    Object... arguments) {
                                for (Object argument : arguments) {
                                    if (argument instanceof Handle handle) {
                                        add(
                                                handle.getOwner(),
                                                handle.getName() + handle.getDesc(),
                                                line[0]);
                                    }
                                }
                            }
                        };
                    }

                    private void flushSupers() {
                        for (String type : pendingSupers) {
                            add(type, null, firstLine[0]);
                        }
                        pendingSupers.clear();
                    }

                    private List<String> typesIn(String descriptor) {
                        Type type = Type.getType(descriptor);
                        List<Type> all = new ArrayList<>();
                        if (type.getSort() == Type.METHOD) {
                            all.add(type.getReturnType());
                            all.addAll(List.of(type.getArgumentTypes()));
                        } else {
                            all.add(type);
                        }
                        List<String> names = new ArrayList<>();
                        for (Type t : all) {
                            Type element = t.getSort() == Type.ARRAY ? t.getElementType() : t;
                            if (element.getSort() == Type.OBJECT) {
                                names.add(element.getInternalName());
                            }
                        }
                        return names;
                    }

                    private void add(String owner, String member, int line) {
                        references.add(
                                new Reference(
                                        owner,
                                        member,
                                        source[0] == null ? className[0] : source[0],
                                        line,
                                        current[0]));
                    }

                    @Override
                    public void visitEnd() {
                        // Supertypes and field types, at the class's first line (where a default
                        // constructor, and so usually the declaration, is)
                        flushSupers();
                    }
                },
                ClassReader.SKIP_FRAMES);
    }
}
