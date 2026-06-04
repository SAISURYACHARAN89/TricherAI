/*
KISS FFT real FFT wrapper (BSD-like). Adapted for internal use.
See kiss_fft.h for license text.
*/

#include "kiss_fftr.h"
#include <stdlib.h>
#include <string.h>

typedef struct kiss_fftr_state {
    int nfft;
    int inverse;
    kiss_fft_cfg substate;
    kiss_fft_cpx *tmpbuf;
} kiss_fftr_state;

kiss_fftr_cfg kiss_fftr_alloc(int nfft, int inverse_fft, void *mem, size_t *lenmem) {
    size_t memneeded = sizeof(kiss_fftr_state) + sizeof(kiss_fft_cpx) * nfft;
    if (lenmem) {
        *lenmem = memneeded;
    }
    if (mem == NULL) {
        mem = malloc(memneeded);
        if (!mem) return NULL;
    }

    kiss_fftr_state *st = (kiss_fftr_state *)mem;
    st->nfft = nfft;
    st->inverse = inverse_fft;
    st->tmpbuf = (kiss_fft_cpx *)((char *)mem + sizeof(kiss_fftr_state));
    st->substate = kiss_fft_alloc(nfft, inverse_fft, NULL, NULL);
    return (kiss_fftr_cfg)st;
}

void kiss_fftr_free(void *cfg) {
    if (!cfg) return;
    kiss_fftr_state *st = (kiss_fftr_state *)cfg;
    if (st->substate) kiss_fft_free(st->substate);
    free(cfg);
}

void kiss_fftr(kiss_fftr_cfg cfg, const KISS_FFT_SCALAR *timedata, kiss_fft_cpx *freqdata) {
    kiss_fftr_state *st = (kiss_fftr_state *)cfg;
    int n = st->nfft;
    for (int i = 0; i < n; ++i) {
        st->tmpbuf[i].r = timedata[i];
        st->tmpbuf[i].i = 0.0f;
    }
    kiss_fft(st->substate, st->tmpbuf, freqdata);
}

