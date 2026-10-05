#ifndef STANDC_H
#define STANDC_H

/* The one function the stand binds. It lives in libstandc.a, not in the binding, so a klib that does
 * not carry the archive cannot be linked by anybody. */
int standc_answer(int seed);

#endif
