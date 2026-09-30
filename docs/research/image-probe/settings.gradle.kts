// A build of its own, outside sborka's, for the reason static-probe gives: the question is what a
// base image does with a binary, and this repository's conventions — `--as-needed` among them —
// would be standing between the question and the answer. The link mode is chosen here instead.
rootProject.name = "image-probe"
include(":curl")
