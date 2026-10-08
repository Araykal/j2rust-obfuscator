package com.araykal.j2rust;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

final class RustExecutionMode {
    private RustExecutionMode() { }

    static String forMethod(MethodNode method) {
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof MethodInsnNode || node instanceof FieldInsnNode ||
                    node instanceof TypeInsnNode || node.getOpcode() == Opcodes.LDC ||
                    node.getOpcode() == Opcodes.NEWARRAY || node.getOpcode() == Opcodes.MULTIANEWARRAY ||
                    node.getOpcode() == Opcodes.ANEWARRAY || node.getOpcode() == Opcodes.ARRAYLENGTH ||
                    node.getOpcode() == Opcodes.MONITORENTER || node.getOpcode() == Opcodes.MONITOREXIT)
                return "rust-jni";
        }
        return "rust-native";
    }
}
