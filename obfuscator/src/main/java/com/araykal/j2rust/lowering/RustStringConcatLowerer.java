package com.araykal.j2rust.lowering;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class RustStringConcatLowerer {
    private static final String BUILDER = "java/lang/StringBuilder";

    private RustStringConcatLowerer() {
    }

    public static void lower(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof InvokeDynamicInsnNode)
                    lowerCall(method, (InvokeDynamicInsnNode) instruction);
                instruction = next;
            }
        }
    }

    private static void lowerCall(MethodNode method, InvokeDynamicInsnNode call) {
        if (!call.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) return;
        boolean recipeMode = call.bsm.getName().equals("makeConcatWithConstants");
        if (!recipeMode && !call.bsm.getName().equals("makeConcat")) return;
        if (recipeMode && (call.bsmArgs.length == 0 || !(call.bsmArgs[0] instanceof String))) return;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        if (!Type.getReturnType(call.desc).equals(Type.getType(String.class))) return;
        String recipe = recipeMode ? (String) call.bsmArgs[0] : null;
        if (recipeMode) {
            int dynamicCount = 0;
            int constantCount = 0;
            for (int index = 0; index < recipe.length(); index++) {
                if (recipe.charAt(index) == '\u0001') dynamicCount++;
                else if (recipe.charAt(index) == '\u0002') constantCount++;
            }
            if (dynamicCount != arguments.length || constantCount != call.bsmArgs.length - 1) return;
            for (int index = 1; index < call.bsmArgs.length; index++) {
                Object constant = call.bsmArgs[index];
                if (!(constant instanceof String || constant instanceof Integer || constant instanceof Long ||
                        constant instanceof Float || constant instanceof Double)) return;
            }
        }
        int[] slots = new int[arguments.length];
        int nextLocal = method.maxLocals;
        for (int index = 0; index < arguments.length; index++) {
            slots[index] = nextLocal;
            nextLocal += arguments[index].getSize();
        }
        InsnList replacement = new InsnList();
        for (int index = arguments.length - 1; index >= 0; index--)
            replacement.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ISTORE), slots[index]));
        replacement.add(new TypeInsnNode(Opcodes.NEW, BUILDER));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BUILDER, "<init>", "()V", false));
        if (recipeMode) {
            int dynamic = 0;
            int constant = 1;
            StringBuilder literal = new StringBuilder();
            for (int index = 0; index < recipe.length(); index++) {
                char part = recipe.charAt(index);
                if (part != '\u0001' && part != '\u0002') {
                    literal.append(part);
                    continue;
                }
                appendLiteral(replacement, literal);
                if (part == '\u0001') {
                    replacement.add(new VarInsnNode(arguments[dynamic].getOpcode(Opcodes.ILOAD), slots[dynamic]));
                    append(replacement, arguments[dynamic++]);
                } else {
                    Object value = call.bsmArgs[constant++];
                    replacement.add(new LdcInsnNode(value));
                    append(replacement, Type.getType(value instanceof String ? String.class :
                            value instanceof Long ? long.class : value instanceof Float ? float.class :
                            value instanceof Double ? double.class : int.class));
                }
            }
            appendLiteral(replacement, literal);
        } else {
            for (int index = 0; index < arguments.length; index++) {
                replacement.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), slots[index]));
                append(replacement, arguments[index]);
            }
        }
        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BUILDER, "toString", "()Ljava/lang/String;", false));
        method.instructions.insertBefore(call, replacement);
        method.instructions.remove(call);
        method.maxLocals = nextLocal;
    }

    private static void appendLiteral(InsnList instructions, StringBuilder literal) {
        if (literal.length() == 0) return;
        instructions.add(new LdcInsnNode(literal.toString()));
        append(instructions, Type.getType(String.class));
        literal.setLength(0);
    }

    private static void append(InsnList instructions, Type type) {
        String parameter;
        switch (type.getSort()) {
            case Type.BOOLEAN: parameter = "Z"; break;
            case Type.CHAR: parameter = "C"; break;
            case Type.LONG: parameter = "J"; break;
            case Type.FLOAT: parameter = "F"; break;
            case Type.DOUBLE: parameter = "D"; break;
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT: parameter = "I"; break;
            default: parameter = type.equals(Type.getType(String.class)) ? "Ljava/lang/String;" : "Ljava/lang/Object;";
        }
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BUILDER, "append",
                "(" + parameter + ")Ljava/lang/StringBuilder;", false));
    }
}
