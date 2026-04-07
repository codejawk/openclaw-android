/**
 * node_launcher.cpp
 *
 * JNI bridge that loads libnode.so at runtime via dlopen and calls
 * node::Start(int, char**) in a background pthread.
 *
 * This avoids the need for exec()-able PIE Node.js binaries.
 * libnode.so from nodejs-mobile-react-native is an Android Bionic ET_DYN
 * shared library — correct for dlopen, not for ProcessBuilder exec.
 */

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <android/log.h>

#define TAG "NodeLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Mangled symbol for node::Start(int, char**)
static const char* NODE_START_SYM = "_ZN4node5StartEiPPc";
typedef int (*NodeStartFn)(int, char**);

static volatile bool s_nodeRunning = false;

struct RunArgs {
    std::string              libPath;
    std::string              cwd;
    std::vector<std::string> envVars;   // "KEY=VALUE" strings
    std::vector<std::string> argv;      // argv[0] = "node", then actual args
};

static void* node_thread_fn(void* ptr) {
    RunArgs* ra = static_cast<RunArgs*>(ptr);

    // ── Set environment variables ──────────────────────────────────
    for (const auto& kv : ra->envVars) {
        size_t eq = kv.find('=');
        if (eq != std::string::npos) {
            std::string key = kv.substr(0, eq);
            std::string val = kv.substr(eq + 1);
            setenv(key.c_str(), val.c_str(), 1 /*overwrite*/);
        }
    }

    // ── Change working directory ───────────────────────────────────
    if (!ra->cwd.empty()) {
        if (chdir(ra->cwd.c_str()) != 0) {
            LOGW("chdir('%s') failed — continuing anyway", ra->cwd.c_str());
        }
    }

    // ── dlopen libnode.so ──────────────────────────────────────────
    LOGI("Loading libnode from: %s", ra->libPath.c_str());
    void* handle = dlopen(ra->libPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        s_nodeRunning = false;
        delete ra;
        return nullptr;
    }
    LOGI("libnode.so loaded OK");

    // ── Resolve node::Start ────────────────────────────────────────
    NodeStartFn startFn =
        reinterpret_cast<NodeStartFn>(dlsym(handle, NODE_START_SYM));
    if (!startFn) {
        LOGE("dlsym '%s' failed: %s", NODE_START_SYM, dlerror());
        dlclose(handle);
        s_nodeRunning = false;
        delete ra;
        return nullptr;
    }
    LOGI("node::Start resolved at %p", (void*)startFn);

    // ── Build argv ─────────────────────────────────────────────────
    int argc = static_cast<int>(ra->argv.size());
    std::vector<char*> argv;
    argv.reserve(argc + 1);
    for (auto& s : ra->argv) {
        argv.push_back(const_cast<char*>(s.c_str()));
    }
    argv.push_back(nullptr);

    LOGI("Calling node::Start with %d args:", argc);
    for (int i = 0; i < argc; i++) {
        LOGI("  argv[%d] = %s", i, argv[i]);
    }

    // ── Start Node.js ──────────────────────────────────────────────
    s_nodeRunning = true;
    int result = startFn(argc, argv.data());
    LOGI("node::Start returned %d", result);
    s_nodeRunning = false;

    delete ra;
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openclaw_native_1app_NodeBridge_nativeStartNode(
    JNIEnv* env,
    jobject /* thiz */,
    jstring jLibPath,
    jstring jCwd,
    jobjectArray jEnvVars,
    jobjectArray jArgs)
{
    if (s_nodeRunning) {
        LOGW("Node.js is already running — ignoring duplicate start");
        return;
    }

    const char* libPath = env->GetStringUTFChars(jLibPath, nullptr);
    const char* cwd     = env->GetStringUTFChars(jCwd,     nullptr);

    auto* ra    = new RunArgs();
    ra->libPath = libPath;
    ra->cwd     = cwd;
    env->ReleaseStringUTFChars(jLibPath, libPath);
    env->ReleaseStringUTFChars(jCwd,     cwd);

    // Environment variables
    int nEnv = env->GetArrayLength(jEnvVars);
    for (int i = 0; i < nEnv; i++) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(jEnvVars, i));
        const char* cs = env->GetStringUTFChars(js, nullptr);
        ra->envVars.emplace_back(cs);
        env->ReleaseStringUTFChars(js, cs);
    }

    // argv
    int nArgs = env->GetArrayLength(jArgs);
    for (int i = 0; i < nArgs; i++) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(jArgs, i));
        const char* cs = env->GetStringUTFChars(js, nullptr);
        ra->argv.emplace_back(cs);
        env->ReleaseStringUTFChars(js, cs);
    }

    // Detached thread with 8 MB stack (Node.js is stack-heavy)
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, 8 * 1024 * 1024);
    int rc = pthread_create(&thread, &attr, node_thread_fn, ra);
    pthread_attr_destroy(&attr);

    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        s_nodeRunning = false;
        delete ra;
    } else {
        LOGI("Node.js thread spawned");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openclaw_native_1app_NodeBridge_nativeIsRunning(
    JNIEnv* /* env */,
    jobject /* thiz */)
{
    return s_nodeRunning ? JNI_TRUE : JNI_FALSE;
}
