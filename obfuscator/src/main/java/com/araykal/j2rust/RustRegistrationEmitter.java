package com.araykal.j2rust;

import com.araykal.j2rust.model.MethodRegistration;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

final class RustRegistrationEmitter {
    private RustRegistrationEmitter() {
    }

    static String emit(Map<String, List<MethodRegistration>> classes, String loaderName) {
        StringBuilder code = new StringBuilder(
                "#[no_mangle]\npub unsafe extern \"system\" fn " +
                RustMethodEmitter.symbol(loaderName, new MethodNode(Opcodes.ASM9, 0,
                        "rustRegisterNatives", "(Ljava/lang/String;Ljava/lang/Class;)V", null, null)) +
                "(env: *mut jni_sys::JNIEnv, _loader: jni_sys::jclass, " +
                "name: jni_sys::jstring, clazz: jni_sys::jclass) {\n" +
                "    let chars = (**env).GetStringUTFChars.unwrap()(env, name, std::ptr::null_mut());\n" +
                "    if chars.is_null() { return; }\n" +
                "    let class_name = std::ffi::CStr::from_ptr(chars).to_bytes();\n" +
                "    let status = match class_name {\n");
        for (Map.Entry<String, List<MethodRegistration>> entry : classes.entrySet()) {
            code.append("        b\"").append(entry.getKey()).append("\" => {\n");
            code.append("            let methods = [\n");
            for (MethodRegistration method : entry.getValue()) {
                code.append("                jni_sys::JNINativeMethod { name: b\"")
                        .append(method.getName()).append("\\0\".as_ptr() as *mut std::ffi::c_char, signature: b\"")
                        .append(method.getDescriptor()).append("\\0\".as_ptr() as *mut std::ffi::c_char, fnPtr: ")
                        .append(method.getSymbol()).append(" as *mut c_void },\n");
            }
            code.append("            ];\n");
            code.append("            (**env).RegisterNatives.unwrap()(env, clazz, methods.as_ptr(), methods.len() as i32)\n");
            code.append("        },\n");
        }
        code.append("        _ => -1,\n    };\n");
        code.append("    (**env).ReleaseStringUTFChars.unwrap()(env, name, chars);\n");
        code.append("    if status != 0 && !has_exception(env) {\n");
        code.append("        throw_named(env, \"java/lang/UnsatisfiedLinkError\", \"JNI registration failed\");\n");
        code.append("    }\n}\n");
        return code.toString();
    }
}
