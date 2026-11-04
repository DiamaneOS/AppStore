// Host stand-in for libnativehelper's AFileDescriptor_getFd.
#include <jni.h>

int AFileDescriptor_getFd(JNIEnv *env, jobject fd) {
    jclass c = (*env)->FindClass(env, "java/io/FileDescriptor");
    jfieldID f = (*env)->GetFieldID(env, c, "fd", "I");
    return (*env)->GetIntField(env, fd, f);
}
