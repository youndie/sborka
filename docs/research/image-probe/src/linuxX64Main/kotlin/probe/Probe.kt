package probe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import platform.posix.errno
import platform.posix.exit
import platform.posix.iconv_close
import platform.posix.iconv_open

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null -> println("probe: loaded")
        "iconv" -> {
            // (iconv_t) -1 is the failure value; glibc sets EINVAL when the conversion's module
            // cannot be found, which is what a base without gconv produces.
            val cd = iconv_open("UTF-16LE", "UTF-8")
            if (cd.toLong() == -1L) {
                println("probe: iconv UTF-8 -> UTF-16LE refused, errno=$errno")
                exit(3)
            }
            iconv_close(cd)
            println("probe: iconv UTF-8 -> UTF-16LE ok")
        }
        else -> {
            println("probe: unknown argument ${args.first()}")
            exit(2)
        }
    }
}
