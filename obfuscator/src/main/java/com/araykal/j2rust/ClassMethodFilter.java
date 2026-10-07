package com.araykal.j2rust;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class ClassMethodFilter {

    private static final String NATIVE_ANNOTATION_DESC = Type.getDescriptor(Native.class);
    private static final String NOT_NATIVE_ANNOTATION_DESC = Type.getDescriptor(NotNative.class);

    private final boolean useAnnotations;

    public ClassMethodFilter(boolean useAnnotations) {
        this.useAnnotations = useAnnotations;
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
        if (!useAnnotations) {
            return true;
        }
        if (classNode.invisibleAnnotations != null &&
                classNode.invisibleAnnotations.stream().anyMatch(annotationNode ->
                        annotationNode.desc.equals(NATIVE_ANNOTATION_DESC))) {
            return true;
        }
        return classNode.methods.stream().anyMatch(methodNode -> this.shouldProcess(classNode, methodNode));
    }

    public boolean shouldProcess(ClassNode classNode, MethodNode methodNode) {
        if (!useAnnotations) {
            return true;
        }
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
}