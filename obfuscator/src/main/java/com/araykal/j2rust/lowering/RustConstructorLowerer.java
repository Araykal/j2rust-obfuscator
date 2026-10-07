package com.araykal.j2rust.lowering;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import com.araykal.j2rust.RustOpcodeSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RustConstructorLowerer {
    private RustConstructorLowerer() {
    }

    public static MethodNode lower(ClassNode clazz, MethodNode constructor, String helperName, int index) {
        MethodInsnNode baseCall = null;
        for (AbstractInsnNode node : constructor.instructions) {
            if (node instanceof MethodInsnNode && node.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode invocation = (MethodInsnNode) node;
                if (invocation.name.equals("<init>") &&
                        (invocation.owner.equals(clazz.name) || invocation.owner.equals(clazz.superName))) {
                    baseCall = invocation;
                    break;
                }
            }
        }
        if (baseCall == null) return null;
        int split = constructor.instructions.indexOf(baseCall);
        int argumentSlots = 1;
        for (Type argument : Type.getArgumentTypes(constructor.desc)) argumentSlots += argument.getSize();
        Map<Integer, Type> captured = new LinkedHashMap<>();
        for (AbstractInsnNode node = constructor.instructions.getFirst(); node != baseCall; node = node.getNext()) {
            int opcode = node.getOpcode();
            if (opcode == Opcodes.GOTO || opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH ||
                    opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE ||
                    opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) return null;
            if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE || opcode == Opcodes.IINC) {
                int slot = opcode == Opcodes.IINC ? ((org.objectweb.asm.tree.IincInsnNode) node).var :
                        ((VarInsnNode) node).var;
                Type type = opcode == Opcodes.LSTORE ? Type.LONG_TYPE :
                        opcode == Opcodes.FSTORE ? Type.FLOAT_TYPE :
                        opcode == Opcodes.DSTORE ? Type.DOUBLE_TYPE :
                        opcode == Opcodes.ASTORE ? Type.getType(Object.class) : Type.INT_TYPE;
                if (slot < argumentSlots) {
                    if (slot == 0 && opcode != Opcodes.ASTORE) return null;
                    Type expected = slot == 0 ? Type.getType(Object.class) : null;
                    int argumentSlot = 1;
                    for (Type argument : Type.getArgumentTypes(constructor.desc)) {
                        if (argumentSlot == slot) expected = argument;
                        argumentSlot += argument.getSize();
                    }
                    if (expected == null || !sameLocalKind(expected, type)) return null;
                } else {
                    if (opcode == Opcodes.ASTORE ||
                            captured.containsKey(slot) && !captured.get(slot).equals(type)) return null;
                    captured.put(slot, type);
                }
            }
        }
        if (constructor.tryCatchBlocks != null) {
            for (TryCatchBlockNode handler : constructor.tryCatchBlocks) {
                if (constructor.instructions.indexOf(handler.start) <= split ||
                        constructor.instructions.indexOf(handler.end) <= split ||
                        constructor.instructions.indexOf(handler.handler) <= split) return null;
            }
        }

        StringBuilder bridgeDescriptor = new StringBuilder("(Ljava/lang/Object;")
                .append(constructor.desc, 1, constructor.desc.indexOf(')'));
        for (Type type : captured.values()) bridgeDescriptor.append(type.getDescriptor());
        bridgeDescriptor.append(")V");
        MethodNode bridge = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "j2rust$init$" + index,
                bridgeDescriptor.toString(), null, null);
        bridge.maxLocals = constructor.maxLocals;
        bridge.maxStack = constructor.maxStack;
        AbstractInsnNode node = baseCall.getNext();
        while (node != null) {
            AbstractInsnNode next = node.getNext();
            constructor.instructions.remove(node);
            bridge.instructions.add(node);
            node = next;
        }
        bridge.tryCatchBlocks = constructor.tryCatchBlocks;
        bridge.localVariables = null;
        if (!RustOpcodeSupport.supported(bridge)) {
            constructor.instructions.add(bridge.instructions);
            return null;
        }
        if (!captured.isEmpty()) {
            InsnList prologue = new InsnList();
            int parameterSlot = argumentSlots;
            int scratchSlot = Math.max(constructor.maxLocals, argumentSlots +
                    captured.values().stream().mapToInt(Type::getSize).sum());
            Map<Integer, Integer> scratch = new LinkedHashMap<>();
            for (Map.Entry<Integer, Type> capture : captured.entrySet()) {
                Type type = capture.getValue();
                prologue.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), parameterSlot));
                prologue.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), scratchSlot));
                scratch.put(capture.getKey(), scratchSlot);
                parameterSlot += type.getSize();
                scratchSlot += type.getSize();
            }
            for (Map.Entry<Integer, Type> capture : captured.entrySet()) {
                Type type = capture.getValue();
                prologue.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), scratch.get(capture.getKey())));
                prologue.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), capture.getKey()));
            }
            bridge.instructions.insert(prologue);
            bridge.maxLocals = scratchSlot;
        }
        constructor.tryCatchBlocks = new ArrayList<>();
        constructor.localVariables = null;
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(constructor.desc)) {
            constructor.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        for (Map.Entry<Integer, Type> capture : captured.entrySet())
            constructor.instructions.add(new VarInsnNode(capture.getValue().getOpcode(Opcodes.ILOAD),
                    capture.getKey()));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                helperName, bridge.name, bridge.desc, false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        return bridge;
    }

    private static boolean sameLocalKind(Type expected, Type actual) {
        if (actual.equals(Type.INT_TYPE))
            return expected.getSort() >= Type.BOOLEAN && expected.getSort() <= Type.INT;
        return expected.equals(actual) || actual.getSort() == Type.OBJECT &&
                (expected.getSort() == Type.OBJECT || expected.getSort() == Type.ARRAY);
    }
}
