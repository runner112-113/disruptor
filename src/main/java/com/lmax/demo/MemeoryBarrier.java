package com.lmax.demo;

import jdk.internal.misc.Unsafe;

import java.lang.reflect.Field;


/**
 * @author Runner
 * @version 1.0
 * @date 2024/6/6 15:49
 * @description
 */
public class MemeoryBarrier {
    static boolean flag;
    static int data = 0;

    static Unsafe unsafe;

    static {
        Field field = null;
        try {
            field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public static void main(String[] args) {


        new Thread(() -> {
            data = 1;
            flag = true;

        }).start();


        new Thread(() -> {
            while (flag) {
                unsafe.loadFence();
                System.out.println("==========");
            }

            System.out.println(data);
        }).start();
    }
}
