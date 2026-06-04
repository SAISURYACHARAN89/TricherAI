/*
Simple KISS FFT implementation (BSD-like). Adapted for internal use.
See kiss_fft.h for license text.
*/

#include "kiss_fft.h"
#include <string.h>
#include <math.h>

static void kf_cexp(kiss_fft_cpx *out, float phase) {
    out->r = cosf(phase);
    out->i = sinf(phase);
}

kiss_fft_cfg kiss_fft_alloc(int nfft, int inverse_fft, void *mem, size_t *lenmem) {
    size_t memneeded = sizeof(kiss_fft_state) + sizeof(kiss_fft_cpx) * nfft;
    if (lenmem) {
        *lenmem = memneeded;
    }
    if (mem == NULL) {
        mem = malloc(memneeded);
        if (!mem) return NULL;
    }

    kiss_fft_state *st = (kiss_fft_state *)mem;
    st->nfft = nfft;
    st->inverse = inverse_fft;
    st->twiddles = (kiss_fft_cpx *)((char *)mem + sizeof(kiss_fft_state));

    for (int i = 0; i < nfft; ++i) {
        float phase = -2.0f * (float)M_PI * i / nfft;
        if (inverse_fft) phase = -phase;
        kf_cexp(&st->twiddles[i], phase);
    }
    return (kiss_fft_cfg)st;
}

void kiss_fft_free(void *cfg) {
    free(cfg);
}

static void kf_bfly(kiss_fft_cfg cfg, const kiss_fft_cpx *fin, kiss_fft_cpx *fout) {
    int n = cfg->nfft;
    for (int k = 0; k < n; ++k) {
        float sumr = 0.0f;
        float sumi = 0.0f;
        for (int t = 0; t < n; ++t) {
            int tw = (t * k) % n;
            float wr = cfg->twiddles[tw].r;
            float wi = cfg->twiddles[tw].i;
            sumr += fin[t].r * wr - fin[t].i * wi;
            sumi += fin[t].r * wi + fin[t].i * wr;
        }
        fout[k].r = sumr;
        fout[k].i = sumi;
    }
}

void kiss_fft(kiss_fft_cfg cfg, const kiss_fft_cpx *fin, kiss_fft_cpx *fout) {
    if (!cfg || !fin || !fout) return;
    kf_bfly(cfg, fin, fout);

    if (cfg->inverse) {
        float scale = 1.0f / cfg->nfft;
        for (int i = 0; i < cfg->nfft; ++i) {
            fout[i].r *= scale;
            fout[i].i *= scale;
        }
    }
}


