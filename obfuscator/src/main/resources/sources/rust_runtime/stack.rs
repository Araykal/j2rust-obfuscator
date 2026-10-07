use super::Value;

pub fn stack_op(stack: &mut Vec<Value>, opcode: i32) {
    let first = stack.pop().unwrap();
    if opcode == 87 { return; }
    if opcode == 88 {
        if first.category() == 1 { stack.pop().unwrap(); }
        return;
    }
    if opcode == 89 { stack.extend([first, first]); return; }
    if opcode == 92 && first.category() == 2 { stack.extend([first, first]); return; }
    let second = stack.pop().unwrap();
    match opcode {
        90 => stack.extend([first, second, first]),
        91 => {
            if second.category() == 2 {
                stack.extend([first, second, first]);
            } else {
                let third = stack.pop().unwrap();
                stack.extend([first, third, second, first]);
            }
        }
        92 => {
            stack.extend([second, first, second, first]);
        }
        93 => {
            if first.category() == 2 {
                stack.extend([first, second, first]);
            } else {
                let third = stack.pop().unwrap();
                stack.extend([second, first, third, second, first]);
            }
        }
        94 => {
            if first.category() == 2 {
                if second.category() == 2 {
                    stack.extend([first, second, first]);
                } else {
                    let third = stack.pop().unwrap();
                    stack.extend([first, third, second, first]);
                }
            } else {
                let third = stack.pop().unwrap();
                if third.category() == 2 {
                    stack.extend([second, first, third, second, first]);
                } else {
                    let fourth = stack.pop().unwrap();
                    stack.extend([second, first, fourth, third, second, first]);
                }
            }
        }
        95 => stack.extend([first, second]),
        _ => unreachable!(),
    }
}
