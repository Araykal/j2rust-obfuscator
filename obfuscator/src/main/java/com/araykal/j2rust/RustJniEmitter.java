package com.araykal.j2rust;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

final class RustJniEmitter {
    private RustJniEmitter() {
    }

    static void emitTypeCheck(StringBuilder code, TypeInsnNode node, String exceptionPath) {
        code.append("let value = stack.pop().unwrap().o();");
        if (node.getOpcode() == Opcodes.CHECKCAST) code.append(" if value.is_null() { stack.push(Value::O(value)); } else { ");
        else code.append(" if value.is_null() { stack.push(Value::I(0)); } else { ");
        code.append("let class = unsafe { find_class(_env, \"").append(node.desc).append("\") }; ");
        RustMethodEmitter.appendExceptionCheck(code, exceptionPath);
        code.append("let matches = unsafe { (**_env).IsInstanceOf.unwrap()(_env, value, class) != 0 }; ");
        code.append("unsafe { (**_env).DeleteLocalRef.unwrap()(_env, class); } ");
        if (node.getOpcode() == Opcodes.CHECKCAST) {
            code.append("if !matches { unsafe { throw_named(_env, \"java/lang/ClassCastException\", \"")
                    .append(node.desc).append("\"); } ").append(exceptionPath).append(" } ");
            code.append("stack.push(Value::O(value));");
        } else code.append("stack.push(Value::I(if matches { 1 } else { 0 }));");
        code.append(" }");
    }

    static void emitCall(StringBuilder code, MethodInsnNode node, String exceptionPath) {
        if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node.owner.equals("java/lang/String") &&
                node.name.equals("length") && node.desc.equals("()I")) {
            code.append("let text = stack.pop().unwrap().o(); ");
            code.append("if text.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"length\"); } ")
                    .append(exceptionPath).append(" } ");
            code.append("stack.push(Value::I(unsafe { (**_env).GetStringLength.unwrap()(_env, text) }));");
            return;
        }
        if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node.owner.equals("java/lang/String") &&
                node.name.equals("isEmpty") && node.desc.equals("()Z")) {
            code.append("let text = stack.pop().unwrap().o(); ");
            code.append("if text.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"isEmpty\"); } ")
                    .append(exceptionPath).append(" } ");
            code.append("stack.push(Value::I(if unsafe { (**_env).GetStringLength.unwrap()(_env, text) } == 0 { 1 } else { 0 }));");
            return;
        }
        if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node.owner.equals("java/lang/String") &&
                node.name.equals("charAt") && node.desc.equals("(I)C")) {
            code.append("let index = stack.pop().unwrap().i(); let text = stack.pop().unwrap().o(); ");
            code.append("if text.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"charAt\"); } ")
                    .append(exceptionPath).append(" } ");
            code.append("let length = unsafe { (**_env).GetStringLength.unwrap()(_env, text) }; ");
            code.append("if index < 0 || index >= length { unsafe { throw_named(_env, \"java/lang/StringIndexOutOfBoundsException\", \"charAt\"); } ")
                    .append(exceptionPath).append(" } ");
            code.append("let mut unit: u16 = 0; unsafe { (**_env).GetStringRegion.unwrap()(_env, text as jni_sys::jstring, index, 1, &mut unit); } ");
            code.append("if unsafe { has_exception(_env) } { ").append(exceptionPath).append(" } ");
            code.append("stack.push(Value::I(unit as i32));");
            return;
        }
        Type[] arguments = Type.getArgumentTypes(node.desc);
        Type returnType = Type.getReturnType(node.desc);
        code.append("let mut args = vec![jni_sys::jvalue { i: 0 }; ").append(arguments.length).append("]; ");
        for (int index = arguments.length - 1; index >= 0; index--) {
            String field = arguments[index].getSort() == Type.BOOLEAN ? "z" :
                    arguments[index].getSort() == Type.BYTE ? "b" :
                    arguments[index].getSort() == Type.CHAR ? "c" :
                    arguments[index].getSort() == Type.SHORT ? "s" :
                    RustMethodEmitter.valueTag(arguments[index]).equals("O") ? "l" : RustMethodEmitter.valueTag(arguments[index]).toLowerCase();
            code.append("args[").append(index).append("] = jni_sys::jvalue { ").append(field)
                    .append(": stack.pop().unwrap().").append(RustMethodEmitter.valueTag(arguments[index]).toLowerCase()).append("()");
            if (arguments[index].getSort() >= Type.BOOLEAN && arguments[index].getSort() <= Type.SHORT)
                code.append(" as ").append(RustMethodEmitter.rustType(arguments[index]));
            code.append(" }; ");
        }
        if (node.getOpcode() == Opcodes.INVOKESTATIC)
            code.append("let receiver = std::ptr::null_mut(); ");
        else code.append("let receiver = stack.pop().unwrap().o(); ");
        if (node.owner.startsWith("java/") || node.owner.startsWith("javax/"))
            code.append("static CALL_CACHE: std::sync::OnceLock<(usize, usize)> = std::sync::OnceLock::new(); ");
        code.append("let value = unsafe { call_method(_env, ").append(node.getOpcode()).append(", \"")
                .append(node.owner).append("\", \"").append(node.name).append("\", \"")
                .append(node.desc).append("\", receiver, &args, '")
                .append(returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY ?
                        "L" : returnType.getDescriptor()).append("', ")
                .append(node.owner.startsWith("java/") || node.owner.startsWith("javax/") ?
                        "Some(&CALL_CACHE)" : "None").append(") }; ");
        code.append("if value.is_none() { ").append(exceptionPath).append(" } ");
        if (returnType.getSort() != Type.VOID) code.append("stack.push(refs.track_value(value.unwrap()));");
    }

    static void emitDirectCall(StringBuilder code, MethodInsnNode node, String exceptionPath) {
        Type[] arguments = Type.getArgumentTypes(node.desc);
        Type result = Type.getReturnType(node.desc);
        for (int index = arguments.length - 1; index >= 0; index--) {
            code.append("let direct_arg_").append(index).append(" = stack.pop().unwrap().")
                    .append(RustMethodEmitter.valueTag(arguments[index]).toLowerCase()).append("()");
            if (arguments[index].getSort() >= Type.BOOLEAN && arguments[index].getSort() <= Type.SHORT)
                code.append(" as ").append(RustMethodEmitter.rustType(arguments[index]));
            code.append("; ");
        }
        if (result.getSort() != Type.VOID) code.append("let direct_result = ");
        code.append(RustMethodEmitter.symbol(node.owner, new MethodNode(Opcodes.ASM9, 0,
                node.name, node.desc, null, null))).append("(_env, _receiver");
        for (int index = 0; index < arguments.length; index++)
            code.append(", direct_arg_").append(index);
        code.append("); ");
        RustMethodEmitter.appendExceptionCheck(code, exceptionPath);
        if (result.getSort() != Type.VOID) {
            code.append("stack.push(refs.track_value(Value::").append(RustMethodEmitter.valueTag(result))
                    .append("(direct_result");
            if (result.getSort() >= Type.BOOLEAN && result.getSort() <= Type.SHORT)
                code.append(" as i32");
            code.append(")));");
        }
    }

    static void emitField(StringBuilder code, FieldInsnNode node, String exceptionPath) {
        boolean staticField = node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.PUTSTATIC;
        boolean read = node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.GETFIELD;
        if (!read) code.append("let value = stack.pop().unwrap(); ");
        if (staticField) code.append("let receiver = std::ptr::null_mut(); ");
        else code.append("let receiver = stack.pop().unwrap().o(); ");
        Type type = Type.getType(node.desc);
        String kind = type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ?
                "L" : type.getDescriptor();
        if (read) {
            code.append("let value = unsafe { get_field(_env, \"").append(node.owner)
                    .append("\", \"").append(node.name).append("\", \"")
                    .append(node.desc).append("\", receiver, ").append(staticField)
                    .append(", '").append(kind).append("') }; ");
            code.append("if value.is_none() { ").append(exceptionPath).append(" } ");
            code.append("stack.push(refs.track_value(value.unwrap()));");
        } else {
            code.append("let success = unsafe { set_field(_env, \"").append(node.owner)
                    .append("\", \"").append(node.name).append("\", \"")
                    .append(node.desc).append("\", receiver, ").append(staticField)
                    .append(", value, '").append(kind).append("') }; ");
            code.append("if !success { ").append(exceptionPath).append(" } ");
        }
    }
}
