use super::Value;
use jni_sys::{JNIEnv, jobject};
use std::collections::HashSet;

pub struct LocalRefs {
    handles: Vec<jobject>,
    known: HashSet<usize>,
}

impl LocalRefs {
    pub fn new() -> Self {
        Self { handles: Vec::new(), known: HashSet::new() }
    }

    pub fn track(&mut self, handle: jobject) -> jobject {
        if !handle.is_null() && self.known.insert(handle as usize) {
            self.handles.push(handle);
        }
        handle
    }

    pub fn track_value(&mut self, value: Value) -> Value {
        if let Value::O(handle) = value {
            self.track(handle);
        }
        value
    }

    pub unsafe fn sweep(&mut self, env: *mut JNIEnv, locals: &[Value], stack: &[Value]) {
        self.handles.retain(|&handle| {
            let live = locals.iter().chain(stack.iter()).any(|value| {
                matches!(value, Value::O(current) if *current == handle)
            });
            if !live {
                (**env).DeleteLocalRef.unwrap()(env, handle);
            }
            live
        });
        self.known.clear();
        self.known.extend(self.handles.iter().map(|&handle| handle as usize));
    }
}
