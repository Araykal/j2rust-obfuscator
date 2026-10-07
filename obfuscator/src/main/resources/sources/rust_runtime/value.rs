use jni_sys::jobject;

#[derive(Clone, Copy)]
pub enum Value {
    I(i32),
    J(i64),
    F(f32),
    D(f64),
    O(jobject),
}

impl Value {
    pub fn i(self) -> i32 { if let Self::I(value) = self { value } else { unreachable!() } }
    pub fn j(self) -> i64 { if let Self::J(value) = self { value } else { unreachable!() } }
    pub fn f(self) -> f32 { if let Self::F(value) = self { value } else { unreachable!() } }
    pub fn d(self) -> f64 { if let Self::D(value) = self { value } else { unreachable!() } }
    pub fn o(self) -> jobject { if let Self::O(value) = self { value } else { unreachable!() } }
    pub(crate) fn category(self) -> usize { if matches!(self, Self::J(_) | Self::D(_)) { 2 } else { 1 } }
}
