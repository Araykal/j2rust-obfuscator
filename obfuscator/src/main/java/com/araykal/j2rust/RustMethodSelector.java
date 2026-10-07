package com.araykal.j2rust;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

final class RustMethodSelector {
    private RustMethodSelector() {
    }

    static boolean shouldProcess(MethodNode method) {
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 &&
                !method.name.equals("<init>");
    }
}
