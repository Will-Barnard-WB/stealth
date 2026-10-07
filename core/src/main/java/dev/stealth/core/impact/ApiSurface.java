package dev.stealth.core.impact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The public API of a jar: its public classes and their public and protected members, with what's
 * deprecated. Read with ASM from the class files, so nothing is loaded or run.
 *
 * @param classes by internal name ({@code org/example/Foo})
 */
record ApiSurface(Map<String, ApiClass> classes) {

    /**
     * @param members by {@code name + descriptor} (methods) or {@code name:descriptor} (fields), to
     *     whether the member is deprecated
     */
    record ApiClass(String name, boolean deprecated, Map<String, Boolean> members) {}

    private static final String DEPRECATED = "Ljava/lang/Deprecated;";

    static ApiSurface of(Path jar) throws IOException {
        Map<String, ApiClass> classes = new HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")
                        || name.endsWith("module-info.class")
                        || name.startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    read(new ClassReader(in)).ifPresent(c -> classes.put(c.name(), c));
                }
            }
        }
        return new ApiSurface(classes);
    }

    private static java.util.Optional<ApiClass> read(ClassReader reader) {
        Map<String, Boolean> members = new HashMap<>();
        boolean[] visible = {false};
        boolean[] deprecated = {false};
        String[] className = {null};
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
                        visible[0] =
                                (access & Opcodes.ACC_PUBLIC) != 0
                                        && (access & Opcodes.ACC_SYNTHETIC) == 0;
                        deprecated[0] = (access & Opcodes.ACC_DEPRECATED) != 0;
                    }

                    @Override
                    public void visitInnerClass(
                            String name, String outerName, String innerName, int access) {
                        // A nested class is API only if it's public or protected itself
                        if (name.equals(className[0])
                                && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
                            visible[0] = false;
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean runtime) {
                        if (descriptor.equals(DEPRECATED)) {
                            deprecated[0] = true;
                        }
                        return null;
                    }

                    @Override
                    public FieldVisitor visitField(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            Object value) {
                        if (isApi(access)) {
                            members.put(
                                    name + ":" + descriptor,
                                    (access & Opcodes.ACC_DEPRECATED) != 0);
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        if (isApi(access) && !name.equals("<clinit>")) {
                            members.put(name + descriptor, (access & Opcodes.ACC_DEPRECATED) != 0);
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!visible[0] || className[0] == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ApiClass(className[0], deprecated[0], members));
    }

    private static boolean isApi(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                && (access & Opcodes.ACC_SYNTHETIC) == 0;
    }
}
