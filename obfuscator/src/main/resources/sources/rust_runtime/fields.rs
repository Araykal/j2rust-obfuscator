use super::*;
use jni_sys::*;
use std::ffi::CString;

pub unsafe fn get_field(
    env: *mut JNIEnv, owner: &str, name: &str, signature: &str,
    receiver: jobject, static_field: bool, result: char,
) -> Option<Value> {
    if !static_field && receiver.is_null() {
        throw_named(env, "java/lang/NullPointerException", name);
        return None;
    }
    let class = find_class(env, owner);
    if class.is_null() || has_exception(env) { return None; }
    let field_name = CString::new(name).unwrap();
    let descriptor = CString::new(signature).unwrap();
    let field = if static_field {
        (**env).GetStaticFieldID.unwrap()(env, class, field_name.as_ptr(), descriptor.as_ptr())
    } else {
        (**env).GetFieldID.unwrap()(env, class, field_name.as_ptr(), descriptor.as_ptr())
    };
    if field.is_null() || has_exception(env) {
        (**env).DeleteLocalRef.unwrap()(env, class);
        return None;
    }
    let value = match (static_field, result) {
        (true, 'J') => Value::J((**env).GetStaticLongField.unwrap()(env, class, field)),
        (true, 'F') => Value::F((**env).GetStaticFloatField.unwrap()(env, class, field)),
        (true, 'D') => Value::D((**env).GetStaticDoubleField.unwrap()(env, class, field)),
        (true, 'L') => Value::O((**env).GetStaticObjectField.unwrap()(env, class, field)),
        (true, 'Z') => Value::I((**env).GetStaticBooleanField.unwrap()(env, class, field) as i32),
        (true, 'B') => Value::I((**env).GetStaticByteField.unwrap()(env, class, field) as i32),
        (true, 'C') => Value::I((**env).GetStaticCharField.unwrap()(env, class, field) as i32),
        (true, 'S') => Value::I((**env).GetStaticShortField.unwrap()(env, class, field) as i32),
        (true, _) => Value::I((**env).GetStaticIntField.unwrap()(env, class, field)),
        (false, 'J') => Value::J((**env).GetLongField.unwrap()(env, receiver, field)),
        (false, 'F') => Value::F((**env).GetFloatField.unwrap()(env, receiver, field)),
        (false, 'D') => Value::D((**env).GetDoubleField.unwrap()(env, receiver, field)),
        (false, 'L') => Value::O((**env).GetObjectField.unwrap()(env, receiver, field)),
        (false, 'Z') => Value::I((**env).GetBooleanField.unwrap()(env, receiver, field) as i32),
        (false, 'B') => Value::I((**env).GetByteField.unwrap()(env, receiver, field) as i32),
        (false, 'C') => Value::I((**env).GetCharField.unwrap()(env, receiver, field) as i32),
        (false, 'S') => Value::I((**env).GetShortField.unwrap()(env, receiver, field) as i32),
        (false, _) => Value::I((**env).GetIntField.unwrap()(env, receiver, field)),
    };
    (**env).DeleteLocalRef.unwrap()(env, class);
    if has_exception(env) { None } else { Some(value) }
}

pub unsafe fn set_field(
    env: *mut JNIEnv, owner: &str, name: &str, signature: &str,
    receiver: jobject, static_field: bool, value: Value, result: char,
) -> bool {
    if !static_field && receiver.is_null() {
        throw_named(env, "java/lang/NullPointerException", name);
        return false;
    }
    let class = find_class(env, owner);
    if class.is_null() || has_exception(env) { return false; }
    let field_name = CString::new(name).unwrap();
    let descriptor = CString::new(signature).unwrap();
    let field = if static_field {
        (**env).GetStaticFieldID.unwrap()(env, class, field_name.as_ptr(), descriptor.as_ptr())
    } else {
        (**env).GetFieldID.unwrap()(env, class, field_name.as_ptr(), descriptor.as_ptr())
    };
    if field.is_null() || has_exception(env) {
        (**env).DeleteLocalRef.unwrap()(env, class);
        return false;
    }
    match (static_field, result) {
        (true, 'J') => (**env).SetStaticLongField.unwrap()(env, class, field, value.j()),
        (true, 'F') => (**env).SetStaticFloatField.unwrap()(env, class, field, value.f()),
        (true, 'D') => (**env).SetStaticDoubleField.unwrap()(env, class, field, value.d()),
        (true, 'L') => (**env).SetStaticObjectField.unwrap()(env, class, field, value.o()),
        (true, 'Z') => (**env).SetStaticBooleanField.unwrap()(env, class, field, value.i() as u8),
        (true, 'B') => (**env).SetStaticByteField.unwrap()(env, class, field, value.i() as i8),
        (true, 'C') => (**env).SetStaticCharField.unwrap()(env, class, field, value.i() as u16),
        (true, 'S') => (**env).SetStaticShortField.unwrap()(env, class, field, value.i() as i16),
        (true, _) => (**env).SetStaticIntField.unwrap()(env, class, field, value.i()),
        (false, 'J') => (**env).SetLongField.unwrap()(env, receiver, field, value.j()),
        (false, 'F') => (**env).SetFloatField.unwrap()(env, receiver, field, value.f()),
        (false, 'D') => (**env).SetDoubleField.unwrap()(env, receiver, field, value.d()),
        (false, 'L') => (**env).SetObjectField.unwrap()(env, receiver, field, value.o()),
        (false, 'Z') => (**env).SetBooleanField.unwrap()(env, receiver, field, value.i() as u8),
        (false, 'B') => (**env).SetByteField.unwrap()(env, receiver, field, value.i() as i8),
        (false, 'C') => (**env).SetCharField.unwrap()(env, receiver, field, value.i() as u16),
        (false, 'S') => (**env).SetShortField.unwrap()(env, receiver, field, value.i() as i16),
        (false, _) => (**env).SetIntField.unwrap()(env, receiver, field, value.i()),
    }
    (**env).DeleteLocalRef.unwrap()(env, class);
    !has_exception(env)
}
