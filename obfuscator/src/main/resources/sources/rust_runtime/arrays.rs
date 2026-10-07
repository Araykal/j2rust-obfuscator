use super::*;
use jni_sys::*;

pub unsafe fn new_primitive_array(env: *mut JNIEnv, kind: i32, length: i32) -> jobject {
    match kind {
        4 => (**env).NewBooleanArray.unwrap()(env, length) as jobject,
        5 => (**env).NewCharArray.unwrap()(env, length) as jobject,
        6 => (**env).NewFloatArray.unwrap()(env, length) as jobject,
        7 => (**env).NewDoubleArray.unwrap()(env, length) as jobject,
        8 => (**env).NewByteArray.unwrap()(env, length) as jobject,
        9 => (**env).NewShortArray.unwrap()(env, length) as jobject,
        10 => (**env).NewIntArray.unwrap()(env, length) as jobject,
        11 => (**env).NewLongArray.unwrap()(env, length) as jobject,
        _ => unreachable!(),
    }
}

pub unsafe fn new_multi_array(env: *mut JNIEnv, descriptor: &str, sizes: &[i32]) -> jobject {
    let component = &descriptor[1..];
    let length = sizes[0];
    if component.len() == 1 {
        let kind = match component.as_bytes()[0] {
            b'Z' => 4, b'C' => 5, b'F' => 6, b'D' => 7,
            b'B' => 8, b'S' => 9, b'I' => 10, b'J' => 11,
            _ => unreachable!(),
        };
        return new_primitive_array(env, kind, length);
    }
    let class_name = if component.starts_with('L') {
        &component[1..component.len() - 1]
    } else {
        component
    };
    let class = find_class(env, class_name);
    if class.is_null() || has_exception(env) { return std::ptr::null_mut(); }
    let array = (**env).NewObjectArray.unwrap()(env, length, class, std::ptr::null_mut());
    (**env).DeleteLocalRef.unwrap()(env, class);
    if array.is_null() || has_exception(env) { return array; }
    if sizes.len() > 1 {
        for index in 0..length {
            let child = new_multi_array(env, component, &sizes[1..]);
            if child.is_null() || has_exception(env) { return array; }
            (**env).SetObjectArrayElement.unwrap()(env, array, index, child);
            (**env).DeleteLocalRef.unwrap()(env, child);
            if has_exception(env) { return array; }
        }
    }
    array
}

pub unsafe fn array_load(env: *mut JNIEnv, array: jobject, index: i32, kind: i32) -> Value {
    match kind {
        46 => { let mut value = 0; (**env).GetIntArrayRegion.unwrap()(env, array as jintArray, index, 1, &mut value); Value::I(value) }
        47 => { let mut value = 0; (**env).GetLongArrayRegion.unwrap()(env, array as jlongArray, index, 1, &mut value); Value::J(value) }
        48 => { let mut value = 0.0; (**env).GetFloatArrayRegion.unwrap()(env, array as jfloatArray, index, 1, &mut value); Value::F(value) }
        49 => { let mut value = 0.0; (**env).GetDoubleArrayRegion.unwrap()(env, array as jdoubleArray, index, 1, &mut value); Value::D(value) }
        50 => Value::O((**env).GetObjectArrayElement.unwrap()(env, array as jobjectArray, index)),
        51 => {
            let boolean_class = find_class(env, "[Z");
            if boolean_class.is_null() || has_exception(env) { return Value::I(0); }
            let is_boolean = (**env).IsInstanceOf.unwrap()(env, array, boolean_class) != 0;
            (**env).DeleteLocalRef.unwrap()(env, boolean_class);
            if is_boolean {
                let mut value = 0;
                (**env).GetBooleanArrayRegion.unwrap()(env, array as jbooleanArray, index, 1, &mut value);
                Value::I(value as i32)
            } else {
                let mut value = 0;
                (**env).GetByteArrayRegion.unwrap()(env, array as jbyteArray, index, 1, &mut value);
                Value::I(value as i32)
            }
        }
        52 => { let mut value = 0; (**env).GetCharArrayRegion.unwrap()(env, array as jcharArray, index, 1, &mut value); Value::I(value as i32) }
        53 => { let mut value = 0; (**env).GetShortArrayRegion.unwrap()(env, array as jshortArray, index, 1, &mut value); Value::I(value as i32) }
        _ => unreachable!(),
    }
}

pub unsafe fn array_store(env: *mut JNIEnv, array: jobject, index: i32, value: Value, kind: i32) {
    match kind {
        79 => (**env).SetIntArrayRegion.unwrap()(env, array as jintArray, index, 1, &value.i()),
        80 => (**env).SetLongArrayRegion.unwrap()(env, array as jlongArray, index, 1, &value.j()),
        81 => (**env).SetFloatArrayRegion.unwrap()(env, array as jfloatArray, index, 1, &value.f()),
        82 => (**env).SetDoubleArrayRegion.unwrap()(env, array as jdoubleArray, index, 1, &value.d()),
        83 => (**env).SetObjectArrayElement.unwrap()(env, array as jobjectArray, index, value.o()),
        84 => {
            let boolean_class = find_class(env, "[Z");
            if boolean_class.is_null() || has_exception(env) { return; }
            let is_boolean = (**env).IsInstanceOf.unwrap()(env, array, boolean_class) != 0;
            (**env).DeleteLocalRef.unwrap()(env, boolean_class);
            if is_boolean {
                (**env).SetBooleanArrayRegion.unwrap()(env, array as jbooleanArray, index, 1, &(value.i() as u8));
            } else {
                (**env).SetByteArrayRegion.unwrap()(env, array as jbyteArray, index, 1, &(value.i() as i8));
            }
        }
        85 => (**env).SetCharArrayRegion.unwrap()(env, array as jcharArray, index, 1, &(value.i() as u16)),
        86 => (**env).SetShortArrayRegion.unwrap()(env, array as jshortArray, index, 1, &(value.i() as i16)),
        _ => unreachable!(),
    }
}
