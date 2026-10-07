package com.araykal.j2rust.intrinsics;

import org.objectweb.asm.tree.MethodInsnNode;

@FunctionalInterface
public interface IntrinsicEmitter {
    void emit(StringBuilder code, MethodInsnNode node, String exceptionPath);
}
