// lab_capture.c — the three native capture paths the lab exercises.
//
// All of them write raw s16le mono PCM to the file the caller names; the Kotlin
// side wraps it in a WAV header so every path produces a comparable artifact.
//
//   aaudioRead      AAudio blocking read        (function-return style)
//   aaudioCallback  AAudio data callback        (callback-proxy style)
//   openslRecord    OpenSL ES buffer queue      (queue style)
//
// The two callback paths hand audio to a writer thread through a single
// producer / single consumer ring: neither callback may block on file I/O.
// That is the same shape an interception module needs on its side, so this
// file doubles as a reference for where the copy has to happen.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "lab_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define RING_SECONDS 2

// ------------------------------------------------------------------- sink

typedef struct {
    FILE *f;
    int16_t *ring;
    size_t cap; /* samples */
    _Atomic size_t w;
    _Atomic size_t r;
    _Atomic int stop;
    _Atomic long long written;
    sem_t sem;
    pthread_t thread;
} sink_t;

static void sink_push(sink_t *s, const int16_t *src, int n) {
    size_t w = atomic_load_explicit(&s->w, memory_order_relaxed);
    for (int i = 0; i < n; i++) {
        size_t r = atomic_load_explicit(&s->r, memory_order_acquire);
        if (w - r >= s->cap) {
            /* Consumer fell behind: drop the oldest sample rather than the new
             * one, so the stream stays in time with the microphone. */
            atomic_store_explicit(&s->r, r + 1, memory_order_release);
        }
        s->ring[w % s->cap] = src[i];
        w++;
    }
    atomic_store_explicit(&s->w, w, memory_order_release);
    sem_post(&s->sem);
}

static void *sink_thread(void *arg) {
    sink_t *s = arg;
    int16_t chunk[2048];
    for (;;) {
        sem_wait(&s->sem);
        for (;;) {
            size_t r = atomic_load_explicit(&s->r, memory_order_relaxed);
            size_t w = atomic_load_explicit(&s->w, memory_order_acquire);
            size_t n = w - r;
            if (n == 0) break;
            if (n > 2048) n = 2048;
            for (size_t i = 0; i < n; i++) chunk[i] = s->ring[(r + i) % s->cap];
            atomic_store_explicit(&s->r, r + n, memory_order_release);
            fwrite(chunk, sizeof(int16_t), n, s->f);
            atomic_fetch_add(&s->written, (long long) n);
        }
        if (atomic_load(&s->stop)) break;
    }
    return NULL;
}

static sink_t *sink_open(const char *path, int rate) {
    sink_t *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->f = fopen(path, "wb");
    if (!s->f) {
        LOGE("sink: cannot open %s", path);
        free(s);
        return NULL;
    }
    s->cap = (size_t) rate * RING_SECONDS;
    s->ring = malloc(s->cap * sizeof(int16_t));
    if (!s->ring) {
        fclose(s->f);
        free(s);
        return NULL;
    }
    sem_init(&s->sem, 0, 0);
    if (pthread_create(&s->thread, NULL, sink_thread, s) != 0) {
        fclose(s->f);
        free(s->ring);
        free(s);
        return NULL;
    }
    return s;
}

static long long sink_close(sink_t *s) {
    if (!s) return 0;
    atomic_store(&s->stop, 1);
    sem_post(&s->sem);
    pthread_join(s->thread, NULL);
    long long n = atomic_load(&s->written);
    fclose(s->f);
    free(s->ring);
    sem_destroy(&s->sem);
    free(s);
    return n;
}

static int16_t to_i16(float v) {
    if (v > 1.0f) v = 1.0f;
    else if (v < -1.0f) v = -1.0f;
    return (int16_t) (v * 32767.0f);
}

// ----------------------------------------------------------------- AAudio

static aaudio_data_callback_result_t aaudio_on_data(
        AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
    (void) stream;
    sink_t *s = userData;
    const float *in = audioData;
    int16_t tmp[512];
    int32_t off = 0;
    while (off < numFrames) {
        int32_t n = numFrames - off;
        if (n > 512) n = 512;
        for (int32_t i = 0; i < n; i++) tmp[i] = to_i16(in[off + i]);
        sink_push(s, tmp, n);
        off += n;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static AAudioStream *aaudio_open_input(int rate, aaudio_data_callback_result_t (*cb)(AAudioStream *, void *, void *, int32_t), void *user) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return NULL;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setChannelCount(b, 1);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    if (cb) AAudioStreamBuilder_setDataCallback(b, cb, user);
    AAudioStream *st = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &st);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || st == NULL) {
        LOGE("aaudio open failed: %d", (int) r);
        return NULL;
    }
    return st;
}

JNIEXPORT jint JNICALL Java_org_voice_changer_lab_NativeCapture_aaudioRead(
        JNIEnv *env, jobject thiz, jstring jpath, jint seconds, jint rate) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -1;
    AAudioStream *st = aaudio_open_input(rate, NULL, NULL);
    if (!st) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -2;
    }
    int got = AAudioStream_getSampleRate(st);
    FILE *f = fopen(path, "wb");
    if (!f) {
        AAudioStream_close(st);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -3;
    }
    aaudio_result_t r = AAudioStream_requestStart(st);
    if (r != AAUDIO_OK) {
        LOGE("aaudio start: %d", (int) r);
        fclose(f);
        AAudioStream_close(st);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -4;
    }
    const int32_t frames = 512;
    float *buf = malloc(sizeof(float) * frames);
    int16_t *out = malloc(sizeof(int16_t) * frames);
    long long total = 0;
    long long want = (long long) got * seconds;
    while (buf && out && total < want) {
        int32_t n = AAudioStream_read(st, buf, frames, 200000000LL /* 200 ms */);
        if (n < 0) {
            LOGE("aaudio read: %d", (int) n);
            break;
        }
        if (n == 0) continue;
        for (int32_t i = 0; i < n; i++) out[i] = to_i16(buf[i]);
        fwrite(out, sizeof(int16_t), (size_t) n, f);
        total += n;
    }
    AAudioStream_requestStop(st);
    AAudioStream_close(st);
    fclose(f);
    free(buf);
    free(out);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    LOGI("aaudioRead rate=%d frames=%lld", got, total);
    return (jint) total;
}

JNIEXPORT jint JNICALL Java_org_voice_changer_lab_NativeCapture_aaudioCallback(
        JNIEnv *env, jobject thiz, jstring jpath, jint seconds, jint rate) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -1;
    sink_t *sink = sink_open(path, rate);
    if (!sink) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -2;
    }
    AAudioStream *st = aaudio_open_input(rate, aaudio_on_data, sink);
    if (!st) {
        sink_close(sink);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -3;
    }
    int got = AAudioStream_getSampleRate(st);
    aaudio_result_t r = AAudioStream_requestStart(st);
    if (r != AAUDIO_OK) {
        LOGE("aaudio start: %d", (int) r);
        AAudioStream_close(st);
        sink_close(sink);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -4;
    }
    struct timespec ts = {seconds, 0};
    nanosleep(&ts, NULL);
    AAudioStream_requestStop(st);
    AAudioStream_close(st);
    long long total = sink_close(sink);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    LOGI("aaudioCallback rate=%d frames=%lld", got, total);
    return (jint) total;
}

// --------------------------------------------------------------- OpenSL ES

typedef struct {
    sink_t *sink;
    SLObjectItf engine_obj;
    SLObjectItf rec_obj;
    SLEngineItf engine;
    SLRecordItf rec;
    SLAndroidSimpleBufferQueueItf bq;
    int16_t *bufs[4];
    int frames;
    int head;
} osl_t;

static void osl_on_buffer(SLAndroidSimpleBufferQueueItf bq, void *ctx) {
    osl_t *o = ctx;
    int i = o->head;
    o->head = (o->head + 1) % 4;
    sink_push(o->sink, o->bufs[i], o->frames);
    (*bq)->Enqueue(bq, o->bufs[i], (SLuint32) (o->frames * (int) sizeof(int16_t)));
}

static SLuint32 sl_rate(int rate) {
    switch (rate) {
        case 8000: return SL_SAMPLINGRATE_8;
        case 16000: return SL_SAMPLINGRATE_16;
        case 22050: return SL_SAMPLINGRATE_22_05;
        case 32000: return SL_SAMPLINGRATE_32;
        case 48000: return SL_SAMPLINGRATE_48;
        default: return SL_SAMPLINGRATE_44_1;
    }
}

JNIEXPORT jint JNICALL Java_org_voice_changer_lab_NativeCapture_openslRecord(
        JNIEnv *env, jobject thiz, jstring jpath, jint seconds, jint rate) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -1;
    osl_t o;
    memset(&o, 0, sizeof(o));
    o.frames = 1024;
    o.sink = sink_open(path, rate);
    if (!o.sink) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -2;
    }
    if (slCreateEngine(&o.engine_obj, 0, NULL, 0, NULL, NULL) != SL_RESULT_SUCCESS ||
        (*o.engine_obj)->Realize(o.engine_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS ||
        (*o.engine_obj)->GetInterface(o.engine_obj, SL_IID_ENGINE, &o.engine) != SL_RESULT_SUCCESS) {
        LOGE("opensl: engine init failed");
        sink_close(o.sink);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -3;
    }

    SLDataLocator_IODevice loc_dev = {
            SL_DATALOCATOR_IODEVICE, SL_IODEVICE_AUDIOINPUT, SL_DEFAULTDEVICEID_AUDIOINPUT, NULL};
    SLDataSource src = {&loc_dev, NULL};
    SLDataLocator_AndroidSimpleBufferQueue loc_bq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 4};
    SLDataFormat_PCM fmt = {
            SL_DATAFORMAT_PCM,
            1,
            sl_rate(rate),
            SL_PCMSAMPLEFORMAT_FIXED_16,
            SL_PCMSAMPLEFORMAT_FIXED_16,
            SL_SPEAKER_FRONT_CENTER,
            SL_BYTEORDER_LITTLEENDIAN};
    SLDataSink snk = {&loc_bq, &fmt};
    const SLInterfaceID ids[1] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean req[1] = {SL_BOOLEAN_TRUE};

    if ((*o.engine)->CreateAudioRecorder(o.engine, &o.rec_obj, &src, &snk, 1, ids, req) != SL_RESULT_SUCCESS ||
        (*o.rec_obj)->Realize(o.rec_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS ||
        (*o.rec_obj)->GetInterface(o.rec_obj, SL_IID_RECORD, &o.rec) != SL_RESULT_SUCCESS ||
        (*o.rec_obj)->GetInterface(o.rec_obj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &o.bq) != SL_RESULT_SUCCESS) {
        LOGE("opensl: recorder init failed");
        if (o.rec_obj) (*o.rec_obj)->Destroy(o.rec_obj);
        (*o.engine_obj)->Destroy(o.engine_obj);
        sink_close(o.sink);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -4;
    }

    for (int i = 0; i < 4; i++) {
        o.bufs[i] = calloc((size_t) o.frames, sizeof(int16_t));
        (*o.bq)->Enqueue(o.bq, o.bufs[i], (SLuint32) (o.frames * (int) sizeof(int16_t)));
    }
    (*o.bq)->RegisterCallback(o.bq, osl_on_buffer, &o);

    if ((*o.rec)->SetRecordState(o.rec, SL_RECORDSTATE_RECORDING) != SL_RESULT_SUCCESS) {
        LOGE("opensl: start failed");
    } else {
        struct timespec ts = {seconds, 0};
        nanosleep(&ts, NULL);
        (*o.rec)->SetRecordState(o.rec, SL_RECORDSTATE_STOPPED);
    }
    (*o.bq)->Clear(o.bq);
    long long total = sink_close(o.sink);
    for (int i = 0; i < 4; i++) free(o.bufs[i]);
    (*o.rec_obj)->Destroy(o.rec_obj);
    (*o.engine_obj)->Destroy(o.engine_obj);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    LOGI("openslRecord rate=%d frames=%lld", rate, total);
    return (jint) total;
}
