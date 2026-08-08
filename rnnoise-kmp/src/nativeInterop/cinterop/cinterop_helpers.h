// Copyright (c) 2026 Enaium
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

/*
 * Helper header for Kotlin/Native cinterop.
 * Provides struct definitions for opaque types that are only forward-declared
 * in rnnoise.h. These definitions are used only by cinterop to generate proper
 * Kotlin types; the actual struct layout is defined in the rnnoise library
 * implementation.
 */
#ifndef CINTEROP_HELPERS_H_
#define CINTEROP_HELPERS_H_

#include "rnnoise.h"

/* Dummy struct definitions for cinterop type generation */
struct DenoiseState { void* impl; };

/*
 * RNNModel mirrors the layout of the struct defined in rnnoise's denoise.c:
 *
 *     struct RNNModel {
 *       const void *const_blob;
 *       void *blob;
 *       int blob_len;
 *       FILE *file;
 *     };
 *
 * FILE is represented as an opaque void* here so the commonized cinterop
 * klib does not depend on the platform's FILE type. The `file` member must
 * be cleared after rnnoise_model_from_buffer() because upstream does not
 * initialize it and rnnoise_model_free() would call fclose() on garbage.
 */
struct RNNModel {
    const void* const_blob;
    void* blob;
    int blob_len;
    void* file;
};

#endif /* CINTEROP_HELPERS_H_ */
