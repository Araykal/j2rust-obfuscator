use super::*;
use jni_sys::*;
use std::ffi::CString;
use std::sync::OnceLock;

pub unsafe fn call_method(
    env: *mut JNIEnv,
    opcode: i32,
    owner: &str,
    name: &str,
    signature: &str,
    receiver: jobject,
    args: &[jvalue],
    result: char,
    cache: Option<&OnceLock<(usize, usize)>>,
) -> Option<Value> {
    if opcode != 184 && receiver.is_null() {
        throw_named(env, "java/lang/NullPointerException", name);
        return None;
    }
    let cached = cache.and_then(|slot| slot.get());
    let (class, method, local_class) = if let Some(&(class, method)) = cached {
        (class as jclass, method as jmethodID, false)
    } else {
        let class = if cache.is_some() || opcode == 184 || opcode == 183 {
            find_class(env, owner)
        } else {
            (**env).GetObjectClass.unwrap()(env, receiver)
        };
        if class.is_null() || has_exception(env) { return None; }
        let method_name = CString::new(name).unwrap();
        let descriptor = CString::new(signature).unwrap();
        let method = if opcode == 184 {
            (**env).GetStaticMethodID.unwrap()(env, class, method_name.as_ptr(), descriptor.as_ptr())
        } else {
            (**env).GetMethodID.unwrap()(env, class, method_name.as_ptr(), descriptor.as_ptr())
        };
        if method.is_null() || has_exception(env) {
            (**env).DeleteLocalRef.unwrap()(env, class);
            return None;
        }
        if let Some(slot) = cache {
            let global = (**env).NewGlobalRef.unwrap()(env, class);
            if global.is_null() || has_exception(env) {
                (**env).DeleteLocalRef.unwrap()(env, class);
                return None;
            }
            if slot.set((global as usize, method as usize)).is_err() {
                (**env).DeleteGlobalRef.unwrap()(env, global);
            }
        }
        (class, method, true)
    };
    let arguments = args.as_ptr();
    let value = match (opcode, result) {
        (184, 'V') => { (**env).CallStaticVoidMethodA.unwrap()(env, class, method, arguments); Value::I(0) }
        (184, 'J') => Value::J((**env).CallStaticLongMethodA.unwrap()(env, class, method, arguments)),
        (184, 'F') => Value::F((**env).CallStaticFloatMethodA.unwrap()(env, class, method, arguments)),
        (184, 'D') => Value::D((**env).CallStaticDoubleMethodA.unwrap()(env, class, method, arguments)),
        (184, 'L') => Value::O((**env).CallStaticObjectMethodA.unwrap()(env, class, method, arguments)),
        (184, 'Z') => Value::I((**env).CallStaticBooleanMethodA.unwrap()(env, class, method, arguments) as i32),
        (184, 'B') => Value::I((**env).CallStaticByteMethodA.unwrap()(env, class, method, arguments) as i32),
        (184, 'C') => Value::I((**env).CallStaticCharMethodA.unwrap()(env, class, method, arguments) as i32),
        (184, 'S') => Value::I((**env).CallStaticShortMethodA.unwrap()(env, class, method, arguments) as i32),
        (184, _) => Value::I((**env).CallStaticIntMethodA.unwrap()(env, class, method, arguments)),
        (183, 'V') => { (**env).CallNonvirtualVoidMethodA.unwrap()(env, receiver, class, method, arguments); Value::I(0) }
        (183, 'J') => Value::J((**env).CallNonvirtualLongMethodA.unwrap()(env, receiver, class, method, arguments)),
        (183, 'F') => Value::F((**env).CallNonvirtualFloatMethodA.unwrap()(env, receiver, class, method, arguments)),
        (183, 'D') => Value::D((**env).CallNonvirtualDoubleMethodA.unwrap()(env, receiver, class, method, arguments)),
        (183, 'L') => Value::O((**env).CallNonvirtualObjectMethodA.unwrap()(env, receiver, class, method, arguments)),
        (183, 'Z') => Value::I((**env).CallNonvirtualBooleanMethodA.unwrap()(env, receiver, class, method, arguments) as i32),
        (183, 'B') => Value::I((**env).CallNonvirtualByteMethodA.unwrap()(env, receiver, class, method, arguments) as i32),
        (183, 'C') => Value::I((**env).CallNonvirtualCharMethodA.unwrap()(env, receiver, class, method, arguments) as i32),
        (183, 'S') => Value::I((**env).CallNonvirtualShortMethodA.unwrap()(env, receiver, class, method, arguments) as i32),
        (183, _) => Value::I((**env).CallNonvirtualIntMethodA.unwrap()(env, receiver, class, method, arguments)),
        (_, 'V') => { (**env).CallVoidMethodA.unwrap()(env, receiver, method, arguments); Value::I(0) }
        (_, 'J') => Value::J((**env).CallLongMethodA.unwrap()(env, receiver, method, arguments)),
        (_, 'F') => Value::F((**env).CallFloatMethodA.unwrap()(env, receiver, method, arguments)),
        (_, 'D') => Value::D((**env).CallDoubleMethodA.unwrap()(env, receiver, method, arguments)),
        (_, 'L') => Value::O((**env).CallObjectMethodA.unwrap()(env, receiver, method, arguments)),
        (_, 'Z') => Value::I((**env).CallBooleanMethodA.unwrap()(env, receiver, method, arguments) as i32),
        (_, 'B') => Value::I((**env).CallByteMethodA.unwrap()(env, receiver, method, arguments) as i32),
        (_, 'C') => Value::I((**env).CallCharMethodA.unwrap()(env, receiver, method, arguments) as i32),
        (_, 'S') => Value::I((**env).CallShortMethodA.unwrap()(env, receiver, method, arguments) as i32),
        (_, _) => Value::I((**env).CallIntMethodA.unwrap()(env, receiver, method, arguments)),
    };
    if local_class { (**env).DeleteLocalRef.unwrap()(env, class); }
    if has_exception(env) { None } else { Some(value) }
}

pub unsafe fn allocate_object(env: *mut JNIEnv, owner: &str) -> jobject {
    let class = find_class(env, owner);
    if class.is_null() || has_exception(env) { return std::ptr::null_mut(); }
    let object = (**env).AllocObject.unwrap()(env, class);
    (**env).DeleteLocalRef.unwrap()(env, class);
    object
}
