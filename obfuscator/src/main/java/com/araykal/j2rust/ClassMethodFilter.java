package com.araykal.j2rust;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.List;

public class ClassMethodFilter {

    private static final String NATIVE_ANNOTATION_DESC = Type.getDescriptor(Native.class);
    private static final String NOT_NATIVE_ANNOTATION_DESC = Type.getDescriptor(NotNative.class);

    private final boolean useAnnotations;
    private final List<String> include;
    private final List<String> exclude;

    public ClassMethodFilter(boolean useAnnotations) {
        this(useAnnotations, Collections.emptyList(), Collections.emptyList());
    }

    public ClassMethodFilter(boolean useAnnotations, List<String> include, List<String> exclude) {
        this.useAnnotations = useAnnotations;
        this.include = include;
        this.exclude = exclude;
    }

    public static void cleanAnnotations(ClassNode classNode) {
        if (classNode.invisibleAnnotations != null) {
            classNode.invisibleAnnotations.removeIf(annotationNode -> annotationNode.desc.equals(NATIVE_ANNOTATION_DESC));
        }
        classNode.methods.stream()
                .filter(methodNode -> methodNode.invisibleAnnotations != null)
                .forEach(methodNode -> methodNode.invisibleAnnotations.removeIf(annotationNode ->
                        annotationNode.desc.equals(NATIVE_ANNOTATION_DESC) || annotationNode.desc.equals(NOT_NATIVE_ANNOTATION_DESC)));
    }

    public boolean shouldProcess(ClassNode classNode) {
        if (!useAnnotations && include.isEmpty() && exclude.isEmpty()) return true;
        if (!useAnnotations && !include.isEmpty())
            return classNode.methods.stream().anyMatch(method -> matches(classNode, method, include));
        if (!useAnnotations && !exclude.isEmpty())
            return classNode.methods.stream().anyMatch(method -> !matches(classNode, method, exclude));
        if (classNode.invisibleAnnotations != null &&
                classNode.invisibleAnnotations.stream().anyMatch(annotationNode ->
                        annotationNode.desc.equals(NATIVE_ANNOTATION_DESC))) {
            return true;
        }
        return classNode.methods.stream().anyMatch(methodNode -> this.shouldProcess(classNode, methodNode));
    }

    public boolean shouldProcess(ClassNode classNode, MethodNode methodNode) {
        if (!useAnnotations && include.isEmpty() && exclude.isEmpty()) return true;
        if (!useAnnotations && !include.isEmpty()) return matches(classNode, methodNode, include);
        if (!useAnnotations && !exclude.isEmpty()) return !matches(classNode, methodNode, exclude);
        boolean classIsMarked = classNode.invisibleAnnotations != null &&
                classNode.invisibleAnnotations.stream().anyMatch(annotationNode ->
                        annotationNode.desc.equals(NATIVE_ANNOTATION_DESC));
        if (methodNode.invisibleAnnotations != null &&
                methodNode.invisibleAnnotations.stream().anyMatch(annotationNode ->
                        annotationNode.desc.equals(NATIVE_ANNOTATION_DESC))) {
            return true;
        }
        return classIsMarked && (methodNode.invisibleAnnotations == null || methodNode.invisibleAnnotations
                .stream().noneMatch(annotationNode -> annotationNode.desc.equals(
                        NOT_NATIVE_ANNOTATION_DESC)));
    }

    private static boolean matches(ClassNode clazz, MethodNode method, List<String> patterns) {
        for (String raw : patterns) {
            if (matchesPattern(clazz.name, method.name, method.desc, raw)) return true;
        }
        return false;
    }

    private static boolean matchesPattern(String className, String methodName, String descriptor, String raw) {
        String pattern = raw == null ? "" : raw.trim();
        if (pattern.isEmpty()) return false;
        if (pattern.equals("*")) return true;

        int separator = pattern.indexOf('#');
        if (separator >= 0) {
            String classPattern = pattern.substring(0, separator).replace('.', '/');
            String methodPattern = pattern.substring(separator + 1);
            if (!matchesClass(className, classPattern)) return false;
            int descriptorStart = methodPattern.indexOf('(');
            String methodNamePattern = descriptorStart < 0 ? methodPattern : methodPattern.substring(0, descriptorStart);
            String descriptorPattern = descriptorStart < 0 ? "*" : methodPattern.substring(descriptorStart);
            return glob(methodNamePattern, methodName) && glob(descriptorPattern, descriptor);
        }

        String classPattern = pattern.replace('.', '/');
        return matchesClass(className, classPattern);
    }

    private static boolean matchesClass(String className, String classPattern) {
        if (classPattern.equals("*")) return true;
        if (classPattern.endsWith("/**")) {
            String packagePrefix = classPattern.substring(0, classPattern.length() - 3);
            return className.startsWith(packagePrefix + "/");
        }
        if (classPattern.endsWith("/*")) {
            String prefix = classPattern.substring(0, classPattern.length() - 2);
            if (className.equals(prefix)) return true;
            if (!className.startsWith(prefix + "/")) return false;
            return className.indexOf('/', prefix.length() + 1) < 0;
        }
        return glob(classPattern, className);
    }

    private static boolean glob(String pattern, String value) {
        if (pattern.equals("*")) return true;
        String[] parts = pattern.split("\\*", -1);
        int offset = 0;
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            int found = value.indexOf(part, offset);
            if (found < 0 || index == 0 && found != 0 || index == parts.length - 1 && found + part.length() != value.length()) return false;
            offset = found + part.length();
        }
        return true;
    }
}
