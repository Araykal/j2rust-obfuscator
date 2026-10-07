package com.araykal.j2rust.lowering;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class RustLegacySubroutineLowerer {
    private RustLegacySubroutineLowerer() {
    }

    public static void lower(ClassNode owner) {
        for (int index = 0; index < owner.methods.size(); index++) {
            MethodNode method = owner.methods.get(index);
            boolean hasSubroutine = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() == Opcodes.JSR || instruction.getOpcode() == Opcodes.RET) {
                    hasSubroutine = true;
                    break;
                }
            }
            if (!hasSubroutine) continue;
            JSRInlinerAdapter inliner = new JSRInlinerAdapter(null, method.access, method.name,
                    method.desc, method.signature, method.exceptions == null ? null :
                    method.exceptions.toArray(new String[0]));
            method.accept(inliner);
            owner.methods.set(index, inliner);
        }
    }
}
