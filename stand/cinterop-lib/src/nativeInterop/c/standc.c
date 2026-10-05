/* Compiled into libstandc.a by the stand's build: an archive of its own rather than C after `---`
 * in the .def, which cinterop would compile into the klib and so prove nothing about carrying. */
int standc_answer(int seed) {
    return seed + 41;
}
