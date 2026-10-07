package com.araykal.j2rust;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public final class RustOpcodeSupport {
    private RustOpcodeSupport() {
    }

    public static boolean supported(MethodNode method) {
        return unsupportedReason(method) == null;
    }

    static String unsupportedReason(MethodNode method) {
        if (!RustMethodSelector.shouldProcess(method)) return "constructor or non-code method";
        if (method.name.equals("<clinit>")) return "class initializer";
        if (!primitive(Type.getReturnType(method.desc))) return "return type";
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (!primitive(argument) || argument.getSort() == Type.VOID) return "argument type";
        }
        for (AbstractInsnNode node : method.instructions) {
            int opcode = node.getOpcode();
            if (opcode < 0) continue;
            if (opcode == Opcodes.LDC) {
                Object value = ((LdcInsnNode) node).cst;
                if (value instanceof Integer || value instanceof Long ||
                        value instanceof Float || value instanceof Double ||
                        value instanceof String || value instanceof Type &&
                        (((Type) value).getSort() == Type.OBJECT ||
                                ((Type) value).getSort() == Type.ARRAY)) continue;
                return "LDC " + value.getClass().getSimpleName();
            }
            if (opcode == Opcodes.NOP ||
                    opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.DCONST_1 ||
                    opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH ||
                    opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD ||
                    opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE ||
                    opcode == Opcodes.IINC ||
                    opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD ||
                    opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE ||
                    opcode >= Opcodes.IADD && opcode <= Opcodes.DNEG ||
                    opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR ||
                    opcode >= Opcodes.I2L && opcode <= Opcodes.I2S ||
                    opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG ||
                    opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ICMPLE ||
                    opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE ||
                    opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL ||
                    opcode == Opcodes.GOTO ||
                    opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN ||
                    opcode == Opcodes.ARRAYLENGTH ||
                    opcode == Opcodes.ATHROW ||
                    opcode >= Opcodes.POP && opcode <= Opcodes.SWAP ||
                    opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) continue;
            if (opcode == Opcodes.NEWARRAY && node instanceof IntInsnNode) continue;
            if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) continue;
            if (opcode == Opcodes.MULTIANEWARRAY) continue;
            if (opcode == Opcodes.NEW || opcode == Opcodes.ANEWARRAY ||
                    opcode == Opcodes.CHECKCAST || opcode == Opcodes.INSTANCEOF)
                continue;
            if (node instanceof FieldInsnNode) continue;
            if (node instanceof MethodInsnNode && opcode >= Opcodes.INVOKEVIRTUAL &&
                    opcode <= Opcodes.INVOKEINTERFACE) continue;
            return "opcode " + opcode + " (" + node.getClass().getSimpleName() + ")";
        }
        return null;
    }

    private static boolean primitive(Type type) {
        return type.getSort() >= Type.VOID && type.getSort() <= Type.OBJECT;
    }
}
