package com.araykal.j2rust;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

final class RustMethodEmitter {
    private RustMethodEmitter() {
    }


    static String emit(ClassNode clazz, MethodNode method) {
        return emit(clazz, method, Collections.emptySet());
    }

    static String emit(ClassNode clazz, MethodNode method, Set<String> directMethods) {
        Type result = Type.getReturnType(method.desc);
        Type[] arguments = Type.getArgumentTypes(method.desc);
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        String symbol = symbol(clazz.name, method);
        StringBuilder code = new StringBuilder("#[no_mangle]\npub extern \"system\" fn ")
                .append(symbol).append("(_env: *mut jni_sys::JNIEnv, _receiver: *mut c_void");
        for (int index = 0; index < arguments.length; index++)
            code.append(", arg").append(index).append(": ").append(rustType(arguments[index]));
        code.append(")");
        if (result.getSort() != Type.VOID) code.append(" -> ").append(rustType(result));
        code.append(" {\n    let mut locals = vec![Value::I(0); ")
                .append(Math.max(method.maxLocals, arguments.length + (isStatic ? 0 : 1))).append("];\n");
        if (!isStatic) code.append("    locals[0] = Value::O(_receiver as jni_sys::jobject);\n");
        int slot = isStatic ? 0 : 1;
        for (int index = 0; index < arguments.length; index++) {
            code.append("    locals[").append(slot).append("] = Value::")
                    .append(valueTag(arguments[index])).append("(");
            if (valueTag(arguments[index]).equals("I") && arguments[index].getSort() != Type.INT)
                code.append("arg").append(index).append(" as i32");
            else code.append("arg").append(index);
            code.append(");\n");
            slot += arguments[index].getSize();
        }
        code.append("    let mut stack: Vec<Value> = Vec::with_capacity(").append(Math.max(method.maxStack, 1)).append(");\n");
        code.append("    let mut refs = LocalRefs::new();\n    let mut ticks: u32 = 0;\n");
        code.append("    let mut pc: usize = 0;\n    loop {\n        ticks = ticks.wrapping_add(1);\n")
                .append("        if ticks & 255 == 0 { unsafe { refs.sweep(_env, &locals, &stack); } }\n")
                .append("        match pc {\n");
        List<AbstractInsnNode> instructions = new ArrayList<>();
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LabelNode) labels.put((LabelNode) node, instructions.size());
            if (node.getOpcode() >= 0) instructions.add(node);
        }
        for (int index = 0; index < instructions.size(); index++) {
            AbstractInsnNode node = instructions.get(index);
            int opcode = node.getOpcode();
            String exceptionPath = exceptionPath(method, labels, index, result);
            code.append("            ").append(index).append(" => { ");
            RustStringConcatPattern concat = method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty() ?
                    RustStringConcatPattern.match(instructions, index, labels) : null;
            if (concat != null) {
                concat.emit(code, exceptionPath);
                code.append("pc = ").append(index + 8).append("; continue; }\n");
                continue;
            }
            if (opcode == Opcodes.NOP) {
                code.append(" ");
            } else if (opcode == Opcodes.ACONST_NULL) {
                code.append("stack.push(Value::O(std::ptr::null_mut()));");
            } else if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                code.append("stack.push(Value::I(").append(opcode - Opcodes.ICONST_0).append("));");
            } else if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) {
                code.append("stack.push(Value::J(").append(opcode - Opcodes.LCONST_0).append("));");
            } else if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) {
                code.append("stack.push(Value::F(").append(opcode - Opcodes.FCONST_0).append(".0));");
            } else if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) {
                code.append("stack.push(Value::D(").append(opcode - Opcodes.DCONST_0).append(".0));");
            } else if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                code.append("stack.push(Value::I(").append(((IntInsnNode) node).operand).append("));");
            } else if (opcode == Opcodes.LDC) {
                Object constant = ((LdcInsnNode) node).cst;
                if (constant instanceof String) {
                    code.append("static LITERAL: std::sync::OnceLock<usize> = std::sync::OnceLock::new(); ");
                    code.append("stack.push(Value::O(unsafe { literal(_env, &[");
                    String value = (String) constant;
                    for (int unit = 0; unit < value.length(); unit++)
                        code.append((int) value.charAt(unit)).append(",");
                    code.append("], &LITERAL) }));");
                    appendExceptionCheck(code, exceptionPath);
                } else if (constant instanceof Type) {
                    code.append("stack.push(Value::O(refs.track(unsafe { find_class(_env, \"")
                            .append(((Type) constant).getInternalName()).append("\") } as jni_sys::jobject)));");
                    appendExceptionCheck(code, exceptionPath);
                } else {
                    code.append("stack.push(Value::").append(constant instanceof Long ? "J" :
                            constant instanceof Float ? "F" : constant instanceof Double ? "D" : "I")
                            .append("(").append(number(constant)).append("));");
                }
            } else if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                code.append("stack.push(locals[").append(((VarInsnNode) node).var).append("]);");
            } else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                code.append("locals[").append(((VarInsnNode) node).var).append("] = stack.pop().unwrap();");
            } else if (opcode == Opcodes.IINC) {
                IincInsnNode increment = (IincInsnNode) node;
                code.append("locals[").append(increment.var).append("] = Value::I(locals[")
                        .append(increment.var).append("].i().wrapping_add(").append(increment.incr).append("));");
            } else if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                if (opcode == Opcodes.RETURN) code.append("return;");
                else {
                    String accessor = opcode == Opcodes.LRETURN ? "j" :
                            opcode == Opcodes.FRETURN ? "f" : opcode == Opcodes.DRETURN ? "d" :
                            opcode == Opcodes.ARETURN ? "o" : "i";
                    code.append("return stack.pop().unwrap().").append(accessor).append("()");
                    if (result.getSort() != Type.INT && accessor.equals("i"))
                        code.append(" as ").append(rustType(result));
                    code.append(";");
                }
            } else if (opcode >= Opcodes.POP && opcode <= Opcodes.SWAP) {
                code.append("stack_op(&mut stack, ").append(opcode).append(");");
            } else if (opcode == Opcodes.GOTO) {
                code.append("pc = ").append(labels.get(((JumpInsnNode) node).label)).append("; continue;");
            } else if (opcode == Opcodes.ATHROW) {
                code.append("let exception = stack.pop().unwrap().o(); ");
                code.append("if exception.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"athrow\"); } } ");
                code.append("else { unsafe { (**_env).Throw.unwrap()(_env, exception); } } ");
                code.append(exceptionPath);
            } else if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL ||
                    opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) {
                emitReferenceBranch(code, opcode, labels.get(((JumpInsnNode) node).label));
            } else if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ICMPLE) {
                emitBranch(code, opcode, labels.get(((JumpInsnNode) node).label));
            } else if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) {
                emitSwitch(code, node, labels);
            } else if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
                code.append("let at = stack.pop().unwrap().i(); let array = stack.pop().unwrap().o(); ");
                code.append("if array.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"array load\"); } ")
                        .append(exceptionPath).append(" } ");
                code.append("let value = unsafe { array_load(_env, array, at, ").append(opcode).append(") }; ");
                appendExceptionCheck(code, exceptionPath);
                code.append(" stack.push(refs.track_value(value));");
            } else if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
                code.append("let value = stack.pop().unwrap(); let at = stack.pop().unwrap().i(); let array = stack.pop().unwrap().o(); ");
                code.append("if array.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"array store\"); } ")
                        .append(exceptionPath).append(" } ");
                code.append("unsafe { array_store(_env, array, at, value, ").append(opcode).append("); } ");
                appendExceptionCheck(code, exceptionPath);
            } else if (opcode == Opcodes.NEWARRAY) {
                code.append("let length = stack.pop().unwrap().i(); stack.push(Value::O(refs.track(unsafe { new_primitive_array(_env, ")
                        .append(((IntInsnNode) node).operand).append(", length) }))); ");
                appendExceptionCheck(code, exceptionPath);
            } else if (opcode == Opcodes.ANEWARRAY) {
                code.append("let length = stack.pop().unwrap().i(); let class = unsafe { find_class(_env, \"")
                        .append(((TypeInsnNode) node).desc).append("\") }; ");
                appendExceptionCheck(code, exceptionPath);
                code.append("let array = unsafe { (**_env).NewObjectArray.unwrap()(_env, length, class, std::ptr::null_mut()) }; ");
                code.append("unsafe { (**_env).DeleteLocalRef.unwrap()(_env, class); } ");
                code.append("stack.push(Value::O(refs.track(array))); ");
                appendExceptionCheck(code, exceptionPath);
            } else if (opcode == Opcodes.NEW) {
                code.append("stack.push(Value::O(refs.track(unsafe { allocate_object(_env, \"")
                        .append(((TypeInsnNode) node).desc).append("\") }))); ");
                appendExceptionCheck(code, exceptionPath);
            } else if (opcode == Opcodes.MULTIANEWARRAY) {
                MultiANewArrayInsnNode array = (MultiANewArrayInsnNode) node;
                code.append("let mut sizes = vec![0i32; ").append(array.dims).append("]; ");
                for (int dimension = array.dims - 1; dimension >= 0; dimension--)
                    code.append("sizes[").append(dimension).append("] = stack.pop().unwrap().i(); ");
                code.append("stack.push(Value::O(refs.track(unsafe { new_multi_array(_env, \"")
                        .append(array.desc).append("\", &sizes) }))); ");
                appendExceptionCheck(code, exceptionPath);
            } else if (opcode == Opcodes.ARRAYLENGTH) {
                code.append("let array = stack.pop().unwrap().o(); if array.is_null() { unsafe { throw_named(_env, \"java/lang/NullPointerException\", \"array length\"); } ")
                        .append(exceptionPath).append(" } ");
                code.append("stack.push(Value::I(unsafe { (**_env).GetArrayLength.unwrap()(_env, array) }));");
            } else if (opcode == Opcodes.INSTANCEOF || opcode == Opcodes.CHECKCAST) {
                RustJniEmitter.emitTypeCheck(code, (TypeInsnNode) node, exceptionPath);
            } else if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                code.append("let object = stack.pop().unwrap().o(); ");
                code.append("unsafe { (**_env).").append(opcode == Opcodes.MONITORENTER ? "MonitorEnter" : "MonitorExit")
                        .append(".unwrap()(_env, object); } ");
                appendExceptionCheck(code, exceptionPath);
            } else if (node instanceof FieldInsnNode) {
                RustJniEmitter.emitField(code, (FieldInsnNode) node, exceptionPath);
            } else if (node instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) node;
                if (isTailCall(clazz, method, call, instructions, index))
                    emitTailCall(code, call, method.maxLocals);
                else if (opcode == Opcodes.INVOKESTATIC && call.owner.equals(clazz.name) &&
                        directMethods.contains(call.name + call.desc))
                    RustJniEmitter.emitDirectCall(code, call, exceptionPath);
                else RustJniEmitter.emitCall(code, call, exceptionPath);
            } else if (opcode >= Opcodes.I2L && opcode <= Opcodes.I2S) {
                RustArithmeticEmitter.emitConversion(code, opcode);
            } else if (opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG) {
                RustArithmeticEmitter.emitComparison(code, opcode);
            } else if (opcode >= Opcodes.IADD && opcode <= Opcodes.DNEG ||
                    opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR) {
                RustArithmeticEmitter.emitArithmetic(code, opcode, exceptionPath);
            } else {
                throw new IllegalStateException("Unexpected opcode " + opcode);
            }
            if (opcode != Opcodes.GOTO && opcode != Opcodes.ATHROW &&
                    opcode != Opcodes.TABLESWITCH && opcode != Opcodes.LOOKUPSWITCH &&
                    (opcode < Opcodes.IRETURN || opcode > Opcodes.RETURN))
                code.append(" pc = ").append(index + 1).append(";");
            code.append(" }\n");
        }
        code.append("            _ => panic!(\"Invalid bytecode control flow\"),\n        }\n    }\n}\n\n");
        return code.toString();
    }

    private static boolean isTailCall(ClassNode clazz, MethodNode method, MethodInsnNode call,
                                      List<AbstractInsnNode> instructions, int index) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC || (method.access & Opcodes.ACC_STATIC) == 0 ||
                (method.access & Opcodes.ACC_SYNCHRONIZED) != 0 ||
                !call.owner.equals(clazz.name) || !call.name.equals(method.name) ||
                !call.desc.equals(method.desc) || method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty() ||
                Type.getArgumentTypes(method.desc).length == 0 || index + 1 >= instructions.size()) return false;
        return instructions.get(index + 1).getOpcode() == Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
    }

    private static void emitTailCall(StringBuilder code, MethodInsnNode call, int maxLocals) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int[] slots = new int[arguments.length];
        int nextSlot = 0;
        for (int index = 0; index < arguments.length; index++) {
            slots[index] = nextSlot;
            nextSlot += arguments[index].getSize();
        }
        for (int index = arguments.length - 1; index >= 0; index--)
            code.append("locals[").append(slots[index]).append("] = stack.pop().unwrap(); ");
        for (int index = nextSlot; index < maxLocals; index++)
            code.append("locals[").append(index).append("] = Value::I(0); ");
        code.append("stack.clear(); pc = 0; continue;");
    }

    private static void emitBranch(StringBuilder code, int opcode, int target) {
        String operator;
        switch (opcode) {
            case Opcodes.IFEQ: case Opcodes.IF_ICMPEQ: operator = "=="; break;
            case Opcodes.IFNE: case Opcodes.IF_ICMPNE: operator = "!="; break;
            case Opcodes.IFLT: case Opcodes.IF_ICMPLT: operator = "<"; break;
            case Opcodes.IFGE: case Opcodes.IF_ICMPGE: operator = ">="; break;
            case Opcodes.IFGT: case Opcodes.IF_ICMPGT: operator = ">"; break;
            default: operator = "<=";
        }
        if (opcode >= Opcodes.IF_ICMPEQ)
            code.append("let right = stack.pop().unwrap().i(); let left = stack.pop().unwrap().i();");
        else code.append("let left = stack.pop().unwrap().i();");
        code.append(" if left ").append(operator).append(opcode >= Opcodes.IF_ICMPEQ ? " right" : " 0")
                .append(" { pc = ").append(target).append("; continue; }");
    }

    private static void emitSwitch(StringBuilder code, AbstractInsnNode node,
                                   Map<LabelNode, Integer> labels) {
        code.append("let key = stack.pop().unwrap().i(); pc = match key { ");
        if (node instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode table = (TableSwitchInsnNode) node;
            for (int index = 0; index < table.labels.size(); index++)
                code.append(table.min + index).append(" => ").append(labels.get(table.labels.get(index))).append(", ");
            code.append("_ => ").append(labels.get(table.dflt));
        } else {
            LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) node;
            for (int index = 0; index < lookup.keys.size(); index++)
                code.append(lookup.keys.get(index)).append(" => ").append(labels.get(lookup.labels.get(index))).append(", ");
            code.append("_ => ").append(labels.get(lookup.dflt));
        }
        code.append(" }; continue;");
    }

    private static void emitReferenceBranch(StringBuilder code, int opcode, int target) {
        if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
            code.append("let value = stack.pop().unwrap().o(); if value.is_null() ")
                    .append(opcode == Opcodes.IFNULL ? "" : "== false ")
                    .append("{ pc = ").append(target).append("; continue; }");
        } else {
            code.append("let right = stack.pop().unwrap().o(); let left = stack.pop().unwrap().o(); if unsafe { (**_env).IsSameObject.unwrap()(_env, left, right) != 0 } ")
                    .append(opcode == Opcodes.IF_ACMPEQ ? "" : "== false").append(" { pc = ")
                    .append(target).append("; continue; }");
        }
    }



    static void appendExceptionCheck(StringBuilder code, String exceptionPath) {
        code.append("if unsafe { has_exception(_env) } { ").append(exceptionPath).append(" } ");
    }

    private static String exceptionPath(MethodNode method, Map<LabelNode, Integer> labels,
                                        int instruction, Type result) {
        List<TryCatchBlockNode> handlers = new ArrayList<>();
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode handler : method.tryCatchBlocks) {
                if (instruction >= labels.get(handler.start) && instruction < labels.get(handler.end))
                    handlers.add(handler);
            }
        }
        if (handlers.isEmpty()) return "return " + defaultValue(result) + ";";
        StringBuilder code = new StringBuilder("if let Some((exception, handler)) = unsafe { match_exception(_env, &[");
        for (TryCatchBlockNode handler : handlers)
            code.append("\"").append(handler.type == null ? "" : handler.type).append("\",");
        code.append("]) } { stack.clear(); stack.push(Value::O(refs.track(exception))); pc = match handler { ");
        for (int index = 0; index < handlers.size(); index++)
            code.append(index).append(" => ").append(labels.get(handlers.get(index).handler)).append(", ");
        code.append("_ => unreachable!() }; continue; } return ").append(defaultValue(result)).append(";");
        return code.toString();
    }

    static String defaultValue(Type type) {
        if (type.getSort() == Type.VOID) return "()";
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)
            return "std::ptr::null_mut()";
        return type.getSort() == Type.FLOAT || type.getSort() == Type.DOUBLE ? "0.0" : "0";
    }

    static String rustType(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN: return "u8";
            case Type.BYTE: return "i8";
            case Type.CHAR: return "u16";
            case Type.SHORT: return "i16";
            case Type.LONG: return "i64";
            case Type.FLOAT: return "f32";
            case Type.DOUBLE: return "f64";
            case Type.ARRAY:
            case Type.OBJECT: return "jni_sys::jobject";
            default: return "i32";
        }
    }

    static String valueTag(Type type) {
        switch (type.getSort()) {
            case Type.LONG: return "J";
            case Type.FLOAT: return "F";
            case Type.DOUBLE: return "D";
            case Type.ARRAY:
            case Type.OBJECT: return "O";
            default: return "I";
        }
    }

    private static String number(Object number) {
        if (number instanceof Float) {
            float value = (Float) number;
            if (Float.isNaN(value)) return "f32::NAN";
            if (Float.isInfinite(value)) return value > 0 ? "f32::INFINITY" : "f32::NEG_INFINITY";
            return value + "_f32";
        }
        if (number instanceof Double) {
            double value = (Double) number;
            if (Double.isNaN(value)) return "f64::NAN";
            if (Double.isInfinite(value)) return value > 0 ? "f64::INFINITY" : "f64::NEG_INFINITY";
            return value + "_f64";
        }
        if (number instanceof Long) return number + "_i64";
        return number.toString();
    }

    static String symbol(String owner, MethodNode method) {
        String descriptor = method.desc.substring(1, method.desc.indexOf(')'));
        return "Java_" + mangle(owner) + "_" + mangle(method.name) + "__" + mangle(descriptor);
    }

    private static String mangle(String value) {
        StringBuilder mangled = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/') mangled.append('_');
            else if (character == '_') mangled.append("_1");
            else if (character == ';') mangled.append("_2");
            else if (character == '[') mangled.append("_3");
            else if (Character.isLetterOrDigit(character) && character < 128) mangled.append(character);
            else mangled.append(String.format("_0%04x", (int) character));
        }
        return mangled.toString();
    }
}
