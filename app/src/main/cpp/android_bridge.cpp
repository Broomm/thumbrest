#include "linux_pad.h"

#include <jni.h>

#include <exception>
#include <memory>
#include <stdexcept>
#include <string>

namespace {

using namespace thumbrest;

// JNI is called serially by the root service's handler thread.
std::unique_ptr<Session> session;

void reportError(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_thumbrest_NativePad_scan(JNIEnv* env, jobject) {
    try {
        const auto controllers = scanControllers();
        const auto stringClass = env->FindClass("java/lang/String");
        auto result = env->NewObjectArray(static_cast<jsize>(controllers.size()), stringClass, nullptr);
        if (!result) return nullptr;
        for (size_t index = 0; index < controllers.size(); ++index) {
            const auto& item = controllers[index];
            const auto row = std::to_string(item.node) + "\t" + item.identity + "\t" +
                std::to_string(item.supported) + "\t" + item.stableId + "\t" + item.name;
            const auto value = env->NewStringUTF(row.c_str());
            if (!value) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
            env->DeleteLocalRef(value);
        }
        return result;
    } catch (const std::exception& error) {
        reportError(env, error);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_thumbrest_NativePad_start(JNIEnv* env, jobject, jint node, jstring identity, jint rate) {
    const auto raw = env->GetStringUTFChars(identity, nullptr);
    if (!raw) return;
    const auto release = [&](const char* chars) { env->ReleaseStringUTFChars(identity, chars); };
    const std::unique_ptr<const char, decltype(release)> owned(raw, release);
    try {
        const std::string expected(owned.get());
        if (session) throw std::runtime_error("Stop the existing controller session first.");
        session = std::make_unique<Session>(node, expected, rate);
    } catch (const std::exception& error) {
        reportError(env, error);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_thumbrest_NativePad_stop(JNIEnv* env, jobject) {
    const auto status = session ? session->stop() : Status::Idle;
    session.reset();
    if (status == Status::RestoreFailed) {
        reportError(env, std::runtime_error("Could not restore the physical controller node. Reboot the device to restore its input devices."));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_thumbrest_NativePad_rate(JNIEnv*, jobject, jint rate) {
    if (session) session->setRate(rate);
}

extern "C" JNIEXPORT void JNICALL
Java_app_thumbrest_NativePad_cycle(JNIEnv*, jobject, jint button) {
    if (session && button >= 0 && static_cast<unsigned>(button) < ButtonCount) {
        session->cycle(static_cast<Button>(button));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_thumbrest_NativePad_clear(JNIEnv*, jobject) {
    if (session) session->clear();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_thumbrest_NativePad_snapshot(JNIEnv* env, jobject) {
    const auto state = session ? session->snapshot() : Snapshot{};
    const jint values[]{static_cast<jint>(state.status),
        static_cast<jint>(state.physical), static_cast<jint>(state.output),
        static_cast<jint>(state.held), static_cast<jint>(state.turbo),
        state.sticks[0], state.sticks[1], state.sticks[2], state.sticks[3]};
    auto array = env->NewIntArray(sizeof(values) / sizeof(*values));
    if (array) env->SetIntArrayRegion(array, 0, sizeof(values) / sizeof(*values), values);
    return array;
}
