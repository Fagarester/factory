/* JNI for com.factorynative.Opus: the walkie-talkie's voice codec (libopus 1.5.2, BSD-3, built in statically). */
#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FN(name) Java_com_factorynative_Opus_##name

/* A voice encoder: mono, VOIP mode, the bitrate given, in-band FEC for lost packets. 0 on failure. */
JNIEXPORT jlong JNICALL FN(encCreate)(JNIEnv *env, jclass cls, jint rate, jint bitrate, jint complexity) {
    int err = 0;
    OpusEncoder *e = opus_encoder_create(rate, 1, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || !e) return 0;
    opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(e, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(e, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(e, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(e, OPUS_SET_PACKET_LOSS_PERC(10));
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL FN(encFree)(JNIEnv *env, jclass cls, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}

/* n samples → bytes in out; the length, or < 0 (an Opus error). */
JNIEXPORT jint JNICALL FN(encode)(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint n, jbyteArray out) {
    if (!h || n <= 0 || (*env)->GetArrayLength(env, pcm) < n) return OPUS_BAD_ARG;
    jshort *p = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int r = opus_encode((OpusEncoder *)(intptr_t)h, p, n, (unsigned char *)o, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, o, r > 0 ? 0 : JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, pcm, p, JNI_ABORT);
    return r;
}

JNIEXPORT jlong JNICALL FN(decCreate)(JNIEnv *env, jclass cls, jint rate) {
    int err = 0;
    OpusDecoder *d = opus_decoder_create(rate, 1, &err);
    return err == OPUS_OK && d ? (jlong)(intptr_t)d : 0;
}

JNIEXPORT void JNICALL FN(decFree)(JNIEnv *env, jclass cls, jlong h) {
    if (h) opus_decoder_destroy((OpusDecoder *)(intptr_t)h);
}

/* A packet (data[off, off+len)) → up to out.length samples; data null: a lost packet made up (PLC); fec 1: the
 * packet BEFORE this one, from its in-band FEC (out.length samples). Samples, or < 0. */
JNIEXPORT jint JNICALL FN(decode)(JNIEnv *env, jclass cls, jlong h, jbyteArray data, jint off, jint len, jshortArray out, jint fec) {
    if (!h) return OPUS_BAD_ARG;
    jint n = (*env)->GetArrayLength(env, out);
    jbyte *d = NULL;
    if (data) {
        if (off < 0 || len <= 0 || off + len > (*env)->GetArrayLength(env, data)) return OPUS_BAD_ARG;
        d = (*env)->GetByteArrayElements(env, data, NULL);
    }
    jshort *o = (*env)->GetShortArrayElements(env, out, NULL);
    int r = opus_decode((OpusDecoder *)(intptr_t)h, d ? (const unsigned char *)d + off : NULL, d ? len : 0, o, n, fec);
    (*env)->ReleaseShortArrayElements(env, out, o, r > 0 ? 0 : JNI_ABORT);
    if (d) (*env)->ReleaseByteArrayElements(env, data, d, JNI_ABORT);
    return r;
}
