package com.araykal.j2rust.lowering;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RustLambdaLowerer {
    private RustLambdaLowerer() {
    }

    public static List<ClassNode> lower(ClassNode owner, Set<String> occupied) {
        List<ClassNode> generated = new ArrayList<>();
        int index = 0;
        for (MethodNode method : owner.methods) {
            AbstractInsnNode node = method.instructions.getFirst();
            while (node != null) {
                AbstractInsnNode next = node.getNext();
                if (node instanceof InvokeDynamicInsnNode) {
                    ClassNode helper = lowerCall(owner, method, (InvokeDynamicInsnNode) node, index++, occupied);
                    if (helper != null) generated.add(helper);
                }
                node = next;
            }
        }
        return generated;
    }

    private static ClassNode lowerCall(ClassNode owner, MethodNode method,
                                       InvokeDynamicInsnNode invocation, int index, Set<String> occupied) {
        if (!invocation.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") ||
                !invocation.bsm.getName().equals("metafactory") ||
                invocation.bsmArgs.length != 3 ||
                !(invocation.bsmArgs[0] instanceof Type) ||
                !(invocation.bsmArgs[1] instanceof Handle) ||
                !(invocation.bsmArgs[2] instanceof Type)) return null;
        Handle implementation = (Handle) invocation.bsmArgs[1];
        if (implementation.getTag() != Opcodes.H_INVOKESTATIC &&
                implementation.getTag() != Opcodes.H_INVOKEVIRTUAL &&
                implementation.getTag() != Opcodes.H_INVOKEINTERFACE) return null;
        Type[] captures = Type.getArgumentTypes(invocation.desc);
        Type[] samArguments = Type.getArgumentTypes(((Type) invocation.bsmArgs[0]).getDescriptor());
        Type[] instantiatedArguments = Type.getArgumentTypes(((Type) invocation.bsmArgs[2]).getDescriptor());
        Type interfaceType = Type.getReturnType(invocation.desc);
        Type[] implementationArguments = Type.getArgumentTypes(implementation.getDesc());
        Type samReturn = Type.getReturnType(((Type) invocation.bsmArgs[0]).getDescriptor());
        Type instantiatedReturn = Type.getReturnType(((Type) invocation.bsmArgs[2]).getDescriptor());
        Type implementationReturn = Type.getReturnType(implementation.getDesc());
        if (interfaceType.getSort() != Type.OBJECT ||
                instantiatedArguments.length != samArguments.length ||
                !adaptable(implementationReturn, instantiatedReturn) ||
                !adaptable(instantiatedReturn, samReturn)) return null;
        boolean instanceCall = implementation.getTag() != Opcodes.H_INVOKESTATIC;
        int boundReceiver = instanceCall && captures.length > 0 ? 1 : 0;
        int samReceiver = instanceCall && captures.length == 0 ? 1 : 0;
        int capturedArguments = captures.length - boundReceiver;
        if (samArguments.length < samReceiver ||
                implementationArguments.length != capturedArguments + samArguments.length - samReceiver)
            return null;
        if (boundReceiver == 1 && !adaptable(captures[0], Type.getObjectType(implementation.getOwner())))
            return null;
        if (samReceiver == 1 && (!adaptable(samArguments[0], instantiatedArguments[0]) ||
                !adaptable(instantiatedArguments[0], Type.getObjectType(implementation.getOwner()))))
            return null;
        for (int argument = 0; argument < implementationArguments.length; argument++) {
            if (argument < capturedArguments) {
                if (!adaptable(captures[argument + boundReceiver], implementationArguments[argument]))
                    return null;
            } else {
                int samIndex = argument - capturedArguments + samReceiver;
                if (!adaptable(samArguments[samIndex], instantiatedArguments[samIndex]) ||
                        !adaptable(instantiatedArguments[samIndex], implementationArguments[argument]))
                    return null;
            }
        }
        String name = owner.name + "$J2RustLambda$" + index;
        int suffix = 1;
        while (occupied.contains(name + ".class"))
            name = owner.name + "$J2RustLambda$" + index + "$" + suffix++;
        occupied.add(name + ".class");
        ClassNode helper = new ClassNode(Opcodes.ASM9);
        helper.version = owner.version;
        helper.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        helper.name = name;
        helper.superName = "java/lang/Object";
        helper.interfaces.add(interfaceType.getInternalName());
        StringBuilder constructorDescriptor = new StringBuilder("(");
        for (int capture = 0; capture < captures.length; capture++) {
            constructorDescriptor.append(captures[capture].getDescriptor());
            helper.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    "capture$" + capture, captures[capture].getDescriptor(), null, null));
        }
        constructorDescriptor.append(")V");
        MethodNode constructor = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                "<init>", constructorDescriptor.toString(), null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        int local = 1;
        for (int capture = 0; capture < captures.length; capture++) {
            constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            constructor.instructions.add(new VarInsnNode(captures[capture].getOpcode(Opcodes.ILOAD), local));
            constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                    name, "capture$" + capture, captures[capture].getDescriptor()));
            local += captures[capture].getSize();
        }
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxLocals = local;
        helper.methods.add(constructor);

        MethodNode sam = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                invocation.name, ((Type) invocation.bsmArgs[0]).getDescriptor(), null, null);
        for (int capture = 0; capture < captures.length; capture++) {
            sam.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            sam.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                    name, "capture$" + capture, captures[capture].getDescriptor()));
            Type target = boundReceiver == 1 && capture == 0 ?
                    Type.getObjectType(implementation.getOwner()) : implementationArguments[capture - boundReceiver];
            cast(sam.instructions, captures[capture], target);
        }
        local = 1;
        for (int samIndex = 0; samIndex < samArguments.length; samIndex++) {
            Type argument = samArguments[samIndex];
            sam.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            cast(sam.instructions, argument, instantiatedArguments[samIndex]);
            Type target = samReceiver == 1 && samIndex == 0 ?
                    Type.getObjectType(implementation.getOwner()) :
                    implementationArguments[capturedArguments + samIndex - samReceiver];
            cast(sam.instructions, instantiatedArguments[samIndex], target);
            local += argument.getSize();
        }
        sam.instructions.add(new MethodInsnNode(
                implementation.getTag() == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC :
                        implementation.getTag() == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                implementation.getOwner(), implementation.getName(), implementation.getDesc(),
                implementation.getTag() == Opcodes.H_INVOKEINTERFACE));
        cast(sam.instructions, implementationReturn, instantiatedReturn);
        cast(sam.instructions, instantiatedReturn, samReturn);
        sam.instructions.add(new InsnNode(samReturn.getOpcode(Opcodes.IRETURN)));
        sam.maxLocals = local;
        helper.methods.add(sam);

        int[] slots = new int[captures.length];
        int nextLocal = method.maxLocals;
        for (int capture = 0; capture < captures.length; capture++) {
            slots[capture] = nextLocal;
            nextLocal += captures[capture].getSize();
        }
        InsnList replacement = new InsnList();
        for (int capture = captures.length - 1; capture >= 0; capture--)
            replacement.add(new VarInsnNode(captures[capture].getOpcode(Opcodes.ISTORE), slots[capture]));
        replacement.add(new TypeInsnNode(Opcodes.NEW, name));
        replacement.add(new InsnNode(Opcodes.DUP));
        for (int capture = 0; capture < captures.length; capture++)
            replacement.add(new VarInsnNode(captures[capture].getOpcode(Opcodes.ILOAD), slots[capture]));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                name, "<init>", constructorDescriptor.toString(), false));
        method.instructions.insertBefore(invocation, replacement);
        method.instructions.remove(invocation);
        method.maxLocals = nextLocal;
        return helper;
    }

    private static boolean adaptable(Type source, Type target) {
        if (source.equals(target) || reference(source) && reference(target)) return true;
        if (source.getSort() == Type.VOID || target.getSort() == Type.VOID) return false;
        if (reference(source)) return wrapper(target) != null;
        if (reference(target)) return wrapper(source) != null;
        return source.getSort() != Type.BOOLEAN && target.getSort() != Type.BOOLEAN &&
                wrapper(source) != null && wrapper(target) != null;
    }

    private static boolean reference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static void cast(InsnList instructions, Type source, Type target) {
        if (source.equals(target)) return;
        if (reference(source) && reference(target)) {
            instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, target.getInternalName()));
        } else if (reference(source)) {
            String owner = wrapper(target);
            instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
            instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner,
                    primitiveName(target) + "Value", "()" + target.getDescriptor(), false));
        } else if (reference(target)) {
            String owner = wrapper(source);
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf",
                    "(" + source.getDescriptor() + ")L" + owner + ";", false));
            if (!target.equals(Type.getObjectType(owner)) &&
                    !target.equals(Type.getType(Object.class)))
                instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, target.getInternalName()));
        } else {
            convertPrimitive(instructions, source, target);
        }
    }

    private static String wrapper(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN: return "java/lang/Boolean";
            case Type.BYTE: return "java/lang/Byte";
            case Type.CHAR: return "java/lang/Character";
            case Type.SHORT: return "java/lang/Short";
            case Type.INT: return "java/lang/Integer";
            case Type.LONG: return "java/lang/Long";
            case Type.FLOAT: return "java/lang/Float";
            case Type.DOUBLE: return "java/lang/Double";
            default: return null;
        }
    }

    private static String primitiveName(Type type) {
        return type.getSort() == Type.CHAR ? "char" : type.getClassName();
    }

    private static void convertPrimitive(InsnList instructions, Type source, Type target) {
        int from = source.getSort();
        int to = target.getSort();
        if (from == Type.CHAR || from >= Type.BYTE && from <= Type.INT) from = Type.INT;
        if (to == Type.CHAR || to >= Type.BYTE && to <= Type.INT) to = Type.INT;
        if (from != to) {
            int opcode;
            if (from == Type.INT) opcode = to == Type.LONG ? Opcodes.I2L :
                    to == Type.FLOAT ? Opcodes.I2F : Opcodes.I2D;
            else if (from == Type.LONG) opcode = to == Type.INT ? Opcodes.L2I :
                    to == Type.FLOAT ? Opcodes.L2F : Opcodes.L2D;
            else if (from == Type.FLOAT) opcode = to == Type.INT ? Opcodes.F2I :
                    to == Type.LONG ? Opcodes.F2L : Opcodes.F2D;
            else opcode = to == Type.INT ? Opcodes.D2I :
                    to == Type.LONG ? Opcodes.D2L : Opcodes.D2F;
            instructions.add(new InsnNode(opcode));
        }
        if (target.getSort() == Type.BYTE) instructions.add(new InsnNode(Opcodes.I2B));
        else if (target.getSort() == Type.SHORT) instructions.add(new InsnNode(Opcodes.I2S));
        else if (target.getSort() == Type.CHAR) instructions.add(new InsnNode(Opcodes.I2C));
    }
}
