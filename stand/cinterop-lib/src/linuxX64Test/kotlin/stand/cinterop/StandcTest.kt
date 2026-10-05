package stand.cinterop

import kotlinx.cinterop.ExperimentalForeignApi
import stand.cinterop.c.standc_answer
import kotlin.test.Test
import kotlin.test.assertEquals

// THE CONSUMER CHECK, from inside the module: this test binary carries no linker options of its own,
// so it links only if the klib carries the archive - the way a stranger's binary would.
@OptIn(ExperimentalForeignApi::class)
class StandcTest {
    @Test
    fun theArchiveTheKlibCarriesIsLinked() {
        assertEquals(42, standc_answer(1))
    }
}
