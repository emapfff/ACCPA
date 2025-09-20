package org.stella

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream

internal class MainTest {
    @ParameterizedTest(name = "{index} Typechecking well-typed program {0}")
    @MethodSource("wellTypedPathStream")
    @Throws(
        IOException::class,
        Exception::class
    )
    fun testWellTyped(filepath: Path) {
        println(filepath.toUri())
        val original = System.`in`
        val fips = FileInputStream(filepath.toFile())
        System.setIn(fips)
        main()
        System.setIn(original)
    }

    @ParameterizedTest(name = "{index} Typechecking ill-typed program {0}")
    @MethodSource("illTypedPathStream")
    @Throws(
        IOException::class,
        Exception::class
    )
    fun testIllTyped(filepath: Path) {
        println(filepath.toUri())
        val original = System.`in`
        val fips = FileInputStream(filepath.toFile())
        System.setIn(fips)
        assertThrows<Exception>("expected the typechecker to fail!") {
            main()
        }
        System.setIn(original)
    }

    companion object {
        private const val BASE_DIR = "src/test/resources"
        private const val WELL_TYPED_DIR = "$BASE_DIR/well-typed"
        private const val ILL_TYPED_DIR = "$BASE_DIR/ill-typed"

        @JvmStatic
        fun wellTypedPathStream(): Stream<Path> = getFilesStream(WELL_TYPED_DIR)

        @JvmStatic
        fun illTypedPathStream(): Stream<Path> = getFilesStream(ILL_TYPED_DIR)

        private fun getFilesStream(path: String) = Files.list(Paths.get(path))
    }
}
