// JNI glue: Kotlin passes the UsbDeviceConnection fd, an MTPZ key file and a folder of music.
#include <jni.h>
#include <android/log.h>
#include <exception>
#include <iostream>
#include <streambuf>
#include <string>
#include <vector>

extern "C" int zune_upload_run_android(int argc, char* argv[], int usb_fd, const char* mtpz_path);

namespace {
struct LineBuf : std::streambuf {
    JNIEnv* env; jobject cb; jmethodID mid; std::string line;
    LineBuf(JNIEnv* e, jobject c) : env(e), cb(c) {
        mid = env->GetMethodID(env->GetObjectClass(cb), "onLine", "(Ljava/lang/String;)V");
    }
    void flushLine() {
        if (line.empty()) return;
        __android_log_print(ANDROID_LOG_INFO, "xune", "%s", line.c_str());
        jstring s = env->NewStringUTF(line.c_str());   // ponytail: assumes valid UTF-8 from the library
        env->CallVoidMethod(cb, mid, s); env->DeleteLocalRef(s);
        line.clear();
    }
    int overflow(int c) override { if (c == '\n') flushLine(); else if (c != EOF) line += (char)c; return c; }
    ~LineBuf() { flushLine(); }
};
}

extern "C" JNIEXPORT jint JNICALL
Java_org_xune_ztransfer_Native_upload(JNIEnv* env, jclass, jint fd, jstring mtpz, jstring dir, jboolean verbose, jobject cb) {
    const char* m = env->GetStringUTFChars(mtpz, nullptr);
    const char* d = env->GetStringUTFChars(dir, nullptr);
    LineBuf buf(env, cb);
    auto *oldOut = std::cout.rdbuf(&buf), *oldErr = std::cerr.rdbuf(&buf);
    std::string path = d, flag = "--verbose", prog = "ztransfer";
    std::vector<char*> argv{prog.data(), path.data()};
    if (verbose) argv.push_back(flag.data());
    int rc;
    // Library errors are C++ exceptions; letting one escape through JNI aborts the whole app.
    try { rc = zune_upload_run_android((int)argv.size(), argv.data(), fd, m); }
    catch (const std::exception& e) { std::cerr << "ERROR: uncaught exception: " << e.what() << std::endl; rc = 2; }
    catch (...) { std::cerr << "ERROR: uncaught non-standard exception" << std::endl; rc = 3; }
    std::cout.flush(); std::cout.rdbuf(oldOut); std::cerr.rdbuf(oldErr);
    env->ReleaseStringUTFChars(mtpz, m); env->ReleaseStringUTFChars(dir, d);
    return rc;
}
