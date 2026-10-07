use jni_sys::*;
use std::ffi::CString;
use std::sync::OnceLock;

pub unsafe fn literal(env: *mut JNIEnv, units: &[u16], slot: &OnceLock<usize>) -> jobject {
    if let Some(&handle) = slot.get() { return handle as jobject; }
    let string = new_string(env, units);
    if string.is_null() || has_exception(env) { return string; }
    let global = (**env).NewGlobalRef.unwrap()(env, string);
    (**env).DeleteLocalRef.unwrap()(env, string);
    if global.is_null() || has_exception(env) { return std::ptr::null_mut(); }
    if slot.set(global as usize).is_err() {
        (**env).DeleteGlobalRef.unwrap()(env, global);
    }
    *slot.get().unwrap() as jobject
}

pub unsafe fn has_exception(env: *mut JNIEnv) -> bool {
    (**env).ExceptionCheck.unwrap()(env) != 0
}

pub unsafe fn match_exception(env: *mut JNIEnv, catch_types: &[&str]) -> Option<(jobject, usize)> {
    let exception = (**env).ExceptionOccurred.unwrap()(env);
    (**env).ExceptionClear.unwrap()(env);
    for (index, catch_type) in catch_types.iter().enumerate() {
        if catch_type.is_empty() {
            return Some((exception, index));
        }
        let class = find_class(env, catch_type);
        if class.is_null() || has_exception(env) {
            (**env).ExceptionClear.unwrap()(env);
            continue;
        }
        let matches = (**env).IsInstanceOf.unwrap()(env, exception, class) != 0;
        (**env).DeleteLocalRef.unwrap()(env, class);
        if matches {
            return Some((exception, index));
        }
    }
    (**env).Throw.unwrap()(env, exception);
    (**env).DeleteLocalRef.unwrap()(env, exception);
    None
}

pub unsafe fn throw_named(env: *mut JNIEnv, name: &str, message: &str) {
    let class_name = CString::new(name).unwrap();
    let text = CString::new(message).unwrap();
    let exception = (**env).FindClass.unwrap()(env, class_name.as_ptr());
    if !exception.is_null() {
        (**env).ThrowNew.unwrap()(env, exception, text.as_ptr());
        (**env).DeleteLocalRef.unwrap()(env, exception);
    }
}

pub unsafe fn new_string(env: *mut JNIEnv, units: &[u16]) -> jobject {
    let string = (**env).NewString.unwrap()(env, units.as_ptr(), units.len() as i32) as jobject;
    if string.is_null() || has_exception(env) { return string; }
    let class = find_class(env, "java/lang/String");
    if class.is_null() || has_exception(env) { return string; }
    let name = CString::new("intern").unwrap();
    let descriptor = CString::new("()Ljava/lang/String;").unwrap();
    let method = (**env).GetMethodID.unwrap()(env, class, name.as_ptr(), descriptor.as_ptr());
    if method.is_null() || has_exception(env) { return string; }
    let interned = (**env).CallObjectMethodA.unwrap()(env, string, method, std::ptr::null());
    (**env).DeleteLocalRef.unwrap()(env, class);
    (**env).DeleteLocalRef.unwrap()(env, string);
    interned
}

pub unsafe fn find_class(env: *mut JNIEnv, name: &str) -> jclass {
    let class_name = CString::new(name).unwrap();
    (**env).FindClass.unwrap()(env, class_name.as_ptr())
}

pub unsafe fn throw_arithmetic(env: *mut JNIEnv) {
    let name = CString::new("java/lang/ArithmeticException").unwrap();
    let message = CString::new("/ by zero").unwrap();
    let exception = (**env).FindClass.unwrap()(env, name.as_ptr());
    if !exception.is_null() {
        (**env).ThrowNew.unwrap()(env, exception, message.as_ptr());
        (**env).DeleteLocalRef.unwrap()(env, exception);
    }
}
