package com.araykal.j2rust.intrinsics;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

final class StringIntrinsics {
    private StringIntrinsics() {
    }

    static void register() {
        IntrinsicRegistry.register("java/lang/String", "length", "()I", StringIntrinsics::emitLength);
        IntrinsicRegistry.register("java/lang/String", "isEmpty", "()Z", StringIntrinsics::emitIsEmpty);
        IntrinsicRegistry.register("java/lang/String", "charAt", "(I)C", StringIntrinsics::emitCharAt);
        IntrinsicRegistry.register("java/lang/String", "substring", "(I)Ljava/lang/String;", StringIntrinsics::emitSubstring);
        IntrinsicRegistry.register("java/lang/String", "substring", "(II)Ljava/lang/String;", StringIntrinsics::emitSubstring);
    }

    private static void emitLength(StringBuilder code, MethodInsnNode node, String exceptionPath) {
        code.append("let text = stack.pop().unwrap().o(); ");
        nullCheck(code, exceptionPath, "length");
        code.append("stack.push(Value::I(unsafe { (**_env).GetStringLength.unwrap()(_env, text) }));");
    }

    private static void emitIsEmpty(StringBuilder code, MethodInsnNode node, String exceptionPath) {
        code.append("let text = stack.pop().unwrap().o(); ");
        nullCheck(code, exceptionPath, "isEmpty");
        code.append("stack.push(Value::I(if unsafe { (**_env).GetStringLength.unwrap()(_env, text) } == 0 { 1 } else { 0 }));");
    }

    private static void emitCharAt(StringBuilder code, MethodInsnNode node, String exceptionPath) {
        code.append("let index = stack.pop().unwrap().i(); let text = stack.pop().unwrap().o(); ");
        nullCheck(code, exceptionPath, "charAt");
        code.append("let length = unsafe { (**_env).GetStringLength.unwrap()(_env, text) }; ");
        code.append("if index < 0 || index >= length { unsafe { throw_named(_env, \"java/lang/StringIndexOutOfBoundsException\", \"charAt\"); } ")
                .append(exceptionPath).append(" } ");
        code.append("let mut unit: u16 = 0; unsafe { (**_env).GetStringRegion.unwrap()(_env, text as jni_sys::jstring, index, 1, &mut unit); } ");
        code.append("if unsafe { has_exception(_env) } { ").append(exceptionPath).append(" } ");
        code.append("stack.push(Value::I(unit as i32));");
    }

    private static void emitSubstring(StringBuilder code, MethodInsnNode node, String exceptionPath) {
        if (node.desc.equals("(I)Ljava/lang/String;")) {
            code.append("let begin = stack.pop().unwrap().i(); let text = stack.pop().unwrap().o(); ");
            code.append("let end = if text.is_null() { 0 } else { unsafe { (**_env).GetStringLength.unwrap()(_env, text) } }; ");
        } else {
            code.append("let end = stack.pop().unwrap().i(); let begin = stack.pop().unwrap().i(); let text = stack.pop().unwrap().o(); ");
        }
        code.append("let result = unsafe { substring(_env, text, begin, end) }; ");
        code.append("if unsafe { has_exception(_env) } { ").append(exceptionPath).append(" } ");
        code.append("stack.push(Value::O(refs.track(result))); ");
    }

    private static void nullCheck(StringBuilder code, String exceptionPath, String method) {
        code.append("if text.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"")
                .append(method).append("\"); } ").append(exceptionPath).append(" } ");
    }
}
