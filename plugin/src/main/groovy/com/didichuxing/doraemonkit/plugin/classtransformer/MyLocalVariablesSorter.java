package com.didichuxing.doraemonkit.plugin.classtransformer;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

public class MyLocalVariablesSorter extends LocalVariablesSorter {

    private  boolean isconstructor;
    private  boolean issuperInjected=false;

    MyLocalVariablesSorter(int access, String descriptor, MethodVisitor methodVisitor) {
        super(access, descriptor, methodVisitor);
    }

    public MyLocalVariablesSorter(int api, int access, String descriptor, MethodVisitor methodVisitor,boolean isconstructor) {
        super(api, access, descriptor, methodVisitor);
        this.isconstructor=isconstructor;
    }



    private int time=-1;

    @Override
    public void visitCode() {
        super.visitCode();
        if(!isconstructor) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            time = newLocal(Type.LONG_TYPE); // 新建一个局部变量
            mv.visitVarInsn(Opcodes.LSTORE, time);
        }
    }


    @Override
    public void visitInsn(int opcode) {
        if(isconstructor) {
            if(opcode==Opcodes.ACC_SUPER&&!issuperInjected){
                super.visitInsn(opcode);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                time = newLocal(Type.LONG_TYPE); // 新建一个局部变量
                mv.visitVarInsn(Opcodes.LSTORE, time);
                issuperInjected=true;
                return;
            }

        }
//        if(opcode==Opcodes.ACC_SUPER){
//           // super.visitInsn(opcode);
//
//            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
//            time = newLocal(Type.LONG_TYPE); // 新建一个局部变量
//            mv.visitVarInsn(Opcodes.LSTORE, time);
//
//           //
//
//            return;
//        }

        if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)|| Opcodes.ATHROW == opcode) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            mv.visitVarInsn(Opcodes.LLOAD, time);
            mv.visitInsn(Opcodes.LSUB);

            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/babytree/baf/hook/AopUtil", "log", "(J)V", false);
            // mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "timer", "J");
            //mv.visitInsn(Opcodes.LADD);
            // mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "timer", "J");
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack + 4, maxLocals);
    }

}
