// SPDX-License-Identifier: Apache-2.0

// QuickJS JNI bridge (WO52 8.1): libjime_quickjs.so.
//
// Mirrors the Java bridge surface of the counterpart QuickJsEngine (decompiled,
// jime_quickjs): eval / runJobs / callOutput / outputValue /
// outputState / isFunction / setDeadline / setGlobalString / settle /
// cancel / destroy / liveCount. The implementation is written from the
// public QuickJS C API only — no counterpart native code was available or used.
//
// Threading: one QuickJsEngine instance is single-threaded — JS calls
// must never run concurrently from two threads (the runner thread drives
// it). Only cancel() is thread-safe (it flips an atomic flag; the
// interrupt handler trips on the next bytecode check).
// Exception: the executing thread may change between calls (e.g. a test
// that creates on one thread and evals on another). QuickJS anchors its
// stack limit to the creating thread's stack top, so each JS entry point
// re-anchors via JS_UpdateStackTop (enterJs) — without it, any JS on a
// different thread fails the stack check at once (and even the exception's
// toString fails the same check, so the message can only be
// "unknown error").
// Host callbacks ($log/$http/...) run on the engine thread, inside the
// JNI call that is executing JS; $http completions arriving on OkHttp
// threads never touch JS — Java queues them and delivers them via
// settle(), which only appends to a mutex-guarded C queue; the queue is
// drained by runJobs() on the engine thread (calling the script's
// __jimeHttpResult global, if defined).
//
// $http delivery contract (Jime-side, documented — the counterpart's JS-side
// delivery path was not recoverable from static evidence):
//   - $http(json) returns an incrementing id (from 1).
//   - On completion the host posts Completion(id, rejected, payload);
//     the runner calls settle(id, !rejected, payload).
//   - runJobs() then calls the script global __jimeHttpResult(id,
//     rejected, payload) if the script defined it as a function.
//   - Scripts that do not define it still observe results through the
//     Java host listener; nothing is silently dropped.

#include <jni.h>

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

#define JIME_QJS_UNUSED(x) (void)(x)

// Arg types passed to Java onHostCall (mirrors the counterpart hw3 enum order).
#define ARG_UNDEFINED 0
#define ARG_NULL 1
#define ARG_STRING 2
#define ARG_PRIMITIVE 3
#define ARG_OBJECT 4

#define OUT_PENDING 0
#define OUT_FULFILLED 1
#define OUT_REJECTED 2

#define INTERRUPT_NONE 0
#define INTERRUPT_TIMEOUT 1
#define INTERRUPT_CANCELLED 2

typedef struct HttpPending {
    int64_t id;
    int rejected;
    char *payload;
    struct HttpPending *next;
} HttpPending;

typedef struct Engine {
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM *vm;
    JNIEnv *env; // valid only while inside a JNI entry on the engine thread
    jobject hostGlobal; // global ref to com.jobeen.ime.script.QuickJsEngine
    jmethodID onHostCallId; // cached on first host call
    int64_t deadlineNs; // monotonic ns, 0 = no deadline
    _Atomic int cancelled;
    _Atomic int interruptedKind;
    int outputState;
    char **outputItems;
    size_t outputCount;
    char *outputError; // rejection reason, when outputState == REJECTED
    JSValue outputPromise; // dup'd thenable, JS_UNDEFINED when none
    pthread_mutex_t httpLock;
    HttpPending *httpHead;
    HttpPending *httpTail;
} Engine;

static _Atomic int g_liveCount = 0;

static int64_t nowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int interruptHandler(JSRuntime *rt, void *opaque) {
    JIME_QJS_UNUSED(rt);
    Engine *e = (Engine *)opaque;
    if (!e) return 0;
    if (atomic_load(&e->cancelled)) {
        atomic_store(&e->interruptedKind, INTERRUPT_CANCELLED);
        return 1;
    }
    if (e->deadlineNs != 0 && nowNs() >= e->deadlineNs) {
        atomic_store(&e->interruptedKind, INTERRUPT_TIMEOUT);
        return 1;
    }
    return 0;
}

// --- Java interop helpers -------------------------------------------------

static jobjectArray makeErrorArray(JNIEnv *env, const char *message, const char *kind) {
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;
    jobjectArray arr = (*env)->NewObjectArray(env, 2, stringClass, NULL);
    if (!arr) return NULL;
    jstring msg = (*env)->NewStringUTF(env, message ? message : "");
    jstring k = (*env)->NewStringUTF(env, kind ? kind : "error");
    if (msg) (*env)->SetObjectArrayElement(env, arr, 0, msg);
    if (k) (*env)->SetObjectArrayElement(env, arr, 1, k);
    return arr;
}

// Exception text of the pending JS exception (or "unknown error").
// Consumes the exception.
static char *takeExceptionText(Engine *e) {
    JSContext *ctx = e->ctx;
    JSValue ex = JS_GetException(ctx);
    char *out = NULL;
    const char *cstr = JS_ToCString(ctx, ex);
    if (cstr) {
        out = strdup(cstr);
        JS_FreeCString(ctx, cstr);
    }
    JS_FreeValue(ctx, ex);
    if (!out) out = strdup("unknown error");
    return out;
}

// JS value -> freshly allocated UTF-8 string (ToString semantics).
static char *valueToUtf8(Engine *e, JSValueConst val) {
    const char *cstr = JS_ToCString(e->ctx, val);
    if (!cstr) return strdup("");
    char *out = strdup(cstr);
    JS_FreeCString(e->ctx, cstr);
    return out ? out : strdup("");
}

static void freeOutputItems(Engine *e) {
    if (e->outputItems) {
        for (size_t i = 0; i < e->outputCount; i++) free(e->outputItems[i]);
        free(e->outputItems);
        e->outputItems = NULL;
    }
    e->outputCount = 0;
    free(e->outputError);
    e->outputError = NULL;
}

// Convert a resolved output value to a string list:
//   array  -> one item per element (ToString each)
//   string -> [that string]
//   other  -> [ToString(value)]
static void setOutputFromValue(Engine *e, JSValueConst val) {
    freeOutputItems(e);
    e->outputState = OUT_FULFILLED;
    JSContext *ctx = e->ctx;
    int isArray = JS_IsArray(ctx, val);
    if (isArray == 1) {
        JSValue lenVal = JS_GetPropertyStr(ctx, val, "length");
        int64_t len = 0;
        if (!JS_IsException(lenVal)) JS_ToInt64(ctx, &len, lenVal);
        JS_FreeValue(ctx, lenVal);
        if (len < 0) len = 0;
        if (len > 100000) len = 100000; // sanity cap; output lists are small
        e->outputItems = (char **)calloc((size_t)len ? (size_t)len : 1, sizeof(char *));
        e->outputCount = (size_t)len;
        for (int64_t i = 0; i < len; i++) {
            JSValue item = JS_GetPropertyUint32(ctx, val, (uint32_t)i);
            e->outputItems[i] = JS_IsException(item) ? strdup("") : valueToUtf8(e, item);
            JS_FreeValue(ctx, item);
        }
    } else {
        e->outputItems = (char **)calloc(1, sizeof(char *));
        e->outputCount = 1;
        e->outputItems[0] = valueToUtf8(e, val);
    }
}

static JSValue onFulfilled(JSContext *ctx, JSValueConst thisVal, int argc, JSValueConst *argv) {
    JIME_QJS_UNUSED(thisVal);
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e && argc >= 1) setOutputFromValue(e, argv[0]);
    return JS_UNDEFINED;
}

static JSValue onRejected(JSContext *ctx, JSValueConst thisVal, int argc, JSValueConst *argv) {
    JIME_QJS_UNUSED(thisVal);
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e) {
        freeOutputItems(e);
        e->outputState = OUT_REJECTED;
        e->outputError = argc >= 1 ? valueToUtf8(e, argv[0]) : strdup("rejected");
    }
    return JS_UNDEFINED;
}

// --- host calls (JS -> Java) ----------------------------------------------

// Call Java QuickJsEngine.onHostCall(name, types, strArgs, objJson).
// Returns a JS value converted from the Java return (null -> undefined).
static JSValue callJavaHost(Engine *e, const char *name, int argc, JSValueConst *argv) {
    JSContext *ctx = e->ctx;
    JNIEnv *env = e->env;
    if (!env || !e->hostGlobal) return JS_UNDEFINED;
    if (!e->onHostCallId) {
        jclass cls = (*env)->GetObjectClass(env, e->hostGlobal);
        if (!cls) return JS_UNDEFINED;
        e->onHostCallId = (*env)->GetMethodID(
            env, cls, "onHostCall",
            "(Ljava/lang/String;[I[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Object;");
        if (!e->onHostCallId) return JS_UNDEFINED;
    }
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return JS_UNDEFINED;
    jintArray types = (*env)->NewIntArray(env, argc);
    jobjectArray strArgs = (*env)->NewObjectArray(env, argc, stringClass, NULL);
    jobjectArray objJson = (*env)->NewObjectArray(env, argc, stringClass, NULL);
    if (!types || !strArgs || !objJson) return JS_UNDEFINED;
    jint *typeBuf = argc > 0 ? (jint *)malloc(sizeof(jint) * (size_t)argc) : NULL;
    for (int i = 0; i < argc; i++) {
        JSValueConst v = argv[i];
        int type;
        char *str = NULL;
        char *json = NULL;
        if (JS_IsUndefined(v)) {
            type = ARG_UNDEFINED;
            str = strdup("");
        } else if (JS_IsNull(v)) {
            type = ARG_NULL;
            str = strdup("");
        } else if (JS_IsString(v)) {
            type = ARG_STRING;
            str = valueToUtf8(e, v);
        } else if (JS_IsNumber(v) || JS_IsBool(v)) {
            type = ARG_PRIMITIVE;
            str = valueToUtf8(e, v);
        } else {
            type = ARG_OBJECT;
            JSValue j = JS_JSONStringify(ctx, v, JS_UNDEFINED, JS_UNDEFINED);
            if (JS_IsException(j)) {
                JS_FreeValue(ctx, j);
                json = strdup("");
            } else {
                json = valueToUtf8(e, j);
                JS_FreeValue(ctx, j);
            }
            str = json ? strdup(json) : strdup("");
        }
        if (typeBuf) typeBuf[i] = type;
        jstring js = (*env)->NewStringUTF(env, str ? str : "");
        if (js) (*env)->SetObjectArrayElement(env, strArgs, i, js);
        jstring jj = (*env)->NewStringUTF(env, json ? json : "");
        if (jj) (*env)->SetObjectArrayElement(env, objJson, i, jj);
        free(str);
        free(json);
    }
    if (typeBuf) {
        (*env)->SetIntArrayRegion(env, types, 0, argc, typeBuf);
        free(typeBuf);
    }
    jstring jname = (*env)->NewStringUTF(env, name);
    jobject ret = (*env)->CallObjectMethod(
        env, e->hostGlobal, e->onHostCallId, jname, types, strArgs, objJson);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return JS_UNDEFINED;
    }
    if (!ret) return JS_UNDEFINED;
    jclass retCls = (*env)->GetObjectClass(env, ret);
    jclass stringCls = (*env)->FindClass(env, "java/lang/String");
    jclass booleanCls = (*env)->FindClass(env, "java/lang/Boolean");
    jclass numberCls = (*env)->FindClass(env, "java/lang/Number");
    JSValue out = JS_UNDEFINED;
    if (stringCls && (*env)->IsInstanceOf(env, ret, stringCls)) {
        const char *cstr = (*env)->GetStringUTFChars(env, (jstring)ret, NULL);
        out = cstr ? JS_NewString(ctx, cstr) : JS_UNDEFINED;
        if (cstr) (*env)->ReleaseStringUTFChars(env, (jstring)ret, cstr);
    } else if (booleanCls && (*env)->IsInstanceOf(env, ret, booleanCls)) {
        jmethodID boolVal = (*env)->GetMethodID(env, booleanCls, "booleanValue", "()Z");
        jboolean b = boolVal ? (*env)->CallBooleanMethod(env, ret, boolVal) : JNI_FALSE;
        out = JS_NewBool(ctx, b ? 1 : 0);
    } else if (numberCls && (*env)->IsInstanceOf(env, ret, numberCls)) {
        jmethodID longVal = (*env)->GetMethodID(env, numberCls, "longValue", "()J");
        jmethodID doubleVal = (*env)->GetMethodID(env, numberCls, "doubleValue", "()D");
        // Long ids are exact; doubles carry timeouts. Prefer long when the
        // value is integral and fits, else double.
        if (longVal && doubleVal) {
            jlong l = (*env)->CallLongMethod(env, ret, longVal);
            jdouble d = (*env)->CallDoubleMethod(env, ret, doubleVal);
            out = (jdouble)l == d ? JS_NewInt64(ctx, (int64_t)l)
                                  : JS_NewFloat64(ctx, (double)d);
        }
    }
    JIME_QJS_UNUSED(retCls);
    return out;
}

#define DEFINE_HOST_FN(fnName, jsName) \
    static JSValue fnName(JSContext *ctx, JSValueConst thisVal, int argc, JSValueConst *argv) { \
        JIME_QJS_UNUSED(thisVal); \
        Engine *e = (Engine *)JS_GetContextOpaque(ctx); \
        if (!e) return JS_UNDEFINED; \
        return callJavaHost(e, jsName, argc, argv); \
    }

DEFINE_HOST_FN(hostLog, "$log")
DEFINE_HOST_FN(hostHttp, "$http")
DEFINE_HOST_FN(hostSetClipboard, "$setClipboard")
DEFINE_HOST_FN(hostInsertText, "$insertText")
DEFINE_HOST_FN(hostOpenURL, "$openURL")
DEFINE_HOST_FN(hostUserNotification, "$userNotification")

static void installHostFunctions(Engine *e) {
    JSContext *ctx = e->ctx;
    JSValue global = JS_GetGlobalObject(ctx);
    struct {
        const char *name;
        JSCFunction *fn;
    } fns[] = {
        {"$log", hostLog},
        {"$http", hostHttp},
        {"$setClipboard", hostSetClipboard},
        {"$insertText", hostInsertText},
        {"$openURL", hostOpenURL},
        {"$userNotification", hostUserNotification},
    };
    for (size_t i = 0; i < sizeof(fns) / sizeof(fns[0]); i++) {
        JSValue fn = JS_NewCFunction(ctx, fns[i].fn, fns[i].name, 0);
        JS_SetPropertyStr(ctx, global, fns[i].name, fn);
    }
    JS_FreeValue(ctx, global);
}

// --- http completion queue --------------------------------------------------

static void enqueueHttp(Engine *e, int64_t id, int rejected, const char *payload) {
    HttpPending *node = (HttpPending *)malloc(sizeof(HttpPending));
    if (!node) return;
    node->id = id;
    node->rejected = rejected;
    node->payload = strdup(payload ? payload : "");
    node->next = NULL;
    pthread_mutex_lock(&e->httpLock);
    if (e->httpTail) e->httpTail->next = node;
    else e->httpHead = node;
    e->httpTail = node;
    pthread_mutex_unlock(&e->httpLock);
}

// Drain the queue by calling the script's __jimeHttpResult global, if it
// is a function. Returns NULL on success, else an error array (JS
// exception text; caller frees via takeExceptionText path — here we build
// the array directly from the message).
static jobjectArray drainHttpQueue(JNIEnv *env, Engine *e) {
    for (;;) {
        pthread_mutex_lock(&e->httpLock);
        HttpPending *node = e->httpHead;
        if (node) {
            e->httpHead = node->next;
            if (!e->httpHead) e->httpTail = NULL;
        }
        pthread_mutex_unlock(&e->httpLock);
        if (!node) return NULL;
        JSContext *ctx = e->ctx;
        JSValue global = JS_GetGlobalObject(ctx);
        JSValue cb = JS_GetPropertyStr(ctx, global, "__jimeHttpResult");
        if (!JS_IsException(cb) && JS_IsFunction(ctx, cb)) {
            JSValue args[3];
            args[0] = JS_NewInt64(ctx, node->id);
            args[1] = JS_NewBool(ctx, node->rejected ? 1 : 0);
            args[2] = JS_NewString(ctx, node->payload ? node->payload : "");
            JSValue r = JS_Call(ctx, cb, global, 3, args);
            for (int i = 0; i < 3; i++) JS_FreeValue(ctx, args[i]);
            if (JS_IsException(r)) {
                char *msg = takeExceptionText(e);
                jobjectArray err = makeErrorArray(env, msg, "error");
                free(msg);
                JS_FreeValue(ctx, r);
                JS_FreeValue(ctx, cb);
                JS_FreeValue(ctx, global);
                free(node->payload);
                free(node);
                return err;
            }
            JS_FreeValue(ctx, r);
        }
        JS_FreeValue(ctx, cb);
        JS_FreeValue(ctx, global);
        free(node->payload);
        free(node);
    }
}

static void freeHttpQueue(Engine *e) {
    pthread_mutex_lock(&e->httpLock);
    HttpPending *node = e->httpHead;
    e->httpHead = e->httpTail = NULL;
    pthread_mutex_unlock(&e->httpLock);
    while (node) {
        HttpPending *next = node->next;
        free(node->payload);
        free(node);
        node = next;
    }
}

static void freeEngine(Engine *e) {
    if (!e) return;
    if (!JS_IsUndefined(e->outputPromise)) JS_FreeValue(e->ctx, e->outputPromise);
    freeOutputItems(e);
    freeHttpQueue(e);
    pthread_mutex_destroy(&e->httpLock);
    if (e->ctx) JS_FreeContext(e->ctx);
    if (e->rt) JS_FreeRuntime(e->rt);
    JNIEnv *env = e->env;
    if (env && e->hostGlobal) (*env)->DeleteGlobalRef(env, e->hostGlobal);
    free(e);
}

// --- JNI entry points -------------------------------------------------------
// Class: com.jobeen.ime.script.QuickJsNative (all @JvmStatic).

#define JNI_FN(name) Java_com_jobeen_ime_script_QuickJsNative_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv *env, jclass clazz, jobject host) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = (Engine *)calloc(1, sizeof(Engine));
    if (!e) return 0;
    e->rt = JS_NewRuntime();
    if (!e->rt) {
        free(e);
        return 0;
    }
    e->ctx = JS_NewContext(e->rt);
    if (!e->ctx) {
        JS_FreeRuntime(e->rt);
        free(e);
        return 0;
    }
    (*env)->GetJavaVM(env, &e->vm);
    e->env = env;
    e->hostGlobal = (*env)->NewGlobalRef(env, host);
    e->outputPromise = JS_UNDEFINED;
    e->outputState = OUT_PENDING;
    pthread_mutex_init(&e->httpLock, NULL);
    JS_SetContextOpaque(e->ctx, e);
    JS_SetInterruptHandler(e->rt, interruptHandler, e);
    // 256 KiB JS stack: deep recursion fails with a catchable error
    // instead of crashing the process with a native stack overflow.
    JS_SetMaxStackSize(e->rt, 256 * 1024);
    installHostFunctions(e);
    atomic_fetch_add(&g_liveCount, 1);
    e->env = NULL;
    return (jlong)(intptr_t)e;
}

static Engine *engineFromPtr(jlong ptr) {
    return (Engine *)(intptr_t)ptr;
}

// 执行 JS 前把栈顶锚到当前线程：QuickJS 的栈上限（stack_limit）
// 由创建线程的栈顶推导；引擎换线程执行时不重锚，当前栈指针落在
// limit 之下，任何 JS 执行都立即判栈溢出（且异常转字符串的 toString
// 也一样溢出，msg 只能是 unknown error）。引擎是单线程模型（只有
// cancel() 跨线程），每次入口重锚一次即够，代价一次取栈指针。
static void enterJs(Engine *e) {
    JS_UpdateStackTop(e->rt);
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeEval)(
    JNIEnv *env, jclass clazz, jlong ptr, jstring code, jstring filename) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e || !code || !filename) return makeErrorArray(env, "invalid engine", "error");
    const char *codeChars = (*env)->GetStringUTFChars(env, code, NULL);
    const char *fileChars = (*env)->GetStringUTFChars(env, filename, NULL);
    if (!codeChars || !fileChars) {
        if (codeChars) (*env)->ReleaseStringUTFChars(env, code, codeChars);
        if (fileChars) (*env)->ReleaseStringUTFChars(env, filename, fileChars);
        return makeErrorArray(env, "invalid script", "error");
    }
    e->env = env;
    atomic_store(&e->interruptedKind, INTERRUPT_NONE);
    enterJs(e);
    JSValue v = JS_Eval(e->ctx, codeChars, strlen(codeChars), fileChars, JS_EVAL_TYPE_GLOBAL);
    e->env = NULL;
    (*env)->ReleaseStringUTFChars(env, code, codeChars);
    (*env)->ReleaseStringUTFChars(env, filename, fileChars);
    if (JS_IsException(v)) {
        char *msg = takeExceptionText(e);
        int kind = atomic_load(&e->interruptedKind);
        jobjectArray err = makeErrorArray(
            env, msg, kind == INTERRUPT_TIMEOUT ? "timeout" : kind == INTERRUPT_CANCELLED ? "cancelled" : "error");
        free(msg);
        return err;
    }
    JS_FreeValue(e->ctx, v);
    return NULL;
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeCallOutput)(JNIEnv *env, jclass clazz, jlong ptr) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e) return makeErrorArray(env, "invalid engine", "error");
    e->env = env;
    atomic_store(&e->interruptedKind, INTERRUPT_NONE);
    enterJs(e);
    JSContext *ctx = e->ctx;
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue output = JS_GetPropertyStr(ctx, global, "output");
    if (JS_IsException(output)) {
        char *msg = takeExceptionText(e);
        jobjectArray err = makeErrorArray(env, msg, "error");
        free(msg);
        JS_FreeValue(ctx, global);
        e->env = NULL;
        return err;
    }
    if (!JS_IsFunction(ctx, output)) {
        JS_FreeValue(ctx, output);
        JS_FreeValue(ctx, global);
        e->env = NULL;
        return makeErrorArray(env, "output is not a function", "no-output");
    }
    JSValue result = JS_Call(ctx, output, global, 0, NULL);
    JS_FreeValue(ctx, output);
    JS_FreeValue(ctx, global);
    if (JS_IsException(result)) {
        char *msg = takeExceptionText(e);
        int kind = atomic_load(&e->interruptedKind);
        jobjectArray err = makeErrorArray(
            env, msg, kind == INTERRUPT_TIMEOUT ? "timeout" : kind == INTERRUPT_CANCELLED ? "cancelled" : "error");
        free(msg);
        e->env = NULL;
        return err;
    }
    // Thenable? Attach fulfil/reject handlers; otherwise the value is the
    // result already (async functions always return a promise, so this
    // branch only fires for a plain `function output()`).
    JSValue then = JS_GetPropertyStr(ctx, result, "then");
    jobjectArray err = NULL;
    if (!JS_IsException(then) && JS_IsFunction(ctx, then)) {
        if (!JS_IsUndefined(e->outputPromise)) JS_FreeValue(ctx, e->outputPromise);
        e->outputPromise = result; // keep alive until settled/destroyed
        e->outputState = OUT_PENDING;
        freeOutputItems(e);
        JSValue onF = JS_NewCFunction(ctx, onFulfilled, "onFulfilled", 1);
        JSValue onR = JS_NewCFunction(ctx, onRejected, "onRejected", 1);
        JSValue args[2] = {onF, onR};
        JSValue r = JS_Call(ctx, then, result, 2, args);
        JS_FreeValue(ctx, onF);
        JS_FreeValue(ctx, onR);
        if (JS_IsException(r)) {
            char *msg = takeExceptionText(e);
            err = makeErrorArray(env, msg, "error");
            free(msg);
        }
        JS_FreeValue(ctx, r);
    } else {
        if (JS_IsException(then)) {
            JSValue ex = JS_GetException(ctx);
            JS_FreeValue(ctx, ex);
        }
        setOutputFromValue(e, result);
        JS_FreeValue(ctx, result);
    }
    JS_FreeValue(ctx, then);
    e->env = NULL;
    return err;
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeRunJobs)(JNIEnv *env, jclass clazz, jlong ptr, jint maxJobs) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e) return makeErrorArray(env, "invalid engine", "error");
    e->env = env;
    atomic_store(&e->interruptedKind, INTERRUPT_NONE);
    enterJs(e);
    jobjectArray err = NULL;
    int executed = 0;
    for (;;) {
        err = drainHttpQueue(env, e);
        if (err) break;
        if (e->deadlineNs != 0 && nowNs() >= e->deadlineNs) {
            err = makeErrorArray(env, "deadline exceeded", "timeout");
            break;
        }
        // 分片上限：脚本的 await 链每步都产生下一个微任务，一次泵完
        // 永不返回，调用方（runner）中间插不进 $http 完成的投递；
        // 到上限即返回 NULL（不是错），调用方下次再泵。
        if (executed >= maxJobs) break;
        JSContext *pctx = NULL;
        int r = JS_ExecutePendingJob(e->rt, &pctx);
        if (r < 0) {
            char *msg = takeExceptionText(e);
            int kind = atomic_load(&e->interruptedKind);
            err = makeErrorArray(
                env, msg, kind == INTERRUPT_TIMEOUT ? "timeout" : kind == INTERRUPT_CANCELLED ? "cancelled" : "error");
            free(msg);
            break;
        }
        if (r == 0) {
            // One more drain: the last job may have settled the promise
            // via a job that queued an http callback through settle().
            err = drainHttpQueue(env, e);
            break;
        }
        executed++;
    }
    e->env = NULL;
    return err;
}

JNIEXPORT jint JNICALL JNI_FN(nativeOutputState)(JNIEnv *env, jclass clazz, jlong ptr) {
    JIME_QJS_UNUSED(env);
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    return e ? e->outputState : OUT_PENDING;
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeOutputValue)(JNIEnv *env, jclass clazz, jlong ptr) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e || e->outputState != OUT_FULFILLED) return NULL;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)e->outputCount, stringClass, NULL);
    if (!arr) return NULL;
    for (size_t i = 0; i < e->outputCount; i++) {
        jstring s = (*env)->NewStringUTF(env, e->outputItems[i] ? e->outputItems[i] : "");
        if (s) (*env)->SetObjectArrayElement(env, arr, (jsize)i, s);
    }
    return arr;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeOutputError)(JNIEnv *env, jclass clazz, jlong ptr) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e || e->outputState != OUT_REJECTED || !e->outputError) return NULL;
    return (*env)->NewStringUTF(env, e->outputError);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsFunction)(JNIEnv *env, jclass clazz, jlong ptr, jstring name) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e || !name) return JNI_FALSE;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (!chars) return JNI_FALSE;
    e->env = env;
    enterJs(e);
    JSValue global = JS_GetGlobalObject(e->ctx);
    JSValue v = JS_GetPropertyStr(e->ctx, global, chars);
    jboolean ok = (!JS_IsException(v) && JS_IsFunction(e->ctx, v)) ? JNI_TRUE : JNI_FALSE;
    if (JS_IsException(v)) {
        JSValue ex = JS_GetException(e->ctx);
        JS_FreeValue(e->ctx, ex);
    }
    JS_FreeValue(e->ctx, v);
    JS_FreeValue(e->ctx, global);
    e->env = NULL;
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return ok;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDeadline)(JNIEnv *env, jclass clazz, jlong ptr, jlong nanos) {
    JIME_QJS_UNUSED(env);
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e) return;
    atomic_store(&e->interruptedKind, INTERRUPT_NONE);
    e->deadlineNs = nanos > 0 ? nowNs() + (int64_t)nanos : 0;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetGlobalString)(JNIEnv *env, jclass clazz, jlong ptr, jstring name, jstring value) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e || !name || !value) return;
    const char *nameChars = (*env)->GetStringUTFChars(env, name, NULL);
    const char *valueChars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!nameChars || !valueChars) {
        if (nameChars) (*env)->ReleaseStringUTFChars(env, name, nameChars);
        if (valueChars) (*env)->ReleaseStringUTFChars(env, value, valueChars);
        return;
    }
    e->env = env;
    enterJs(e);
    JSValue global = JS_GetGlobalObject(e->ctx);
    JSValue str = JS_NewString(e->ctx, valueChars);
    JS_SetPropertyStr(e->ctx, global, nameChars, str);
    JS_FreeValue(e->ctx, global);
    e->env = NULL;
    (*env)->ReleaseStringUTFChars(env, name, nameChars);
    (*env)->ReleaseStringUTFChars(env, value, valueChars);
}

JNIEXPORT void JNICALL JNI_FN(nativeSettle)(JNIEnv *env, jclass clazz, jlong ptr, jlong id, jboolean fulfilled, jstring value) {
    JIME_QJS_UNUSED(env);
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e || !value) return;
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!chars) return;
    enqueueHttp(e, (int64_t)id, fulfilled == JNI_TRUE ? 0 : 1, chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
}

JNIEXPORT void JNICALL JNI_FN(nativeCancel)(JNIEnv *env, jclass clazz, jlong ptr) {
    JIME_QJS_UNUSED(env);
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e) return;
    atomic_store(&e->cancelled, 1);
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv *env, jclass clazz, jlong ptr) {
    JIME_QJS_UNUSED(clazz);
    Engine *e = engineFromPtr(ptr);
    if (!e) return;
    atomic_fetch_sub(&g_liveCount, 1);
    e->env = env;
    freeEngine(e);
}

JNIEXPORT jint JNICALL JNI_FN(nativeLiveCount)(JNIEnv *env, jclass clazz) {
    JIME_QJS_UNUSED(env);
    JIME_QJS_UNUSED(clazz);
    return atomic_load(&g_liveCount);
}
