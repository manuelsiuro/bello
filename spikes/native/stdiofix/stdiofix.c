/*
 * Android 5.x (API < 23) libc exports only __sF, not the stdin/stdout/stderr
 * pointer symbols that prebuilt libvosk.so references. Provide them here;
 * libvosk.so is patched to DT_NEED this library, which must be loaded first.
 */
#include <stdio.h>
#undef stdin
#undef stdout
#undef stderr
FILE* stdin = &__sF[0];
FILE* stdout = &__sF[1];
FILE* stderr = &__sF[2];
