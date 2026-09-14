/*
 *  Copyright (c) 2026 Enaium
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 *  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 *  DEALINGS IN THE SOFTWARE.
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rnnoise.h"

/*
 * RNNoise is trained and gated on the int16 sample range: its own demo
 * (rnnoise/examples/rnnoise_demo.c) feeds `short` samples straight into the
 * float API. The Kotlin API works in -1..1, so frames are scaled by this factor
 * on the way in and back on the way out. Keep it in sync with
 * RNNOISE_SAMPLE_SCALE in rnnoise-kmp/src/commonMain/kotlin/cn/enaium/rnnoise/Rnnoise.kt,
 * which the Kotlin/Native path uses.
 */
static constexpr float kRnnoiseSampleScale = 32768.0f;

// ============================================================================
// Model handle
//
// rnnoise_model_from_buffer() keeps a pointer to the caller's buffer, so a
// model loaded from a JVM byte[] is backed by a native heap copy. The handle
// owns both the RNNModel and the buffer copy; rnnoise_model_free() releases
// the model (and any file/blob storage it owns), and the handle releases the
// buffer copy.
// ============================================================================

typedef struct {
    RNNModel* model;
    void* buffer;
} rnnoise_model_handle_t;

/*
 * Mirror of the RNNModel struct defined in rnnoise's denoise.c:
 *
 *     struct RNNModel {
 *       const void *const_blob;
 *       void *blob;
 *       int blob_len;
 *       FILE *file;
 *     };
 *
 * rnnoise_model_from_buffer() does not initialize `file`, and
 * rnnoise_model_free() calls fclose() on it, so it must be cleared before the
 * model can be freed (upstream bug; the JNI bridge writes through this mirror
 * because RNNModel itself is opaque).
 */
typedef struct {
    const void* const_blob;
    void* blob;
    int blob_len;
    FILE* file;
} rnnoise_model_layout_t;

static rnnoise_model_handle_t* rnnoise_model_handle_wrap(RNNModel* model, void* buffer) {
    rnnoise_model_handle_t* handle = (rnnoise_model_handle_t*)malloc(sizeof(rnnoise_model_handle_t));
    if (handle == NULL) {
        return NULL;
    }
    handle->model = model;
    handle->buffer = buffer;
    return handle;
}

static RNNModel* rnnoise_model_handle_get(jlong handlePtr) {
    if (handlePtr == 0) {
        return NULL;
    }
    return reinterpret_cast<rnnoise_model_handle_t*>(handlePtr)->model;
}

// ============================================================================
// DenoiseState
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_rnnoise_Jni_create(JNIEnv* env, jclass clazz, jlong modelPtr) {
    DenoiseState* state = rnnoise_create(rnnoise_model_handle_get(modelPtr));
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_rnnoise_Jni_destroy(JNIEnv* env, jclass clazz, jlong ptr) {
    DenoiseState* state = reinterpret_cast<DenoiseState*>(ptr);
    rnnoise_destroy(state);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_rnnoise_Jni_getFrameSize(JNIEnv* env, jclass clazz) {
    return rnnoise_get_frame_size();
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_rnnoise_Jni_getSize(JNIEnv* env, jclass clazz) {
    return rnnoise_get_size();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_cn_enaium_rnnoise_Jni_processFrame(JNIEnv* env, jclass clazz, jlong ptr, jfloatArray input, jfloatArray output) {
    DenoiseState* state = reinterpret_cast<DenoiseState*>(ptr);

    jsize inLen = env->GetArrayLength(input);
    jsize outLen = env->GetArrayLength(output);
    if (inLen != outLen) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "input and output frame lengths must match");
        return 0.0f;
    }

    jfloat* inData = env->GetFloatArrayElements(input, nullptr);
    jfloat* outData = env->GetFloatArrayElements(output, nullptr);

    // RNNoise is trained and gated on the int16 sample range - its own demo
    // feeds `short` samples straight into this float API - while this bindings
    // API works in -1..1. Scale the frame up on the way in and back on the way
    // out, so the documented range denoises at any level instead of only near
    // full scale (below ~-2 dBFS RNNoise's silence gate skips filtering).
    const int frameSize = rnnoise_get_frame_size();
    const jsize inCount = inLen < frameSize ? inLen : frameSize;
    for (jsize i = 0; i < inCount; i++) {
        inData[i] *= kRnnoiseSampleScale;
    }

    float vadProb = rnnoise_process_frame(state, outData, inData);

    const jsize outCount = outLen < frameSize ? outLen : frameSize;
    for (jsize i = 0; i < outCount; i++) {
        outData[i] /= kRnnoiseSampleScale;
    }
    env->ReleaseFloatArrayElements(input, inData, JNI_ABORT);
    env->ReleaseFloatArrayElements(output, outData, 0);
    return vadProb;
}

// ============================================================================
// RNNModel
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_rnnoise_Jni_modelFromFilename(JNIEnv* env, jclass clazz, jstring filename) {
    const char* chars = env->GetStringUTFChars(filename, nullptr);
    RNNModel* model = rnnoise_model_from_filename(chars);
    env->ReleaseStringUTFChars(filename, chars);
    if (model == NULL) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "Failed to load rnnoise model from file");
        return 0;
    }
    return reinterpret_cast<jlong>(rnnoise_model_handle_wrap(model, NULL));
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_rnnoise_Jni_modelFromBuffer(JNIEnv* env, jclass clazz, jbyteArray buffer) {
    jsize len = env->GetArrayLength(buffer);
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);

    void* copy = malloc(len);
    if (copy != NULL) {
        memcpy(copy, bytes, len);
    }
    env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);

    if (copy == NULL) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Failed to copy model buffer");
        return 0;
    }

    RNNModel* model = rnnoise_model_from_buffer(copy, len);
    if (model == NULL) {
        free(copy);
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "Failed to load rnnoise model from buffer");
        return 0;
    }
    // Work around the upstream bug: rnnoise_model_from_buffer() leaves
    // `file` uninitialized and rnnoise_model_free() would fclose() it.
    reinterpret_cast<rnnoise_model_layout_t*>(model)->file = NULL;
    return reinterpret_cast<jlong>(rnnoise_model_handle_wrap(model, copy));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_rnnoise_Jni_modelFree(JNIEnv* env, jclass clazz, jlong modelPtr) {
    rnnoise_model_handle_t* handle = reinterpret_cast<rnnoise_model_handle_t*>(modelPtr);
    if (handle == NULL) {
        return;
    }
    rnnoise_model_free(handle->model);
    free(handle->buffer);
    free(handle);
}
