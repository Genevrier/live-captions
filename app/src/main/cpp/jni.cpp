#include "translation.hpp"
#include <jni.h>
#include <exception>
#include <cstdlib>

static std::string utf8(JNIEnv * env, jbyteArray bytes) {
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
static void fail(JNIEnv * env, const std::exception & e) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what()); }
extern "C" JNIEXPORT jlong JNICALL Java_com_asr_live_i18n_NativeTranslator_load(JNIEnv * env, jobject, jbyteArray path, jint threads, jint batch, jint ubatch, jboolean opus, jboolean preferOpenCL, jbyteArray cacheDir) {
    try { return reinterpret_cast<jlong>((opus ? load_opus(utf8(env, path), threads) :
        load_hymt(utf8(env, path), threads, batch, ubatch, preferOpenCL, cacheDir ? utf8(env, cacheDir) : "")).release()); }
    catch (const std::exception & e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_asr_live_i18n_NativeTranslator_run(JNIEnv * env, jobject, jlong handle, jbyteArray text) {
    try {
        auto result = reinterpret_cast<TranslationEngine *>(handle)->translate(utf8(env, text));
        auto bytes = env->NewByteArray(result.size());
        if (bytes) env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return bytes;
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_asr_live_i18n_NativeTranslator_abort(JNIEnv *, jobject, jlong handle) { reinterpret_cast<TranslationEngine *>(handle)->cancel(); }
extern "C" JNIEXPORT void JNICALL Java_com_asr_live_i18n_NativeTranslator_free(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<TranslationEngine *>(handle); }
extern "C" JNIEXPORT jstring JNICALL Java_com_asr_live_i18n_NativeTranslator_backend(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<TranslationEngine *>(handle)->backend().c_str());
}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_asr_live_i18n_NativeTranslator_stats(JNIEnv * env, jobject, jlong handle) {
    const auto stats = reinterpret_cast<TranslationEngine *>(handle)->stats();
    const jlong values[] = {stats.prefill_ms, stats.decode_ms};
    jlongArray result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_asr_live_asr_QnnService_configureDspPath(JNIEnv * env, jobject, jbyteArray path) {
    setenv("ADSP_LIBRARY_PATH", utf8(env, path).c_str(), 1);
}
