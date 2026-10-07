package com.araykal.j2rust;

import org.objectweb.asm.Opcodes;

final class RustArithmeticEmitter {
    private RustArithmeticEmitter() {
    }

    static void emitConversion(StringBuilder code, int opcode) {
        String source = opcode <= Opcodes.I2D || opcode >= Opcodes.I2B ? "i" :
                opcode <= Opcodes.L2D ? "j" : opcode <= Opcodes.F2D ? "f" : "d";
        String destination = opcode == Opcodes.I2L || opcode == Opcodes.F2L || opcode == Opcodes.D2L ? "j" :
                opcode == Opcodes.I2F || opcode == Opcodes.L2F || opcode == Opcodes.D2F ? "f" :
                opcode == Opcodes.I2D || opcode == Opcodes.L2D || opcode == Opcodes.F2D ? "d" : "i";
        String cast = destination.equals("j") ? "i64" : destination.equals("f") ? "f32" :
                destination.equals("d") ? "f64" : "i32";
        if (opcode == Opcodes.I2B) cast = "i8";
        if (opcode == Opcodes.I2C) cast = "u16";
        if (opcode == Opcodes.I2S) cast = "i16";
        code.append("let value = stack.pop().unwrap().").append(source).append("(); stack.push(Value::")
                .append(destination.toUpperCase()).append("(value as ").append(cast);
        if (opcode >= Opcodes.I2B) code.append(" as i32");
        code.append("));");
    }

    static void emitComparison(StringBuilder code, int opcode) {
        String type = opcode == Opcodes.LCMP ? "j" : opcode <= Opcodes.FCMPG ? "f" : "d";
        code.append("let right = stack.pop().unwrap().").append(type).append("(); let left = stack.pop().unwrap().")
                .append(type).append("(); stack.push(Value::I(if left > right { 1 } else if left == right { 0 } ")
                .append("else if left < right { -1 } else { ")
                .append(opcode == Opcodes.FCMPG || opcode == Opcodes.DCMPG ? "1" : "-1").append(" }));");
    }

    static void emitArithmetic(StringBuilder code, int opcode, String exceptionPath) {
        int offset;
        String type;
        if (opcode >= Opcodes.ISHL) {
            offset = (opcode - Opcodes.ISHL) % 2;
            type = offset == 0 ? "i" : "j";
        } else {
            offset = (opcode - Opcodes.IADD) % 4;
            type = new String[]{"i", "j", "f", "d"}[offset];
        }
        String tag = type.toUpperCase();
        if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG) {
            code.append("let value = stack.pop().unwrap().").append(type).append("(); stack.push(Value::")
                    .append(tag).append("(").append(type.equals("i") || type.equals("j") ?
                            "value.wrapping_neg()" : "-value").append("));");
            return;
        }
        if (opcode == Opcodes.ISHL || opcode == Opcodes.LSHL ||
                opcode == Opcodes.ISHR || opcode == Opcodes.LSHR ||
                opcode == Opcodes.IUSHR || opcode == Opcodes.LUSHR) {
            code.append("let shift = stack.pop().unwrap().i(); let left = stack.pop().unwrap().")
                    .append(type).append("(); stack.push(Value::").append(tag).append("(");
            int mask = type.equals("j") ? 63 : 31;
            if (opcode == Opcodes.IUSHR || opcode == Opcodes.LUSHR)
                code.append("((left as ").append(type.equals("j") ? "u64" : "u32")
                        .append(") >> (shift & ").append(mask).append(")) as ").append(type.equals("j") ? "i64" : "i32");
            else code.append("left.").append(opcode == Opcodes.ISHL || opcode == Opcodes.LSHL ?
                    "wrapping_shl" : "wrapping_shr").append("((shift & ").append(mask).append(") as u32)");
            code.append("));");
            return;
        }
        code.append("let right = stack.pop().unwrap().").append(type).append("(); let left = stack.pop().unwrap().")
                .append(type).append("(); ");
        if (opcode == Opcodes.IDIV || opcode == Opcodes.LDIV || opcode == Opcodes.IREM || opcode == Opcodes.LREM)
            code.append("if right == 0 { unsafe { throw_arithmetic(_env); } ")
                    .append(exceptionPath).append(" } ");
        code.append("stack.push(Value::").append(tag).append("(");
        int group = opcode < Opcodes.ISHL ? (opcode - Opcodes.IADD) / 4 : -1;
        String operator = group == 0 ? "add" : group == 1 ? "sub" : group == 2 ? "mul" :
                group == 3 ? "div" : group == 4 ? "rem" : "";
        if (opcode >= Opcodes.IAND && opcode <= Opcodes.LXOR) {
            String infix = opcode <= Opcodes.LAND ? "&" : opcode <= Opcodes.LOR ? "|" : "^";
            code.append("left ").append(infix).append(" right");
        } else if (type.equals("i") || type.equals("j")) {
            code.append("left.wrapping_").append(operator).append("(right)");
        } else {
            String infix = group == 0 ? "+" : group == 1 ? "-" : group == 2 ? "*" :
                    group == 3 ? "/" : "%";
            code.append("left ").append(infix).append(" right");
        }
        code.append("));");
    }
}
