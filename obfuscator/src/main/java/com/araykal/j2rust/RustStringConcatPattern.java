package com.araykal.j2rust;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Map;

final class RustStringConcatPattern {
    final int local;
    final String suffix;

    private RustStringConcatPattern(int local, String suffix) {
        this.local = local;
        this.suffix = suffix;
    }

    static RustStringConcatPattern match(List<AbstractInsnNode> nodes, int start,
                                         Map<LabelNode, Integer> labels) {
        if (start + 7 >= nodes.size()) return null;
        if (!(nodes.get(start) instanceof TypeInsnNode) ||
                nodes.get(start).getOpcode() != Opcodes.NEW ||
                !((TypeInsnNode) nodes.get(start)).desc.equals("java/lang/StringBuilder") ||
                nodes.get(start + 1).getOpcode() != Opcodes.DUP ||
                !call(nodes.get(start + 2), Opcodes.INVOKESPECIAL, "<init>", "()V") ||
                !(nodes.get(start + 3) instanceof VarInsnNode) ||
                nodes.get(start + 3).getOpcode() != Opcodes.ALOAD ||
                !call(nodes.get(start + 4), Opcodes.INVOKEVIRTUAL, "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;") ||
                !(nodes.get(start + 5) instanceof LdcInsnNode) ||
                !(((LdcInsnNode) nodes.get(start + 5)).cst instanceof String) ||
                !call(nodes.get(start + 6), Opcodes.INVOKEVIRTUAL, "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;") ||
                !call(nodes.get(start + 7), Opcodes.INVOKEVIRTUAL, "toString", "()Ljava/lang/String;"))
            return null;
        for (int index = start + 1; index <= start + 7; index++)
            if (labels.containsValue(index)) return null;
        return new RustStringConcatPattern(((VarInsnNode) nodes.get(start + 3)).var,
                (String) ((LdcInsnNode) nodes.get(start + 5)).cst);
    }

    private static boolean call(AbstractInsnNode node, int opcode, String name, String descriptor) {
        if (!(node instanceof MethodInsnNode) || node.getOpcode() != opcode) return false;
        MethodInsnNode method = (MethodInsnNode) node;
        return method.owner.equals("java/lang/StringBuilder") && method.name.equals(name) &&
                method.desc.equals(descriptor);
    }

    void emit(StringBuilder code, String exceptionPath) {
        code.append("let result = unsafe { concat_literal(_env, locals[").append(local).append("].o(), &[");
        for (int index = 0; index < suffix.length(); index++)
            code.append((int) suffix.charAt(index)).append(',');
        code.append("]) }; ");
        RustMethodEmitter.appendExceptionCheck(code, exceptionPath);
        code.append("stack.push(Value::O(refs.track(result))); ");
    }
}
