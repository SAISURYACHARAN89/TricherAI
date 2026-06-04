#include <jni.h>
#include <cmath>
#include <vector>
#include <thread>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace {
struct Complex {
    float re;
    float im;
};

inline Complex add(const Complex &a, const Complex &b) {
    return {a.re + b.re, a.im + b.im};
}

inline Complex sub(const Complex &a, const Complex &b) {
    return {a.re - b.re, a.im - b.im};
}

inline Complex mul(const Complex &a, const Complex &b) {
    return {a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re};
}

void fft(std::vector<Complex> &a, bool invert) {
    int n = static_cast<int>(a.size());
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) {
            Complex tmp = a[i];
            a[i] = a[j];
            a[j] = tmp;
        }
    }

    for (int len = 2; len <= n; len <<= 1) {
        float ang = static_cast<float>((invert ? -1.0 : 1.0) * 2.0 * M_PI / len);
        Complex wlen = {std::cos(ang), std::sin(ang)};
        for (int i = 0; i < n; i += len) {
            Complex w = {1.0f, 0.0f};
            for (int j = 0; j < len / 2; j++) {
                Complex u = a[i + j];
                Complex v = mul(a[i + j + len / 2], w);
                a[i + j] = add(u, v);
                a[i + j + len / 2] = sub(u, v);
                w = mul(w, wlen);
            }
        }
    }

    if (invert) {
        float invN = 1.0f / n;
        for (int i = 0; i < n; i++) {
            a[i].re *= invN;
            a[i].im *= invN;
        }
    }
}

int nextPow2(int v) {
    int n = 1;
    while (n < v) n <<= 1;
    return n;
}

void prepareBluestein(int n, int m, std::vector<Complex> &chirp, std::vector<Complex> &bFft) {
    chirp.resize(n);
    std::vector<Complex> b(m, {0.0f, 0.0f});

    for (int i = 0; i < n; i++) {
        double angle = M_PI * (static_cast<double>(i) * i) / n;
        float c = static_cast<float>(std::cos(angle));
        float s = static_cast<float>(std::sin(angle));
        chirp[i] = {c, s};
        b[i] = {c, s};
        if (i != 0) {
            b[m - i] = {c, s};
        }
    }

    bFft = b;
    fft(bFft, false);
}

void dftBluestein(const float *input, int n, int m,
                  const std::vector<Complex> &chirp,
                  const std::vector<Complex> &bFft,
                  std::vector<Complex> &scratch,
                  std::vector<Complex> &out) {
    scratch.assign(m, {0.0f, 0.0f});

    for (int i = 0; i < n; i++) {
        Complex c = chirp[i];
        // input * conj(chirp)
        scratch[i] = {input[i] * c.re, -input[i] * c.im};
    }

    fft(scratch, false);
    for (int i = 0; i < m; i++) {
        scratch[i] = mul(scratch[i], bFft[i]);
    }
    fft(scratch, true);

    out.resize(n);
    for (int i = 0; i < n; i++) {
        Complex c = chirp[i];
        // result * conj(chirp)
        Complex v = scratch[i];
        out[i] = {v.re * c.re + v.im * c.im, -v.re * c.im + v.im * c.re};
    }
}
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_whispertflite_utils_NativeFft_computeMelSpectrogram(
        JNIEnv *env,
        jclass,
        jfloatArray jSamples,
        jint nSamples,
        jint nThreads,
        jfloatArray jFilters,
        jint nMel,
        jint nFft,
        jint fftStep,
        jint melLen,
        jint /*expectedSamples*/,
        jint fftSize) {
    if (jSamples == nullptr || jFilters == nullptr) {
        return nullptr;
    }

    jfloat *samples = env->GetFloatArrayElements(jSamples, nullptr);
    jfloat *filters = env->GetFloatArrayElements(jFilters, nullptr);
    if (samples == nullptr || filters == nullptr) {
        if (samples != nullptr) env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);
        if (filters != nullptr) env->ReleaseFloatArrayElements(jFilters, filters, JNI_ABORT);
        return nullptr;
    }

    int threads = nThreads <= 0 ? 1 : nThreads;
    int m = nextPow2(2 * fftSize - 1);

    std::vector<float> hann(static_cast<size_t>(fftSize), 0.0f);
    for (int i = 0; i < fftSize; i++) {
        hann[i] = static_cast<float>(0.5 * (1.0 - std::cos(2.0 * M_PI * i / fftSize)));
    }

    std::vector<Complex> chirp;
    std::vector<Complex> bFft;
    prepareBluestein(fftSize, m, chirp, bFft);

    std::vector<float> melData(static_cast<size_t>(nMel) * melLen, 0.0f);

    auto worker = [&](int tid) {
        std::vector<Complex> scratch;
        std::vector<Complex> spectrum;
        std::vector<float> power(nFft, 0.0f);
        std::vector<float> frame(static_cast<size_t>(fftSize), 0.0f);

        for (int i = tid; i < melLen; i += threads) {
            int offset = i * fftStep;
            for (int j = 0; j < fftSize; j++) {
                int idx = offset + j;
                frame[j] = (idx < nSamples) ? samples[idx] * hann[j] : 0.0f;
            }

            dftBluestein(frame.data(), fftSize, m, chirp, bFft, scratch, spectrum);

            if (nFft > 0) {
                power[0] = spectrum[0].re * spectrum[0].re + spectrum[0].im * spectrum[0].im;
            }
            for (int k = 1; k < nFft - 1; k++) {
                float mag = spectrum[k].re * spectrum[k].re + spectrum[k].im * spectrum[k].im;
                power[k] = 2.0f * mag;
            }
            if (nFft > 1) {
                int k = nFft - 1;
                power[k] = spectrum[k].re * spectrum[k].re + spectrum[k].im * spectrum[k].im;
            }

            for (int mel = 0; mel < nMel; mel++) {
                double sum = 0.0;
                int filterOffset = mel * nFft;
                for (int k = 0; k < nFft; k++) {
                    sum += power[k] * filters[filterOffset + k];
                }
                float value = static_cast<float>(std::log10(std::max(sum, 1e-10)));
                melData[static_cast<size_t>(mel) * melLen + i] = value;
            }
        }
    };

    std::vector<std::thread> pool;
    pool.reserve(threads);
    for (int t = 0; t < threads; t++) {
        pool.emplace_back(worker, t);
    }
    for (auto &t : pool) {
        t.join();
    }

    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);
    env->ReleaseFloatArrayElements(jFilters, filters, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(melData.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(melData.size()), melData.data());
    return result;
}
