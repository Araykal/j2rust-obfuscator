use jni_sys::{JNIEnv, jobject, jstring};
use std::cell::RefCell;

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
