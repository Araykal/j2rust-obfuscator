use jni_sys::{JNIEnv, jobject, jstring};
use std::cell::RefCell;
use super::throw_named;

thread_local! {
    static SCRATCH: RefCell<Vec<u16>> = RefCell::new(Vec::new());
}

pub unsafe fn concat_literal(env: *mut JNIEnv, left: jobject, suffix: &[u16]) -> jobject {
    SCRATCH.with(|buffer| {
        let mut units = buffer.borrow_mut();
        units.clear();
        if left.is_null() {
            units.extend_from_slice(&[110, 117, 108, 108]);
        } else {
            let length = (**env).GetStringLength.unwrap()(env, left as jstring);
            units.resize(length as usize, 0);
            (**env).GetStringRegion.unwrap()(env, left as jstring, 0, length, units.as_mut_ptr());
            if (**env).ExceptionCheck.unwrap()(env) != 0 { return std::ptr::null_mut(); }
        }
        units.extend_from_slice(suffix);
        (**env).NewString.unwrap()(env, units.as_ptr(), units.len() as i32) as jobject
    })
}

pub unsafe fn substring(env: *mut JNIEnv, value: jobject, begin: i32, end: i32) -> jobject {
    if value.is_null() {
        throw_named(env, "java/lang/NullPointerException", "substring");
        return std::ptr::null_mut();
    }
    let length = (**env).GetStringLength.unwrap()(env, value as jstring);
    if begin < 0 || end < begin || end > length {
        throw_named(env, "java/lang/StringIndexOutOfBoundsException", "substring");
        return std::ptr::null_mut();
    }
    let size = end - begin;
    let mut units = vec![0u16; size as usize];
    (**env).GetStringRegion.unwrap()(env, value as jstring, begin, size, units.as_mut_ptr());
    if (**env).ExceptionCheck.unwrap()(env) != 0 { return std::ptr::null_mut(); }
    (**env).NewString.unwrap()(env, units.as_ptr(), size) as jobject
}

pub unsafe fn equals(env: *mut JNIEnv, left: jobject, right: jobject) -> i32 {
    if left.is_null() || right.is_null() { return 0; }
    let class = super::find_class(env, "java/lang/String");
    if class.is_null() || (**env).IsInstanceOf.unwrap()(env, right, class) == 0 {
        if !class.is_null() { (**env).DeleteLocalRef.unwrap()(env, class); }
        return 0;
    }
    (**env).DeleteLocalRef.unwrap()(env, class);
    let left_length = (**env).GetStringLength.unwrap()(env, left as jstring);
    let right_length = (**env).GetStringLength.unwrap()(env, right as jstring);
    if left_length != right_length { return 0; }
    let mut left_units = vec![0u16; left_length as usize];
    let mut right_units = vec![0u16; right_length as usize];
    (**env).GetStringRegion.unwrap()(env, left as jstring, 0, left_length, left_units.as_mut_ptr());
    (**env).GetStringRegion.unwrap()(env, right as jstring, 0, right_length, right_units.as_mut_ptr());
    if (**env).ExceptionCheck.unwrap()(env) != 0 { return 0; }
    if left_units == right_units { 1 } else { 0 }
}
