/*
KISS FFT real FFT wrapper (BSD-like). Adapted for internal use.
See kiss_fft.h for license text.
*/

#ifndef KISS_FFTR_H
#define KISS_FFTR_H

#include "kiss_fft.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct kiss_fftr_state *kiss_fftr_cfg;

kiss_fftr_cfg kiss_fftr_alloc(int nfft, int inverse_fft, void *mem, size_t *lenmem);
void kiss_fftr(kiss_fftr_cfg cfg, const KISS_FFT_SCALAR *timedata, kiss_fft_cpx *freqdata);
void kiss_fftr_free(void *cfg);

#ifdef __cplusplus
}
#endif

#endif

