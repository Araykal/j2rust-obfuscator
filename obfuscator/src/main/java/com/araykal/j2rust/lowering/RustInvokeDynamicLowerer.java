package com.araykal.j2rust.lowering;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

public final class RustInvokeDynamicLowerer {
    private RustInvokeDynamicLowerer() {
    }

    public static List<MethodNode> lower(ClassNode owner) {
        List<MethodNode> bridges = new ArrayList<>();
        int index = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                    String name;
                    do {
                        name = "j2rust$indy$" + index++;
                    } while (hasMethod(owner, name, dynamic.desc));
                    int access = (owner.access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
                    MethodNode bridge = new MethodNode(Opcodes.ASM9,
                            access | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            name, dynamic.desc, null, null);
                    int slot = 0;
                    for (Type argument : Type.getArgumentTypes(dynamic.desc)) {
                        bridge.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
                        slot += argument.getSize();
                    }
                    bridge.instructions.add(new InvokeDynamicInsnNode(dynamic.name, dynamic.desc,
                            dynamic.bsm, dynamic.bsmArgs));
                    bridge.instructions.add(new org.objectweb.asm.tree.InsnNode(
                            Type.getReturnType(dynamic.desc).getOpcode(Opcodes.IRETURN)));
                    bridge.maxLocals = slot;
                    method.instructions.set(dynamic, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            owner.name, bridge.name, bridge.desc, false));
                    bridges.add(bridge);
                } else if (instruction instanceof LdcInsnNode && needsBridge(((LdcInsnNode) instruction).cst)) {
                    Object constant = ((LdcInsnNode) instruction).cst;
                    String descriptor = "()" + constantDescriptor(constant);
                    Type type = Type.getReturnType(descriptor);
                    String name;
                    do {
                        name = constant instanceof ConstantDynamic ? "j2rust$condy$" + index++ :
                                "j2rust$ldc$" + index++;
                    } while (hasMethod(owner, name, descriptor));
                    int access = (owner.access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
                    MethodNode bridge = new MethodNode(Opcodes.ASM9,
                            access | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            name, descriptor, null, null);
                    bridge.instructions.add(new LdcInsnNode(constant));
                    bridge.instructions.add(new InsnNode(type.getOpcode(Opcodes.IRETURN)));
                    method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            owner.name, name, descriptor, false));
                    bridges.add(bridge);
                }
                instruction = next;
            }
        }
        owner.methods.addAll(bridges);
        return bridges;
    }

    private static boolean needsBridge(Object constant) {
        return constant instanceof ConstantDynamic || constant instanceof Handle ||
                constant instanceof Type && ((Type) constant).getSort() != Type.OBJECT &&
                ((Type) constant).getSort() != Type.ARRAY;
    }

    private static String constantDescriptor(Object constant) {
        if (constant instanceof ConstantDynamic) return ((ConstantDynamic) constant).getDescriptor();
        if (constant instanceof Handle) return "Ljava/lang/invoke/MethodHandle;";
        return ((Type) constant).getSort() == Type.METHOD ?
                "Ljava/lang/invoke/MethodType;" : "Ljava/lang/Class;";
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods)
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        return false;
    }
}
