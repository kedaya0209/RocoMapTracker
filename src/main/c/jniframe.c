#include <jni.h>

// 实际的 push 实现
static jint native_push(JNIEnv *env, jclass clazz, jint capacity) {
    return (*env)->PushLocalFrame(env, capacity);
}

// 实际的 pop 实现
static jint native_pop(JNIEnv *env, jclass clazz) {
    (*env)->PopLocalFrame(env, NULL);
    return 0;
}

// 方法注册表
static JNINativeMethod methods[] = {
    {"push", "(I)I", (void *)native_push},
    {"pop",  "()I",  (void *)native_pop}
};

// 动态注册入口
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // 要注册的 Java 类，可以换成任何你想要的类路径
    jclass clazz = (*env)->FindClass(env, "com/luoke/app/utils/JNIFrameNative");
    if (clazz == NULL) {
        return JNI_ERR;
    }

    if ((*env)->RegisterNatives(env, clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}